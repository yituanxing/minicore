package aethercore.core.v2

import chisel3._
import chisel3.util._

/**
  * Conservative oldest-ready memory selector for bounded pre-head Load overlap.
  *
  * Age 0 preserves the historical exact-head Memory policy: any ready,
  * exception-free Memory uOp may launch. At younger ages, only an ordinary
  * Normal Load may be selected. A younger Load may cross ordinary compute and
  * a completed ordinary Load that is itself still non-head; the latter proves
  * that the older Load completed through the already-qualified replay-safe
  * pre-head path. Head Memory, incomplete Memory, Branch, System, explicit
  * ordering/serialization and known decoded exceptions remain hard barriers.
  *
  * This module owns policy and once-only issue state only. ROB order/lifetime,
  * dependency/value state and the memory transaction itself remain owned by the
  * existing backend/LSU structures. Resource availability is explicit so the
  * selector never presents a drifting Decoupled request while the blocking LSU
  * is occupied.
  */
class TinySelectiveLoadIssue(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"selective load issue XLEN must be 32 or 64, got $xlen")

  private val Entries = TinyRobGeometry.Entries
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val window = Input(Vec(Entries, new TinySchedulingEntry(xlen)))
    val allocated = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    val block = Input(Bool())
    val available = Input(Bool())
    val request = Decoupled(new TinyMemoryRequest(xlen, IdentityBits, GenerationBits))
    // Meaningful only while request.valid. It is sampled by the LSU on fire.
    val preHead = Output(Bool())
  })

  private val issuedValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  private val issuedToken = Reg(Vec(Entries, new RobToken(IdentityBits, GenerationBits)))

  private def sameRobToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  /**
    * A younger Load may cross ordinary compute immediately. It may also cross a
    * completed ordinary Load only when that Load is itself non-head. Because a
    * non-head Load can complete only through the replay-safe speculative path,
    * this widens overlap without needing a second PMA/order scoreboard.
    *
    * Age 0 Memory intentionally remains a barrier even after completion: it may
    * be MMIO/ordered and must reach the architectural retirement boundary before
    * younger memory externalization is allowed to pass it.
    */
  private def olderAllowsSafeLoadBypass(entry: TinySchedulingEntry, age: Int): Bool = {
    val computeClass = entry.uop.executionClass === ExecutionClass.Integer ||
      entry.uop.executionClass === ExecutionClass.MulDiv
    val completedPreHeadLoad = if (age == 0) {
      false.B
    } else {
      entry.complete &&
        entry.uop.executionClass === ExecutionClass.Memory &&
        entry.uop.decoded.memory.kind === MemoryOperationKind.Load
    }

    entry.valid &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      !entry.uop.decoded.exception.valid &&
      (computeClass || completedPreHeadLoad)
  }

  private val bypassOpen = Wire(Vec(Entries, Bool()))
  bypassOpen(0) := true.B
  for (age <- 1 until Entries) {
    bypassOpen(age) := bypassOpen(age - 1) &&
      olderAllowsSafeLoadBypass(io.window(age - 1), age - 1)
  }

  private val eligible = Wire(Vec(Entries, Bool()))
  for (age <- 0 until Entries) {
    val entry = io.window(age)
    val token = entry.uop.robToken
    val alreadyIssued = issuedValid(token.index) && sameRobToken(issuedToken(token.index), token)
    val exactHeadMemory = age == 0

    val classEligible = if (exactHeadMemory) {
      entry.uop.executionClass === ExecutionClass.Memory
    } else {
      entry.uop.executionClass === ExecutionClass.Memory &&
        entry.uop.decoded.memory.kind === MemoryOperationKind.Load &&
        entry.uop.decoded.ordering === OrderingClass.Normal
    }

    eligible(age) := bypassOpen(age) &&
      entry.valid &&
      !entry.complete &&
      entry.dependenciesValid &&
      entry.operandsReady &&
      !entry.uop.decoded.exception.valid &&
      classEligible &&
      !alreadyIssued
  }

  private val candidates = Wire(Vec(Entries, new TinyMemoryRequest(xlen, IdentityBits, GenerationBits)))
  for (age <- 0 until Entries) {
    val entry = io.window(age)
    val candidate = WireDefault(0.U.asTypeOf(new TinyMemoryRequest(xlen, IdentityBits, GenerationBits)))
    candidate.robToken := entry.uop.robToken
    candidate.producerTag := entry.uop.producerTag
    candidate.valueRef := entry.uop.valueRef
    candidate.kind := entry.uop.decoded.memory.kind
    candidate.size := entry.uop.decoded.memory.size
    candidate.unsigned := entry.uop.decoded.memory.unsigned
    candidate.atomicOp := entry.uop.decoded.memory.atomicOp
    candidate.base := entry.rs1.value
    candidate.offset := entry.uop.decoded.immediate
    candidate.storeData := entry.rs2.value
    candidate.rawInst := entry.uop.decoded.rawInst
    candidates(age) := candidate
  }

  // Youngest-to-oldest connects leave age0 as the final (oldest) winner.
  private val selectedValid = WireDefault(false.B)
  private val selectedAge = WireDefault(0.U(log2Ceil(Entries).W))
  private val selectedRequest = WireDefault(0.U.asTypeOf(new TinyMemoryRequest(xlen, IdentityBits, GenerationBits)))
  for (age <- (Entries - 1) to 0 by -1) {
    when(eligible(age)) {
      selectedValid := true.B
      selectedAge := age.U
      selectedRequest := candidates(age)
    }
  }

  io.request.valid := selectedValid && io.available && !io.block
  io.request.bits := selectedRequest
  io.preHead := io.request.valid && selectedAge =/= 0.U

  when(io.allocated.valid) {
    issuedValid(io.allocated.bits.robToken.index) := false.B
  }

  when(io.request.fire) {
    val token = io.request.bits.robToken
    issuedValid(token.index) := true.B
    issuedToken(token.index) := token
  }
}
