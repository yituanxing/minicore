package aethercore.core.semantic

import chisel3._
import chisel3.util._
import aethercore.common.{CsrOp, MachineExceptionCode, PrivilegeMode, TrapInfo, XRetOp}
import aethercore.config.IsaConfig

/** Architectural effects computed at the head, not applied until retirement.
  *
  * The shared evaluator never owns CSR registers, ROB tokens, completion
  * backpressure, or commit timing. Both Tiny and Product transport this record
  * using their own exact-lifetime completion protocol.
  */
class PendingPrivilegedEffect(val xlen: Int) extends Bundle {
  require(xlen == 32 || xlen == 64, s"privileged-effect XLEN must be 32 or 64, got $xlen")

  val csrWriteValid = Bool()
  val csrAddress = UInt(12.W)
  val csrWriteData = UInt(xlen.W)
  val trapReturn = Bool()
  val trapReturnSupervisor = Bool()
  val sfenceVma = Bool()
  val fenceI = Bool()
  val wfi = Bool()
}

class SystemSemanticResult(val xlen: Int) extends Bundle {
  val hasValue = Bool()
  val value = UInt(xlen.W)
  val exception = new TrapInfo(xlen)
  val privileged = new PendingPrivilegedEffect(xlen)
}

/** One authoritative, backend-independent CSR/SYSTEM semantics evaluator.
  *
  * Inputs describe only architectural decode, the resolved rs1 value, and a
  * read-only CSR view. Predecoded faults take priority over system legality.
  * Tiny and Product independently decide when their oldest uOp may evaluate,
  * how to hold the result under completion backpressure, and when to retire.
  */
class SystemSemanticEvaluator(
    val isa: IsaConfig,
    val allowSfenceVma: Boolean = false,
    val allowWfi: Boolean = false
) extends Module {
  private val xlen = isa.xlen

  val io = IO(new Bundle {
    val decoded = Input(new DecodedInstruction(xlen))
    val producesValue = Input(Bool())
    val rs1Value = Input(UInt(xlen.W))
    val csrReadAddr = Output(UInt(12.W))
    val csrReadData = Input(UInt(xlen.W))
    val csrReadImplemented = Input(Bool())
    val csrReadWritable = Input(Bool())
    val currentPrivilege = Input(UInt(2.W))
    val result = Output(new SystemSemanticResult(xlen))
  })

  private val decoded = io.decoded
  private val kind = decoded.system.kind
  io.csrReadAddr := decoded.system.csrAddress
  io.result := 0.U.asTypeOf(io.result)

  private val instructionTrapValue = if (xlen == 32) {
    decoded.rawInst
  } else {
    Cat(0.U((xlen - 32).W), decoded.rawInst)
  }

  private def markIllegalInstruction(): Unit = {
    io.result.exception.valid := true.B
    io.result.exception.cause := MachineExceptionCode.IllegalInstruction.U(xlen.W)
    io.result.exception.value := instructionTrapValue
  }

  private val csrImmediate = decoded.system.csrImmediate.pad(xlen)
  private val csrOperand = Mux(decoded.system.csrUseImmediate, csrImmediate, io.rs1Value)
  private val csrSourceFieldNonZero = Mux(
    decoded.system.csrUseImmediate,
    decoded.system.csrImmediate.orR,
    decoded.rs1 =/= 0.U
  )
  private val csrWriteIntent = decoded.system.csrOp === CsrOp.Write ||
    ((decoded.system.csrOp === CsrOp.Set || decoded.system.csrOp === CsrOp.Clear) &&
      csrSourceFieldNonZero)
  private val csrPrivilegeLegal = io.currentPrivilege >= decoded.system.csrAddress(9, 8)
  private val csrLegal = isa.hasZicsr.B && io.csrReadImplemented &&
    csrPrivilegeLegal && (!csrWriteIntent || io.csrReadWritable)

  private val csrWriteData = WireDefault(csrOperand)
  switch(decoded.system.csrOp) {
    is(CsrOp.Set)   { csrWriteData := io.csrReadData | csrOperand }
    is(CsrOp.Clear) { csrWriteData := io.csrReadData & ~csrOperand }
  }

  private val environmentCallCause = Mux(
    io.currentPrivilege === PrivilegeMode.User.U,
    MachineExceptionCode.EnvironmentCallFromU.U(xlen.W),
    Mux(io.currentPrivilege === PrivilegeMode.Supervisor.U,
      MachineExceptionCode.EnvironmentCallFromS.U(xlen.W),
      MachineExceptionCode.EnvironmentCallFromM.U(xlen.W))
  )
  private val machineReturnLegal =
    decoded.system.xret === XRetOp.Machine && io.currentPrivilege === PrivilegeMode.Machine.U
  private val supervisorReturnLegal =
    decoded.system.xret === XRetOp.Supervisor && isa.hasS.B &&
      io.currentPrivilege >= PrivilegeMode.Supervisor.U
  private val xretLegal = machineReturnLegal || supervisorReturnLegal

  private val implementedSystem =
    kind === SystemOperationKind.Csr ||
      kind === SystemOperationKind.Ecall ||
      kind === SystemOperationKind.Ebreak ||
      kind === SystemOperationKind.Xret ||
      kind === SystemOperationKind.Fence ||
      (kind === SystemOperationKind.FenceI && isa.hasZifencei.B) ||
      (kind === SystemOperationKind.SfenceVma && allowSfenceVma.B && isa.hasPagedVirtualMemory.B) ||
      (kind === SystemOperationKind.Wfi && allowWfi.B)

  when(decoded.exception.valid) {
    io.result.exception := decoded.exception
  }.elsewhen(!implementedSystem) {
    markIllegalInstruction()
  }.otherwise {
    switch(kind) {
      is(SystemOperationKind.Csr) {
        when(csrLegal && decoded.system.csrOp =/= CsrOp.None) {
          io.result.hasValue := io.producesValue
          io.result.value := io.csrReadData
          io.result.privileged.csrWriteValid := csrWriteIntent
          io.result.privileged.csrAddress := decoded.system.csrAddress
          io.result.privileged.csrWriteData := csrWriteData
        }.otherwise {
          markIllegalInstruction()
        }
      }
      is(SystemOperationKind.Ecall) {
        io.result.exception.valid := true.B
        io.result.exception.cause := environmentCallCause
      }
      is(SystemOperationKind.Ebreak) {
        io.result.exception.valid := true.B
        io.result.exception.cause := MachineExceptionCode.Breakpoint.U(xlen.W)
        io.result.exception.value := decoded.pc
      }
      is(SystemOperationKind.Xret) {
        when(xretLegal) {
          io.result.privileged.trapReturn := true.B
          io.result.privileged.trapReturnSupervisor :=
            decoded.system.xret === XRetOp.Supervisor
        }.otherwise {
          markIllegalInstruction()
        }
      }
      is(SystemOperationKind.Wfi) {
        when(io.currentPrivilege === PrivilegeMode.User.U) {
          markIllegalInstruction()
        }.otherwise {
          io.result.privileged.wfi := true.B
        }
      }
      is(SystemOperationKind.Fence) { }
      is(SystemOperationKind.FenceI) { io.result.privileged.fenceI := true.B }
      is(SystemOperationKind.SfenceVma) {
        when(io.currentPrivilege < PrivilegeMode.Supervisor.U) {
          markIllegalInstruction()
        }.otherwise {
          io.result.privileged.sfenceVma := true.B
        }
      }
    }
  }
}
