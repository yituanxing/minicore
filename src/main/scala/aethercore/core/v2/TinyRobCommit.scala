package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.CommitTrace
import aethercore.core.RegisterFile

private[v2] object TinyRobGeometry {
  val Entries: Int = 4
  val IndexBits: Int = 2
  val GenerationBits: Int = 2
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
}

/**
  * F1 fixed four-entry ROB, extended by F4 with one deliberately narrow
  * head-recovery operation.
  *
  * Allocation remains the single owner of RobToken / ProducerTag / ValueRef.
  * Completion may arrive for any live slot, but only a completed head retires.
  * A normal taken-branch recovery is accepted only from a completion that has
  * already passed the complete lifetime/storage identity match and names the
  * current head. The current F3 issue policy is strict oldest-only, so every
  * other live entry is necessarily younger and may be squashed without a
  * general age comparator or branch mask.
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
    val headView = Valid(new BackendUop(xlen, IndexBits, GenerationBits))
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

  io.retire.valid := retireHead.valid && retireHead.complete
  io.retire.bits := 0.U.asTypeOf(new RobRetirement(xlen))
  io.retire.bits.uop := retireHead.uop
  io.retire.bits.resultValid := retireHead.resultValid
  io.retire.bits.result := retireHead.result
  io.retire.bits.exception := retireHead.exception

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
    io.completion.bits.branchValid &&
    io.completion.bits.branchTaken &&
    !io.completion.bits.exception.valid

  io.acceptedCompletion.valid := completionMatches
  io.acceptedCompletion.bits := io.completion.bits
  io.acceptedRecovery.valid := recoveryMatches
  io.acceptedRecovery.bits := io.completion.bits

  // Recovery wins over same-cycle speculative dispatch. The surviving branch
  // is not complete at the start of this cycle, so it cannot also retire.
  io.dispatch.ready := count =/= Entries.U && !recoveryMatches
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
    tail := tail + 1.U
  }

  switch(Cat(allocFire, retireFire)) {
    is("b10".U) { count := count + 1.U }
    is("b01".U) { count := count - 1.U }
  }

  when(recoveryMatches) {
    for (index <- 0 until Entries) {
      when(index.U =/= head && entries(index).valid) {
        entries(index).valid := false.B
        entries(index).complete := false.B
        // A killed slot gets a new lifetime immediately. Any future late
        // response carrying the old generation will fail completionMatches.
        slotGenerations(index) := slotGenerations(index) + 1.U
      }
    }
    tail := head + 1.U
    count := 1.U
  }
}

/**
  * F1 architectural retirement owner.
  *
  * It consumes only ROB-head retirement and produces the already-qualified
  * CommitTrace plus the one committed architectural integer-register write.
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
