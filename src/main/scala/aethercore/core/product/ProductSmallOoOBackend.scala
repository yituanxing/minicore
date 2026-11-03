package aethercore.core.product

import chisel3._
import chisel3.util._
import aethercore.common.{AluOp, BranchType, TrapInfo}
import aethercore.core.semantic._

/** Lifetime identity is owned by ROB and never inferred from a PRF address. */
class ProductRobId(val indexBits: Int, val generationBits: Int) extends Bundle {
  val index = UInt(indexBits.W)
  val generation = UInt(generationBits.W)
}

/** Physical value identity is independent of ROB lifetime. */
class ProductPhysicalTag(val physicalBits: Int, val generationBits: Int) extends Bundle {
  val index = UInt(physicalBits.W)
  val generation = UInt(generationBits.W)
}

class ProductDispatch(val xlen: Int) extends Bundle {
  val decoded = new DecodedInstruction(xlen)
  val predictedNextPc = UInt(xlen.W)
}

class ProductIssue(val xlen: Int, val indexBits: Int, val physicalBits: Int, val generationBits: Int)
  extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val hasDestination = Bool()
  val aluOp = AluOp()
  val wordOp = Bool()
  val controlFlow = ControlFlowKind()
  val branchType = BranchType()
  val lhs = UInt(xlen.W)
  val rhs = UInt(xlen.W)
  val immediate = UInt(xlen.W)
  val pc = UInt(xlen.W)
  val instBytes = UInt(3.W)
}

class ProductCompletion(val xlen: Int, val indexBits: Int, val physicalBits: Int, val generationBits: Int)
  extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val hasValue = Bool()
  val value = UInt(xlen.W)
  val branchValid = Bool()
  val nextPc = UInt(xlen.W)
  val exception = new TrapInfo(xlen)
}

class ProductRetirement(val xlen: Int, val indexBits: Int, val physicalBits: Int, val generationBits: Int)
  extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val rd = UInt(5.W)
  val wroteRegister = Bool()
  val value = UInt(xlen.W)
  val exception = new TrapInfo(xlen)
}

class ProductRedirect(val xlen: Int, val indexBits: Int, val generationBits: Int)
  extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val target = UInt(xlen.W)
}

private class ProductRobSlot(
  val xlen: Int, val indexBits: Int, val physicalBits: Int, val generationBits: Int
) extends Bundle {
  val valid = Bool()
  val complete = Bool()
  val tokenGeneration = UInt(generationBits.W)
  val decoded = new DecodedInstruction(xlen)
  val predictedNextPc = UInt(xlen.W)
  val writes = Bool()
  val oldPhysical = UInt(physicalBits.W)
  val newPhysical = new ProductPhysicalTag(physicalBits, generationBits)
  val result = UInt(xlen.W)
  val exception = new TrapInfo(xlen)
}

private class ProductIqSlot(
  val xlen: Int, val indexBits: Int, val physicalBits: Int, val generationBits: Int
) extends Bundle {
  val valid = Bool()
  val token = new ProductRobId(indexBits, generationBits)
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val writes = Bool()
  val src1 = UInt(physicalBits.W)
  val src2 = UInt(physicalBits.W)
  val useRs1 = Bool()
  val useRs2 = Bool()
  val lhsSource = OperandSourceKind()
  val rhsSource = OperandSourceKind()
  val aluOp = AluOp()
  val wordOp = Bool()
  val controlFlow = ControlFlowKind()
  val branchType = BranchType()
  val immediate = UInt(xlen.W)
  val pc = UInt(xlen.W)
  val instBytes = UInt(3.W)
}

/** First real SmallOoO *integer/branch back end*.
  *
  * One dispatch, one issue, one precise retirement; IQ4, ROB8, PRF48 by profile.
  * An independent execution cluster must implement ProductIssue -> ProductCompletion.
  * Memory, atomics, privileged operations and F/D are intentionally NOT accepted.
  * This module is not a complete CPU and cannot claim Linux or Debian support.
  *
  * Arbitrary-age branch recovery uses youngest-first ROB undo, one slot/cycle.
  * While recovering, allocate/issue/retire stop. FU completions are
  * backpressured until undo ends; then stale lifetimes are rejected. Every ROB and physical destination reuse changes a
  * generation; transport responses will require separate MemTxnId ownership.
  */
