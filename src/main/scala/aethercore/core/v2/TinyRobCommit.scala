package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.CommitTrace
import aethercore.core.RegisterFile

private[v2] object TinyRobGeometry {
  val Entries: Int = 8
  val IndexBits: Int = 3

  // A generation is a bounded lifetime discriminator, not a globally unique
  // instruction number. F7's two-bit field was sufficient for oldest-only
  // behavior but aliases after only four lifetimes of the same slot. Selective
  // issue will allow killed variable-latency work to return after the slot has
  // been reused, so A8 deliberately gives each slot a much larger reuse budget.
  //
  // Producers that can retain a RobToken after squash must still have a bounded
  // terminal-response lifetime shorter than this reuse budget. Potentially
  // unbounded external memory transactions use an independent AetherMem
  // transaction lifetime instead of relying on RobToken generation alone.
  val GenerationBits: Int = 8
  val GenerationReuseBudget: Int = 1 << GenerationBits
}

/** Backend semantics before lifetime/dependency/value identities are allocated. */
class RobDispatch(val xlen: Int) extends Bundle {
  require(xlen == 32 || xlen == 64, s"ROB-dispatch XLEN must be 32 or 64, got $xlen")

  val decoded = new DecodedInstruction(xlen)
  val executionClass = ExecutionClass()
  val producesValue = Bool()
}

/** Internal retirement record between the ROB and the architectural Commit owner. */
class RobRetirement(val xlen: Int) extends Bundle {
  val uop = new BackendUop(
    xlen,
    TinyRobGeometry.IndexBits,
    TinyRobGeometry.GenerationBits
  )
  val resultValid = Bool()
  val result = UInt(xlen.W)
  val exception = new aethercore.common.TrapInfo(xlen)
  val privileged = new PendingPrivilegedEffect(xlen)
}

/**
  * Read-only ROB scheduling projection in architectural age order.
  *
  * This is deliberately not an issue queue: it owns no duplicate uOp state and
  * no readiness policy. TinyRob remains the sole order/lifetime owner; later
  * scheduling logic may only observe this projection.
  */
class TinyRobWindowEntry(val xlen: Int) extends Bundle {
  val valid = Bool()
  val complete = Bool()
  val uop = new BackendUop(
    xlen,
    TinyRobGeometry.IndexBits,
    TinyRobGeometry.GenerationBits
  )
}

private class TinyRobEntry(val xlen: Int) extends Bundle {
  val valid = Bool()
  val complete = Bool()
  val uop = new BackendUop(
    xlen,
    TinyRobGeometry.IndexBits,
    TinyRobGeometry.GenerationBits
  )
  val resultValid = Bool()
  val result = UInt(xlen.W)
  val exception = new aethercore.common.TrapInfo(xlen)
  val privileged = new PendingPrivilegedEffect(xlen)
}

/**
  * Fixed eight-entry ROB.
  *
  * F4 added validated head-only normal branch recovery. F5 extends the same
  * lifetime authority to synchronous traps and xRET: a matching head
  * completion carrying an exception or validated trap-return effect may squash
  * every younger entry, but the head itself survives until precise retirement.
  */
