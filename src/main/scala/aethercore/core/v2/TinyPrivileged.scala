package aethercore.core.v2

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum
import aethercore.config.{CoreConfig, IsaConfig}
import aethercore.core.MachineCsrFile
import aethercore.core.semantic.SystemSemanticEvaluator

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
  * A8 strengthens the transport contract without moving architectural
  * ownership: a generated system completion is Decoupled, stays bit-stable
  * under backpressure, and is emitted at most once for one observed head
  * lifetime. This matters for changing values such as the time CSR and for a
  * future completion arbiter that may temporarily select another producer.
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

    val completion = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  })

  private def sameRobToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  private val decoded = io.head.bits.decoded
  private val systemKind = decoded.system.kind
  private val isSystemHead = io.head.valid && io.head.bits.executionClass === ExecutionClass.System
  private val hasPredecodedException = io.head.valid && decoded.exception.valid
  private val headRecordReady = io.headDependenciesValid
  private val operandsReady = io.headDependenciesValid && io.headOperandsReady

  // Pure architectural System/CSR semantics are shared with ProductCore;
  // only this Tiny wrapper owns the backend-specific lifetime/backpressure.
  private val evaluator = Module(new SystemSemanticEvaluator(isa, allowSfenceVma, allowWfi))
  evaluator.io.decoded := decoded
  evaluator.io.producesValue := io.head.bits.producesValue
  evaluator.io.rs1Value := io.headRs1.value
  evaluator.io.csrReadData := io.csrReadData
  evaluator.io.csrReadImplemented := io.csrReadImplemented
  evaluator.io.csrReadWritable := io.csrReadWritable
  evaluator.io.currentPrivilege := io.currentPrivilege
  io.csrReadAddr := evaluator.io.csrReadAddr

  // Fresh semantics are still combinational when the consumer is ready, so A8
  // does not add latency to the ordered F5/F6 path. If the completion cannot
  // fire, the complete response is captured below and becomes independent of
  // changing CSR/time inputs until it is accepted.
  private val freshCompletion = Wire(Valid(
    new ExecutionResponse(xlen, IdentityBits, GenerationBits)
  ))
  freshCompletion.valid := false.B
  freshCompletion.bits := 0.U.asTypeOf(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  freshCompletion.bits.robToken := io.head.bits.robToken
  freshCompletion.bits.producerTag := io.head.bits.producerTag
  freshCompletion.bits.valueRef := io.head.bits.valueRef

  private val heldValid = RegInit(false.B)
  private val heldBits = Reg(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  private val deliveredValid = RegInit(false.B)
  private val deliveredToken = Reg(new RobToken(IdentityBits, GenerationBits))
  private val alreadyDelivered = deliveredValid && io.head.valid &&
    sameRobToken(deliveredToken, io.head.bits.robToken)

  // A predecoded exception is architectural fact and must not wait for rs1.
  // Otherwise evaluate only the exact head once its sources are ready. The
  // evaluator is combinational and has no ROB-identity or state ownership.
  when((headRecordReady && hasPredecodedException) ||
       (operandsReady && isSystemHead)) {
    freshCompletion.valid := true.B
    freshCompletion.bits.hasValue := evaluator.io.result.hasValue
    freshCompletion.bits.value := evaluator.io.result.value
    freshCompletion.bits.exception := evaluator.io.result.exception
    freshCompletion.bits.privileged := evaluator.io.result.privileged
  }

  private val freshEligible = freshCompletion.valid && !alreadyDelivered && !heldValid
  io.completion.valid := heldValid || freshEligible
  io.completion.bits := Mux(heldValid, heldBits, freshCompletion.bits)

  // Once the observed head moves, an old numeric token is no longer remembered
  // as delivered forever. This mirrors the A8 once-only lifetime rule used by
  // issue ownership rather than treating a bounded token as globally unique.
  when(deliveredValid &&
       (!io.head.valid || !sameRobToken(deliveredToken, io.head.bits.robToken))) {
    deliveredValid := false.B
  }

  // Capture exactly the response that first encountered backpressure. In
  // particular, a CSR read such as time cannot change underneath valid.
  when(freshEligible && !io.completion.ready) {
    heldValid := true.B
    heldBits := freshCompletion.bits
  }

  when(io.completion.fire) {
    deliveredValid := true.B
    deliveredToken := io.completion.bits.robToken
    when(heldValid) {
      heldValid := false.B
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

  // A8 makes simultaneous pending producers legal and arbitrates them instead
  // of relying on oldest-only mutual exclusion. The ROB still receives one
  // completion per cycle and remains the final full-identity validator.
  val completions = Module(new TinyCompletionArbiter(xlen, 2))
  completions.io.in(0) <> system.io.completion
  completions.io.in(1) <> execution.io.response
  dependencyBackend.io.completion.valid := completions.io.out.valid
  dependencyBackend.io.completion.bits := completions.io.out.bits
  completions.io.out.ready := true.B

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