class ProductSmallOoOBackend(val geometry: ProductCoreGeometry) extends Module {
  require(geometry.decodeWidth == 1 && geometry.dispatchWidth == 1 &&
    geometry.issueWidth == 1 && geometry.retireWidth == 1,
    "this first hardware slice implements the Small one-wide backend only")
  private val Xlen = 64
  private val N = geometry.robEntries
  private val Q = geometry.iqEntries
  private val P = geometry.integerPhysicalRegisters
  private val I = log2Ceil(N)
  private val F = log2Ceil(P)
  private val G = 16

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new ProductDispatch(Xlen)))
    val issue = Decoupled(new ProductIssue(Xlen, I, F, G))
    val completion = Flipped(Decoupled(new ProductCompletion(Xlen, I, F, G)))
    val retire = Decoupled(new ProductRetirement(Xlen, I, F, G))
    val redirect = Valid(new ProductRedirect(Xlen, I, G))
    val trap = Valid(new ProductRetirement(Xlen, I, F, G))
    val occupancy = Output(UInt(log2Ceil(N + 1).W))
    val recovering = Output(Bool())
    val acceptedCompletion = Output(Bool())
    val outOfOrderCompletion = Output(Bool())
    val staleCompletion = Output(Bool())
    val unsupported = Output(Bool())
  })

  private val rob = RegInit(VecInit(Seq.fill(N)(
    0.U.asTypeOf(new ProductRobSlot(Xlen, I, F, G)))))
  private val iq = RegInit(VecInit(Seq.fill(Q)(
    0.U.asTypeOf(new ProductIqSlot(Xlen, I, F, G)))))
  private val generations = RegInit(VecInit(Seq.fill(N)(0.U(G.W))))
  private val physicalGenerations = RegInit(VecInit(Seq.fill(P)(0.U(G.W))))
  private val speculativeRat = RegInit(VecInit((0 until 32).map(_.U(F.W))))
  private val committedRat = RegInit(VecInit((0 until 32).map(_.U(F.W))))
  private val free = RegInit(VecInit((0 until P).map(i => (i >= 32).B)))
  private val ready = RegInit(VecInit((0 until P).map(i => (i < 32).B)))
  private val prf = Reg(Vec(P, UInt(Xlen.W))) // values are initialized by software before consumption
  private val head = RegInit(0.U(I.W))
  private val tail = RegInit(0.U(I.W))
  private val count = RegInit(0.U(log2Ceil(N + 1).W))
  private val recovering = RegInit(false.B)
  private val flushingTrap = RegInit(false.B)
  private val recoveryCut = Reg(UInt(I.W))

  io.occupancy := count
  io.recovering := recovering
  io.unsupported := false.B
  io.acceptedCompletion := false.B
  io.outOfOrderCompletion := false.B
  io.staleCompletion := false.B
  io.redirect := 0.U.asTypeOf(io.redirect)
  io.trap := 0.U.asTypeOf(io.trap)

  val dispatchDecoded = io.dispatch.bits.decoded
  val writes = dispatchDecoded.writesRd && dispatchDecoded.rd =/= 0.U
  val exceptionOnly = dispatchDecoded.exception.valid
  val supported =
    dispatchDecoded.memory.kind === MemoryOperationKind.None &&
      dispatchDecoded.system.kind === SystemOperationKind.None &&
      dispatchDecoded.ordering === OrderingClass.Normal
  io.unsupported := io.dispatch.valid && !supported && !exceptionOnly

  val freePhysicalMask = free.asUInt
  val freeIqMask = VecInit(iq.map(s => !s.valid)).asUInt
  val hasPhysical = freePhysicalMask.orR
  val hasIq = freeIqMask.orR
  val chosenPhysical = PriorityEncoder(freePhysicalMask)
  val chosenIq = PriorityEncoder(freeIqMask)
  val headEntry = rob(head)

  // Completion matches all lifetime fields. Completion is always drained;
  // stale or killed responses never mutate a new ROB slot or destination.
  val c = io.completion.bits
  val completionEntry = rob(c.token.index)
  val sameLifetime = completionEntry.valid &&
    !completionEntry.complete &&
    completionEntry.tokenGeneration === c.token.generation &&
    completionEntry.writes === c.hasValue &&
    (!completionEntry.writes ||
      (completionEntry.newPhysical.index === c.destination.index &&
        completionEntry.newPhysical.generation === c.destination.generation &&
        physicalGenerations(c.destination.index) === c.destination.generation))
  val accepted = io.completion.valid && !recovering && sameLifetime
  val branchMismatch = accepted && c.branchValid &&
    completionEntry.decoded.controlFlow.kind =/= ControlFlowKind.None &&
    !c.exception.valid &&
    c.nextPc =/= completionEntry.predictedNextPc
  io.completion.ready := !recovering
  io.acceptedCompletion := accepted
  io.staleCompletion := io.completion.fire && !sameLifetime
  io.outOfOrderCompletion := accepted && c.token.index =/= head

  io.redirect.valid := branchMismatch
  io.redirect.bits.token := c.token
  io.redirect.bits.target := c.nextPc

  // Dispatch and retirement are blocked on any redirect boundary. No two
  // divergent owners mutate the speculative RAT in one cycle.
  val canDispatch = !recovering && !branchMismatch && count < N.U &&
    (!writes || hasPhysical) && (exceptionOnly || hasIq)
  io.dispatch.ready := canDispatch && (supported || exceptionOnly) &&
    !(count =/= 0.U && headEntry.valid && headEntry.complete &&
      !recovering && !branchMismatch && io.retire.ready && headEntry.exception.valid)

  val retiringFault = headEntry.exception.valid
  io.retire.valid := count =/= 0.U && headEntry.valid &&
    headEntry.complete && !recovering && !branchMismatch
  io.retire.bits := 0.U.asTypeOf(io.retire.bits)
  io.retire.bits.token.index := head
  io.retire.bits.token.generation := headEntry.tokenGeneration
  io.retire.bits.pc := headEntry.decoded.pc
  io.retire.bits.inst := headEntry.decoded.inst
  io.retire.bits.rd := headEntry.decoded.rd
  io.retire.bits.wroteRegister := headEntry.writes && !retiringFault
  io.retire.bits.value := headEntry.result
  io.retire.bits.exception := headEntry.exception
  val trapFire = io.retire.fire && retiringFault
  io.trap.valid := trapFire
  io.trap.bits := io.retire.bits

  // IQ owns only dependency/scheduling state, never architectural order.
  val iqReady = Wire(Vec(Q, Bool()))
  for (j <- 0 until Q) {
    val e = iq(j)
    iqReady(j) := e.valid &&
      (!e.useRs1 || ready(e.src1)) &&
      (!e.useRs2 || ready(e.src2))
  }
  val readyMask = iqReady.asUInt
  val chooseIssue = PriorityEncoder(readyMask)
  val selected = iq(chooseIssue)
  io.issue.valid := readyMask.orR && !recovering && !branchMismatch && !trapFire
  io.issue.bits := 0.U.asTypeOf(io.issue.bits)
  io.issue.bits.token := selected.token
  io.issue.bits.destination := selected.destination
  io.issue.bits.hasDestination := selected.writes
  io.issue.bits.aluOp := selected.aluOp
  io.issue.bits.wordOp := selected.wordOp
  io.issue.bits.controlFlow := selected.controlFlow
  io.issue.bits.branchType := selected.branchType
  io.issue.bits.pc := selected.pc
  io.issue.bits.instBytes := selected.instBytes
  io.issue.bits.immediate := selected.immediate
  io.issue.bits.lhs := MuxLookup(selected.lhsSource.asUInt, 0.U(Xlen.W), Seq(
    OperandSourceKind.Rs1.asUInt -> Mux(selected.src1 === 0.U, 0.U, prf(selected.src1)),
    OperandSourceKind.Pc.asUInt -> selected.pc,
    OperandSourceKind.Zero.asUInt -> 0.U(Xlen.W)))
  io.issue.bits.rhs := Mux(selected.rhsSource === OperandSourceKind.Immediate,
    selected.immediate, Mux(selected.src2 === 0.U, 0.U, prf(selected.src2)))

  val normalRetire = io.retire.fire && !retiringFault

  // Age-precise recovery is serialized. A branch is preserved; younger work
  // is undone from tail backwards. A synchronous trap discards all uncommitted.
  when(recovering) {
    val finished = Mux(flushingTrap, count === 0.U, tail === (recoveryCut + 1.U)(I - 1, 0))
    when(finished) {
      recovering := false.B
      flushingTrap := false.B
    }.otherwise {
      val victimIndex = (tail - 1.U)(I - 1, 0)
      val victim = rob(victimIndex)
      assert(victim.valid, "recovery victim must belong to a live ROB lifetime")
      when(victim.writes) {
        speculativeRat(victim.decoded.rd) := victim.oldPhysical
        free(victim.newPhysical.index) := true.B
        ready(victim.newPhysical.index) := false.B
      }
      for (j <- 0 until Q) {
        when(iq(j).valid && iq(j).token.index === victimIndex &&
             iq(j).token.generation === victim.tokenGeneration) {
          iq(j).valid := false.B
        }
      }
      rob(victimIndex).valid := false.B
      generations(victimIndex) := generations(victimIndex) + 1.U
      tail := victimIndex
      count := count - 1.U
    }
  }.otherwise {
    when(branchMismatch) {
      recovering := true.B
      flushingTrap := false.B
      recoveryCut := c.token.index
    }.elsewhen(trapFire) {
      recovering := true.B
      flushingTrap := true.B
    }.otherwise {
      when(io.dispatch.fire) {
        val d = io.dispatch.bits.decoded
        val newGeneration = physicalGenerations(chosenPhysical) + 1.U
        val prior = speculativeRat(d.rd)
        rob(tail) := 0.U.asTypeOf(rob(tail))
        rob(tail).valid := true.B
        rob(tail).complete := exceptionOnly
        rob(tail).tokenGeneration := generations(tail)
        rob(tail).decoded := d
        rob(tail).predictedNextPc := io.dispatch.bits.predictedNextPc
        rob(tail).writes := writes
        rob(tail).oldPhysical := prior
        rob(tail).newPhysical.index := Mux(writes, chosenPhysical, 0.U)
        rob(tail).newPhysical.generation := Mux(writes, newGeneration, 0.U)
        rob(tail).exception := d.exception
        when(writes) {
          speculativeRat(d.rd) := chosenPhysical
          free(chosenPhysical) := false.B
          ready(chosenPhysical) := false.B
          physicalGenerations(chosenPhysical) := newGeneration
        }
        when(!exceptionOnly) {
          iq(chosenIq) := 0.U.asTypeOf(iq(chosenIq))
          iq(chosenIq).valid := true.B
          iq(chosenIq).token.index := tail
          iq(chosenIq).token.generation := generations(tail)
          iq(chosenIq).destination.index := Mux(writes, chosenPhysical, 0.U)
          iq(chosenIq).destination.generation := Mux(writes, newGeneration, 0.U)
          iq(chosenIq).writes := writes
          iq(chosenIq).src1 := speculativeRat(d.rs1)
          iq(chosenIq).src2 := speculativeRat(d.rs2)
          iq(chosenIq).useRs1 := d.usesRs1
          iq(chosenIq).useRs2 := d.usesRs2
          iq(chosenIq).lhsSource := d.lhsSource
          iq(chosenIq).rhsSource := d.rhsSource
          iq(chosenIq).aluOp := d.aluOp
          iq(chosenIq).wordOp := d.wordOp
          iq(chosenIq).controlFlow := d.controlFlow.kind
          iq(chosenIq).branchType := d.controlFlow.branchType
          iq(chosenIq).immediate := d.immediate
          iq(chosenIq).pc := d.pc
          iq(chosenIq).instBytes := d.instBytes
        }
        tail := tail + 1.U
      }
      when(io.issue.fire) {
        iq(chooseIssue).valid := false.B
      }
      when(normalRetire) {
        when(headEntry.writes) {
          assert(headEntry.oldPhysical =/= 0.U)
          committedRat(headEntry.decoded.rd) := headEntry.newPhysical.index
          free(headEntry.oldPhysical) := true.B
        }
        rob(head).valid := false.B
        generations(head) := generations(head) + 1.U
        head := head + 1.U
      }
      when(io.dispatch.fire =/= normalRetire) {
        count := Mux(io.dispatch.fire, count + 1.U, count - 1.U)
      }
    }
  }

  // A validated older completion may write while a younger ROB entry is being
  // undone. Physical register reuse cannot occur until its undo is complete.
  when(accepted) {
    rob(c.token.index).complete := true.B
    rob(c.token.index).result := c.value
    rob(c.token.index).exception := c.exception
    when(c.hasValue && !c.exception.valid) {
      prf(c.destination.index) := c.value
      ready(c.destination.index) := true.B
    }
  }

  assert(speculativeRat(0) === 0.U && committedRat(0) === 0.U &&
    !free(0), "x0 must remain mapped to immutable physical zero")
  assert(count <= N.U, "ROB cannot over-allocate")
  when(io.dispatch.fire && writes) {
    assert(chosenPhysical =/= 0.U, "physical zero cannot be renamed")
  }
  when(io.retire.fire && !retiringFault) {
    assert(headEntry.valid && headEntry.complete, "retirement must be exact head")
  }
}