class TinyRob(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"tiny-ROB XLEN must be 32 or 64, got $xlen")

  private val Entries = TinyRobGeometry.Entries
  private val IndexBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IndexBits, GenerationBits))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IndexBits, GenerationBits)))
    val acceptedCompletion = Valid(new ExecutionResponse(xlen, IndexBits, GenerationBits))
    val acceptedRecovery = Valid(new ExecutionResponse(xlen, IndexBits, GenerationBits))
    val acceptedPrivilegedRecovery = Valid(new ExecutionResponse(xlen, IndexBits, GenerationBits))
    val headView = Valid(new BackendUop(xlen, IndexBits, GenerationBits))
    val window = Output(Vec(Entries, new TinyRobWindowEntry(xlen)))
    val retire = Decoupled(new RobRetirement(xlen))
    val occupancy = Output(UInt(log2Ceil(Entries + 1).W))
  })

  private val entries = RegInit(VecInit(Seq.fill(Entries)(0.U.asTypeOf(new TinyRobEntry(xlen)))))
  val slotGenerations = RegInit(VecInit(Seq.fill(Entries)(0.U(GenerationBits.W))))
  val head = RegInit(0.U(IndexBits.W))
  val tail = RegInit(0.U(IndexBits.W))
  val count = RegInit(0.U(log2Ceil(Entries + 1).W))

  io.occupancy := count

  private val retireHead = entries(head)
  io.headView.valid := retireHead.valid
  io.headView.bits := retireHead.uop

  // A8.3 exports the live ROB as an age-ordered read-only window. Dynamic
  // indexing wraps naturally in the fixed three-bit slot domain; age<count is
  // the authoritative live-range bound. No state is copied into this view.
  for (age <- 0 until Entries) {
    val slot = (head + age.U)(IndexBits - 1, 0)
    val entry = entries(slot)
    val live = age.U < count && entry.valid
    io.window(age) := 0.U.asTypeOf(new TinyRobWindowEntry(xlen))
    io.window(age).valid := live
    io.window(age).complete := live && entry.complete
    io.window(age).uop := entry.uop
  }

  io.retire.valid := retireHead.valid && retireHead.complete
  io.retire.bits := 0.U.asTypeOf(new RobRetirement(xlen))
  io.retire.bits.uop := retireHead.uop
  io.retire.bits.resultValid := retireHead.resultValid
  io.retire.bits.result := retireHead.result
  io.retire.bits.exception := retireHead.exception
  io.retire.bits.privileged := retireHead.privileged

  val retireFire = io.retire.valid && io.retire.ready

  val completionIndex = io.completion.bits.robToken.index
  private val completionEntry = entries(completionIndex)
  val completionMatches = io.completion.valid &&
    completionEntry.valid &&
    !completionEntry.complete &&
    completionEntry.uop.robToken.index === io.completion.bits.robToken.index &&
    completionEntry.uop.robToken.generation === io.completion.bits.robToken.generation &&
    completionEntry.uop.producerTag.id === io.completion.bits.producerTag.id &&
    completionEntry.uop.producerTag.generation === io.completion.bits.producerTag.generation &&
    completionEntry.uop.valueRef.id === io.completion.bits.valueRef.id &&
    completionEntry.uop.valueRef.generation === io.completion.bits.valueRef.generation

  val recoveryMatches = completionMatches &&
    completionIndex === head &&
    completionEntry.uop.executionClass === ExecutionClass.Branch &&
    completionEntry.uop.decoded.controlFlow.kind =/= ControlFlowKind.None &&
    io.completion.bits.branchValid &&
    io.completion.bits.branchTaken &&
    !completionEntry.exception.valid &&
    !io.completion.bits.exception.valid

  val systemTrapReturn = completionEntry.uop.executionClass === ExecutionClass.System &&
    io.completion.bits.privileged.trapReturn
  val privilegedRecoveryMatches = completionMatches &&
    completionIndex === head &&
    (completionEntry.exception.valid ||
      io.completion.bits.exception.valid ||
      systemTrapReturn)

  val squashYounger = recoveryMatches || privilegedRecoveryMatches

  io.acceptedCompletion.valid := completionMatches
  io.acceptedCompletion.bits := io.completion.bits
  io.acceptedRecovery.valid := recoveryMatches
  io.acceptedRecovery.bits := io.completion.bits
  io.acceptedPrivilegedRecovery.valid := privilegedRecoveryMatches
  io.acceptedPrivilegedRecovery.bits := io.completion.bits

  // Any validated head recovery wins over same-cycle speculative dispatch. The
  // surviving head is incomplete at the start of the cycle, so it cannot also
  // retire on this cycle.
  io.dispatch.ready := count =/= Entries.U && !squashYounger
  val allocFire = io.dispatch.valid && io.dispatch.ready

  io.allocated.valid := allocFire
  io.allocated.bits := 0.U.asTypeOf(new BackendUop(xlen, IndexBits, GenerationBits))
  io.allocated.bits.decoded := io.dispatch.bits.decoded
  io.allocated.bits.executionClass := io.dispatch.bits.executionClass
  io.allocated.bits.robToken.index := tail
  io.allocated.bits.robToken.generation := slotGenerations(tail)
  io.allocated.bits.producerTag.id := tail
  io.allocated.bits.producerTag.generation := slotGenerations(tail)
  io.allocated.bits.valueRef.id := tail
  io.allocated.bits.valueRef.generation := slotGenerations(tail)
  io.allocated.bits.producesValue := io.dispatch.bits.producesValue

  when(completionMatches) {
    entries(completionIndex).complete := true.B
    entries(completionIndex).resultValid := io.completion.bits.hasValue
    entries(completionIndex).result := io.completion.bits.value
    entries(completionIndex).privileged := Mux(
      completionEntry.uop.executionClass === ExecutionClass.System,
      io.completion.bits.privileged,
      0.U.asTypeOf(new PendingPrivilegedEffect(xlen))
    )
    when(!entries(completionIndex).exception.valid && io.completion.bits.exception.valid) {
      entries(completionIndex).exception := io.completion.bits.exception
    }
  }

  when(retireFire) {
    entries(head).valid := false.B
    entries(head).complete := false.B
    slotGenerations(head) := slotGenerations(head) + 1.U
    head := head + 1.U
  }

  when(allocFire) {
    entries(tail).valid := true.B
    entries(tail).complete := false.B
    entries(tail).uop := io.allocated.bits
    entries(tail).resultValid := false.B
    entries(tail).result := 0.U
    entries(tail).exception := io.dispatch.bits.decoded.exception
    entries(tail).privileged := 0.U.asTypeOf(new PendingPrivilegedEffect(xlen))
    tail := tail + 1.U
  }

  switch(Cat(allocFire, retireFire)) {
    is("b10".U) { count := count + 1.U }
    is("b01".U) { count := count - 1.U }
  }

  when(squashYounger) {
    for (index <- 0 until Entries) {
      when(index.U =/= head && entries(index).valid) {
        entries(index).valid := false.B
        entries(index).complete := false.B
        // A killed slot gets a new lifetime immediately. Any future late
        // response carrying the old generation will fail completionMatches as
        // long as the producer obeys the bounded-response lifetime contract.
        slotGenerations(index) := slotGenerations(index) + 1.U
      }
    }
    tail := head + 1.U
    count := 1.U
  }
}

