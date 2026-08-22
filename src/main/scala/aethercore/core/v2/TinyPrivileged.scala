package aethercore.core.v2

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum
import aethercore.common.{CsrOp, MachineExceptionCode, PrivilegeMode, XRetOp}
import aethercore.config.{CoreConfig, IsaConfig}
import aethercore.core.MachineCsrFile

object PrivilegedRedirectKind extends ChiselEnum {
  val Trap, Return = Value
}

/** Frontend-facing consequence of a precisely retiring trap or xRET. */
class PrivilegedRedirect(val xlen: Int) extends Bundle {
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val robToken = new RobToken(IdentityBits, GenerationBits)
  val target = UInt(xlen.W)
  val kind = PrivilegedRedirectKind()
}

/**
  * F5 completion generator for serialized system instructions.
  *
  * It is intentionally side-effect free. CSR reads/legality and the pending
  * write value are computed while the instruction is the oldest ready uOp, but
  * MachineCsrFile is not mutated here. ECALL/EBREAK/predecoded exceptions are
  * turned into tagged exception completions. xRET is validated and recorded as
  * a pending return effect. All architectural state changes happen later when
  * the matching ROB head retires.
  *
  * Later phases may opt into a semantic operation whose architectural effect is
  * still owned at retirement. F6 uses this narrow seam for SFENCE.VMA. F7 may
  * opt into WFI once an explicit asynchronous wake/interrupt owner is present.
  * Defaults remain false so the frozen earlier phases keep their fail-closed
  * behavior.
  */