/**
  * Architectural integer-register retirement owner.
  *
  * F5 privileged effects are carried alongside this record but are consumed by
  * a separate commit-time privileged adapter. Register writes remain governed
  * by the same precise exception rule used since F1.
  */
class V2Commit(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"v2-commit XLEN must be 32 or 64, got $xlen")

  val io = IO(new Bundle {
    val retire = Flipped(Decoupled(new RobRetirement(xlen)))
    val commit = Output(new CommitTrace(xlen = xlen))
    val rfWriteEnable = Output(Bool())
    val rfWriteAddress = Output(UInt(5.W))
    val rfWriteData = Output(UInt(xlen.W))
  })

  io.retire.ready := true.B

  val retiring = io.retire.valid
  val decoded = io.retire.bits.uop.decoded
  val exception = io.retire.bits.exception
  val architecturalRdWrite = retiring &&
    decoded.writesRd &&
    decoded.rd =/= 0.U &&
    io.retire.bits.resultValid &&
    !exception.valid

  io.commit := 0.U.asTypeOf(new CommitTrace(xlen = xlen))
  io.commit.valid := retiring
  io.commit.pc := decoded.pc
  io.commit.inst := decoded.inst
  io.commit.rawInst := decoded.rawInst
  io.commit.instBytes := decoded.instBytes
  io.commit.rd := decoded.rd
  io.commit.rdWrite := architecturalRdWrite
  io.commit.rdData := io.retire.bits.result
  io.commit.memValid := false.B
  io.commit.memWrite := false.B
  io.commit.memAddr := 0.U
  io.commit.memWdata := 0.U
  io.commit.memWmask := 0.U
  io.commit.exception := retiring && exception.valid
  io.commit.exceptionCause := exception.cause
  io.commit.exceptionValue := exception.value
  io.commit.interrupt := false.B
  io.commit.interruptCause := 0.U
  io.commit.interruptPc := 0.U

  io.rfWriteEnable := architecturalRdWrite
  io.rfWriteAddress := decoded.rd
  io.rfWriteData := io.retire.bits.result
}

/** Thin F1 integration harness: Tiny ROB -> Commit -> existing committed RF. */
class TinyRobCommitBackend(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"v2 F1 backend XLEN must be 32 or 64, got $xlen")

  private val IndexBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IndexBits, GenerationBits))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IndexBits, GenerationBits)))
    val commit = Output(new CommitTrace(xlen = xlen))
    val rs1Addr = Input(UInt(5.W))
    val rs2Addr = Input(UInt(5.W))
    val rs1Data = Output(UInt(xlen.W))
    val rs2Data = Output(UInt(xlen.W))
    val occupancy = Output(UInt(log2Ceil(TinyRobGeometry.Entries + 1).W))
  })

  val rob = Module(new TinyRob(xlen))
  val commitStage = Module(new V2Commit(xlen))
  val registerFile = Module(new RegisterFile(xlen))

  rob.io.dispatch.valid := io.dispatch.valid
  rob.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := rob.io.dispatch.ready

  io.allocated := rob.io.allocated

  rob.io.completion.valid := io.completion.valid
  rob.io.completion.bits := io.completion.bits

  commitStage.io.retire <> rob.io.retire
  io.commit := commitStage.io.commit
  io.occupancy := rob.io.occupancy

  registerFile.io.writeEnable := commitStage.io.rfWriteEnable
  registerFile.io.rdAddr := commitStage.io.rfWriteAddress
  registerFile.io.rdData := commitStage.io.rfWriteData
  registerFile.io.rs1Addr := io.rs1Addr
  registerFile.io.rs2Addr := io.rs2Addr
  io.rs1Data := registerFile.io.rs1Data
  io.rs2Data := registerFile.io.rs2Data
}