class TinySystemCompletion(
    val isa: IsaConfig,
    val allowSfenceVma: Boolean = false,
    val allowWfi: Boolean = false
) extends Module {
  private val xlen = isa.xlen
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val head = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    val headDependenciesValid = Input(Bool())
    val headRs1 = Input(new OperandState(xlen, IdentityBits, GenerationBits))
    val headOperandsReady = Input(Bool())

    val csrReadAddr = Output(UInt(12.W))
    val csrReadData = Input(UInt(xlen.W))
    val csrReadImplemented = Input(Bool())
    val csrReadWritable = Input(Bool())
    val currentPrivilege = Input(UInt(2.W))

    val completion = Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  })

  private val decoded = io.head.bits.decoded
  private val systemKind = decoded.system.kind
  private val isSystemHead = io.head.valid && io.head.bits.executionClass === ExecutionClass.System
  private val hasPredecodedException = io.head.valid && decoded.exception.valid
  private val headRecordReady = io.headDependenciesValid
  private val operandsReady = io.headDependenciesValid && io.headOperandsReady

  io.csrReadAddr := decoded.system.csrAddress
  io.completion.valid := false.B
  io.completion.bits := 0.U.asTypeOf(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  io.completion.bits.robToken := io.head.bits.robToken
  io.completion.bits.producerTag := io.head.bits.producerTag
  io.completion.bits.valueRef := io.head.bits.valueRef

  private val instructionTrapValue = if (xlen == 32) {
    decoded.rawInst
  } else {
    Cat(0.U((xlen - 32).W), decoded.rawInst)
  }

  private def markIllegalInstruction(): Unit = {
    io.completion.bits.exception.valid := true.B
    io.completion.bits.exception.cause := MachineExceptionCode.IllegalInstruction.U(xlen.W)
    io.completion.bits.exception.value := instructionTrapValue
  }

  private val csrImmediate = decoded.system.csrImmediate.pad(xlen)
  private val csrOperand = Mux(decoded.system.csrUseImmediate, csrImmediate, io.headRs1.value)
  private val csrSourceFieldNonZero = Mux(
    decoded.system.csrUseImmediate,
    decoded.system.csrImmediate.orR,
    decoded.rs1 =/= 0.U
  )
  private val csrWriteIntent = decoded.system.csrOp === CsrOp.Write ||
    ((decoded.system.csrOp === CsrOp.Set || decoded.system.csrOp === CsrOp.Clear) &&
      csrSourceFieldNonZero)
  private val csrPrivilegeLegal = io.currentPrivilege >= decoded.system.csrAddress(9, 8)
  private val csrLegal = isa.hasZicsr.B &&
    io.csrReadImplemented &&
    csrPrivilegeLegal &&
    (!csrWriteIntent || io.csrReadWritable)

  private val csrWriteData = WireDefault(csrOperand)
  switch(decoded.system.csrOp) {
    is(CsrOp.Set)   { csrWriteData := io.csrReadData | csrOperand }
    is(CsrOp.Clear) { csrWriteData := io.csrReadData & ~csrOperand }
  }

  private val environmentCallCause = Mux(
    io.currentPrivilege === PrivilegeMode.User.U,
    MachineExceptionCode.EnvironmentCallFromU.U(xlen.W),
    Mux(
      io.currentPrivilege === PrivilegeMode.Supervisor.U,
      MachineExceptionCode.EnvironmentCallFromS.U(xlen.W),
      MachineExceptionCode.EnvironmentCallFromM.U(xlen.W)
    )
  )

  private val machineReturnLegal =
    decoded.system.xret === XRetOp.Machine && io.currentPrivilege === PrivilegeMode.Machine.U
  private val supervisorReturnLegal =
    decoded.system.xret === XRetOp.Supervisor && isa.hasS.B &&
      io.currentPrivilege >= PrivilegeMode.Supervisor.U
  private val xretLegal = machineReturnLegal || supervisorReturnLegal
  private val sfenceAvailable = allowSfenceVma.B && isa.hasPagedVirtualMemory.B
  private val wfiAvailable = allowWfi.B

  private val implementedSystem =
    systemKind === SystemOperationKind.Csr ||
      systemKind === SystemOperationKind.Ecall ||
      systemKind === SystemOperationKind.Ebreak ||
      systemKind === SystemOperationKind.Xret ||
      systemKind === SystemOperationKind.Fence ||
      (systemKind === SystemOperationKind.FenceI && isa.hasZifencei.B) ||
      (systemKind === SystemOperationKind.SfenceVma && sfenceAvailable) ||
      (systemKind === SystemOperationKind.Wfi && wfiAvailable)

  // A predecoded exception is already an architectural semantic fact and must
  // not wait for a source value that the faulting instruction will never use.
  when(headRecordReady && hasPredecodedException) {
    io.completion.valid := true.B
    io.completion.bits.exception := decoded.exception
  }.elsewhen(operandsReady && isSystemHead) {
    // A deferred or unavailable system operation must fail closed instead of
    // leaving the oldest ROB entry permanently incomplete.
    io.completion.valid := true.B
    when(!implementedSystem) {
      markIllegalInstruction()
    }.otherwise {
      switch(systemKind) {
        is(SystemOperationKind.Csr) {
          when(csrLegal && decoded.system.csrOp =/= CsrOp.None) {
            io.completion.bits.hasValue := io.head.bits.producesValue
            io.completion.bits.value := io.csrReadData
            io.completion.bits.privileged.csrWriteValid := csrWriteIntent
            io.completion.bits.privileged.csrAddress := decoded.system.csrAddress
            io.completion.bits.privileged.csrWriteData := csrWriteData
          }.otherwise {
            markIllegalInstruction()
          }
        }
        is(SystemOperationKind.Ecall) {
          io.completion.bits.exception.valid := true.B
          io.completion.bits.exception.cause := environmentCallCause
          io.completion.bits.exception.value := 0.U
        }
        is(SystemOperationKind.Ebreak) {
          io.completion.bits.exception.valid := true.B
          io.completion.bits.exception.cause := MachineExceptionCode.Breakpoint.U(xlen.W)
          io.completion.bits.exception.value := decoded.pc
        }
        is(SystemOperationKind.Xret) {
          when(xretLegal) {
            io.completion.bits.privileged.trapReturn := true.B
            io.completion.bits.privileged.trapReturnSupervisor :=
              decoded.system.xret === XRetOp.Supervisor
          }.otherwise {
            markIllegalInstruction()
          }
        }
        // F7's asynchronous owner consumes the retirement event. WFI remains
        // side-effect free here so only Commit-time state can put the frontend
        // to sleep. U-mode is illegal in the current privileged profile.
        is(SystemOperationKind.Wfi) {
          when(io.currentPrivilege === PrivilegeMode.User.U) {
            markIllegalInstruction()
          }
        }
        // With strict-oldest issue and no caches in the v2 bring-up backend,
        // FENCE/FENCE.I are conservative serialized no-ops. FENCE.I reaches
        // this case only when Zifencei is present.
        is(SystemOperationKind.Fence)  { }
        is(SystemOperationKind.FenceI) { }
        // F6 opts into a full data-side translation flush at retirement. The
        // completion itself is side-effect free. TVM is not implemented by the
        // current qualified MachineCsrFile, so legality is S/M privilege only.
        is(SystemOperationKind.SfenceVma) {
          when(io.currentPrivilege < PrivilegeMode.Supervisor.U) {
            markIllegalInstruction()
          }
        }
      }
    }
  }
}

/**
  * F5 precise privileged-state composition.
  *
  * The existing MachineCsrFile remains the sole CSR/privilege/WARL/trap-state
  * owner. F5 only decides when a validated, completed ROB head may drive its
  * trapEnter/trapReturn/write interfaces. Async interrupts, WFI and SFENCE.VMA
  * are intentionally deferred.
  */
class TinyPrivilegedBackend(val config: CoreConfig) extends Module {
  private val isa = config.isa
  private val xlen = isa.xlen
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val commit = Output(new aethercore.common.CommitTrace(xlen = xlen))
    val branchRedirect = Valid(new RecoveryRedirect(xlen))
    val privilegedRedirect = Valid(new PrivilegedRedirect(xlen))
    val currentPrivilege = Output(UInt(2.W))
    val time = if (isa.hasTimeCounter) Some(Input(UInt(64.W))) else None
    val occupancy = Output(UInt(log2Ceil(TinyRobGeometry.Entries + 1).W))
  })

  val dependencyBackend = Module(new TinyDependencyBackend(xlen))
  val issue = Module(new TinyOldestIssue(xlen))
  val execution = Module(new TinyExecutionCluster(xlen, isa.hasC))
  val system = Module(new TinySystemCompletion(isa))
  val csrFile = Module(new MachineCsrFile(
    isa,
    config.platform.paddrBits,
    withMachineExternalInterrupt = false,
    withSupervisorExternalInterrupt = false
  ))

  private val retiring = dependencyBackend.io.retiring
  private val retiringSystem = retiring.valid &&
    retiring.bits.uop.executionClass === ExecutionClass.System
  private val trapAtRetire = retiring.valid && retiring.bits.exception.valid
  private val returnAtRetire = retiringSystem &&
    !retiring.bits.exception.valid &&
    retiring.bits.privileged.trapReturn
  private val privilegedBoundary = trapAtRetire || returnAtRetire

  dependencyBackend.io.dispatch.valid := io.dispatch.valid && !privilegedBoundary
  dependencyBackend.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := dependencyBackend.io.dispatch.ready && !privilegedBoundary
  io.allocated := dependencyBackend.io.allocated
  io.commit := dependencyBackend.io.commit
  io.occupancy := dependencyBackend.io.occupancy

  // Normal execution never consumes a system/predecoded-exception head. Those
  // are completed by TinySystemCompletion below.
  issue.io.head := dependencyBackend.io.head
  issue.io.head.valid := dependencyBackend.io.head.valid &&
    dependencyBackend.io.head.bits.executionClass =/= ExecutionClass.System &&
    !dependencyBackend.io.head.bits.decoded.exception.valid
  issue.io.headDependenciesValid := dependencyBackend.io.headDependenciesValid
  issue.io.headRs1 := dependencyBackend.io.headRs1
  issue.io.headRs2 := dependencyBackend.io.headRs2
  issue.io.headOperandsReady := dependencyBackend.io.headOperandsReady
  execution.io.request <> issue.io.request

  system.io.head := dependencyBackend.io.head
  system.io.headDependenciesValid := dependencyBackend.io.headDependenciesValid
  system.io.headRs1 := dependencyBackend.io.headRs1
  system.io.headOperandsReady := dependencyBackend.io.headOperandsReady

  csrFile.io.readAddr := system.io.csrReadAddr
  system.io.csrReadData := csrFile.io.readData
  system.io.csrReadImplemented := csrFile.io.readImplemented
  system.io.csrReadWritable := csrFile.io.readWritable
  system.io.currentPrivilege := csrFile.io.currentPrivilege
  io.currentPrivilege := csrFile.io.currentPrivilege

  assert(!(system.io.completion.valid && execution.io.response.valid),
    "oldest-only F5 cannot produce normal and system completions simultaneously")
  dependencyBackend.io.completion.valid := system.io.completion.valid || execution.io.response.valid
  dependencyBackend.io.completion.bits := Mux(
    system.io.completion.valid,
    system.io.completion.bits,
    execution.io.response.bits
  )
  execution.io.response.ready := !system.io.completion.valid

  io.branchRedirect.valid := dependencyBackend.io.acceptedRecovery.valid
  io.branchRedirect.bits := 0.U.asTypeOf(new RecoveryRedirect(xlen))
  io.branchRedirect.bits.robToken := dependencyBackend.io.acceptedRecovery.bits.robToken
  io.branchRedirect.bits.target := dependencyBackend.io.acceptedRecovery.bits.branchTarget

  csrFile.io.writeEnable := retiringSystem &&
    !retiring.bits.exception.valid &&
    retiring.bits.privileged.csrWriteValid
  csrFile.io.writeAddr := retiring.bits.privileged.csrAddress
  csrFile.io.writeData := retiring.bits.privileged.csrWriteData
  csrFile.io.timerInterrupt := false.B
  if (isa.hasTimeCounter) {
    // F5 exposes the architectural counter source only. Interrupt take remains
    // deliberately deferred and timerInterrupt stays tied off above.
    csrFile.io.time.get := io.time.get
  }
  csrFile.io.trapEnter := trapAtRetire
  csrFile.io.trapPc := retiring.bits.uop.decoded.pc
  csrFile.io.trapCause := retiring.bits.exception.cause
  csrFile.io.trapValue := retiring.bits.exception.value
  csrFile.io.trapReturn := returnAtRetire
  csrFile.io.trapReturnSupervisor :=
    returnAtRetire && retiring.bits.privileged.trapReturnSupervisor

  io.privilegedRedirect.valid := privilegedBoundary
  io.privilegedRedirect.bits := 0.U.asTypeOf(new PrivilegedRedirect(xlen))
  io.privilegedRedirect.bits.robToken := retiring.bits.uop.robToken
  io.privilegedRedirect.bits.target := Mux(trapAtRetire, csrFile.io.trapVector, csrFile.io.returnPc)
  io.privilegedRedirect.bits.kind := Mux(
    trapAtRetire,
    PrivilegedRedirectKind.Trap,
    PrivilegedRedirectKind.Return
  )
}
