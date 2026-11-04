package aethercore

import aethercore.core.product._
import aethercore.core.semantic.{ControlFlowKind, FloatingFormat, FloatingOperationKind}
import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A directed real-backend harness: unlike an isolated FPU flush test it
  * creates real branch redirects and exercises the backend's ROB victim stream.
  * A long-latency DIV must be killed by the *matching* index and generation,
  * never by a younger global redirect which leaves the older DIV live.
  */
private class ProductFpArithmeticRecoveryHarness(geometry: ProductCoreGeometry)
    extends Module {
  private val I = log2Ceil(geometry.robEntries)
  private val P = log2Ceil(geometry.integerPhysicalRegisters)
  private val G = 16
  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new ProductDispatch(64)))
    val integerIssue = Decoupled(new ProductIssue(64, I, P, G))
    val branchCompletion = Flipped(Decoupled(new ProductCompletion(64, I, P, G)))
    val retire = Decoupled(new ProductRetirement(64, I, P, G))
    val redirect = Valid(new ProductRedirect(64, I, G))
    val recovering = Output(Bool())
    val occupancy = Output(UInt(log2Ceil(geometry.robEntries + 1).W))
    val fpIssueFired = Output(Bool())
    val lastFpToken = Output(new ProductRobId(I, G))
    val killObserved = Output(Bool())
    val killToken = Output(new ProductRobId(I, G))
    val staleCompletion = Output(Bool())
  })

  val backend = Module(new ProductSmallOoOBackend(geometry, enableFloating = true))
  val arithmetic = Module(new ProductFpArithmetic(geometry))
  val arb = Module(new Arbiter(new ProductCompletion(64, I, P, G), 2))

  backend.io.dispatch <> io.dispatch
  io.integerIssue <> backend.io.issue
  backend.io.integerAvailable := true.B
  backend.io.mAvailable := true.B
  backend.io.memoryAvailable := true.B
  backend.io.memoryReserve.ready := true.B
  backend.io.systemHead.ready := false.B
  backend.io.fpIssue.get <> arithmetic.io.request
  backend.io.fpAvailable.get := arithmetic.io.available
  arithmetic.io.frm := 0.U

  // The execution sidecar owns exactly one FPU lifetime at a time.
  // Matching BOTH token index and generation is required to squash it.
  val active = RegInit(false.B)
  val liveToken = RegInit(0.U.asTypeOf(new ProductRobId(I, G)))
  val lastToken = RegInit(0.U.asTypeOf(new ProductRobId(I, G)))
  val launch = backend.io.fpIssue.get.fire
  when(launch) {
    liveToken := backend.io.fpIssue.get.bits.token
    lastToken := backend.io.fpIssue.get.bits.token
    active := true.B
  }
  val victim = backend.io.fpKill.get
  val matchingKill = active && victim.valid &&
    victim.bits.index === liveToken.index &&
    victim.bits.generation === liveToken.generation
  arithmetic.io.flush := matchingKill
  when(matchingKill || arithmetic.io.response.fire) { active := false.B }
  io.fpIssueFired := launch
  io.lastFpToken := lastToken
  io.killObserved := matchingKill
  io.killToken := victim.bits

  arb.io.in(0) <> io.branchCompletion
  arb.io.in(1) <> arithmetic.io.response
  backend.io.completion <> arb.io.out
  io.retire <> backend.io.retire
  io.redirect := backend.io.redirect
  io.recovering := backend.io.recovering
  io.occupancy := backend.io.occupancy
  io.staleCompletion := backend.io.staleCompletion
}

class ProductFpArithmeticRecoverySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Exact FP DIV lifetime across ROB branch recovery"

  private val geometry = ProductCoreProfiles.smallGeometry
  private case class Ticket(index: BigInt, generation: BigInt)

  private def initialize(d: ProductFpArithmeticRecoveryHarness): Unit = {
    d.io.dispatch.valid.poke(false.B)
    d.io.dispatch.bits.poke(0.U.asTypeOf(d.io.dispatch.bits))
    d.io.integerIssue.ready.poke(true.B)
    d.io.branchCompletion.valid.poke(false.B)
    d.io.branchCompletion.bits.poke(0.U.asTypeOf(d.io.branchCompletion.bits))
    d.io.retire.ready.poke(false.B)
    d.clock.step()
  }

  private def dispatchFp(d: ProductFpArithmeticRecoveryHarness,
                         kind: FloatingOperationKind.Type = FloatingOperationKind.Div): Ticket = {
    val q = d.io.dispatch.bits
    q.poke(0.U.asTypeOf(q))
    q.decoded.pc.poke("h80000000".U)
    q.decoded.inst.poke("h1a105053".U)
    q.decoded.rawInst.poke("h1a105053".U)
    q.decoded.instBytes.poke(4.U)
    q.decoded.floating.kind.poke(kind)
    q.decoded.floating.format.poke(FloatingFormat.Double)
    q.decoded.floating.rs1.poke(1.U)
    q.decoded.floating.rs2.poke(2.U)
    q.decoded.floating.rd.poke(3.U)
    q.decoded.floating.usesRs1.poke(true.B)
    q.decoded.floating.usesRs2.poke(true.B)
    q.decoded.floating.writesRd.poke(true.B)
    q.predictedNextPc.poke("h80000004".U)
    d.io.dispatch.valid.poke(true.B)
    d.io.dispatch.ready.expect(true.B)
    d.clock.step()
    d.io.dispatch.valid.poke(false.B)
    var elapsed = 0
    while (!d.io.fpIssueFired.peek().litToBoolean && elapsed < 20) {
      d.clock.step()
      elapsed += 1
    }
    d.io.fpIssueFired.expect(true.B)
    d.clock.step()
    Ticket(d.io.lastFpToken.index.peek().litValue,
      d.io.lastFpToken.generation.peek().litValue)
  }

  private def dispatchBranch(d: ProductFpArithmeticRecoveryHarness): Ticket = {
    val q = d.io.dispatch.bits
    q.poke(0.U.asTypeOf(q))
    q.decoded.pc.poke("h80000004".U)
    q.decoded.inst.poke("h00000463".U)
    q.decoded.rawInst.poke("h00000463".U)
    q.decoded.instBytes.poke(4.U)
    q.decoded.controlFlow.kind.poke(ControlFlowKind.Conditional)
    q.predictedNextPc.poke("h80000008".U)
    d.io.dispatch.valid.poke(true.B)
    d.io.dispatch.ready.expect(true.B)
    d.clock.step()
    d.io.dispatch.valid.poke(false.B)
    d.io.integerIssue.valid.expect(true.B)
    val token = Ticket(d.io.integerIssue.bits.token.index.peek().litValue,
      d.io.integerIssue.bits.token.generation.peek().litValue)
    d.clock.step()
    token
  }

  private def redirectBranch(d: ProductFpArithmeticRecoveryHarness, t: Ticket): Unit = {
    val c = d.io.branchCompletion.bits
    c.poke(0.U.asTypeOf(c))
    c.token.index.poke(t.index.U)
    c.token.generation.poke(t.generation.U)
    c.branchValid.poke(true.B)
    c.nextPc.poke("h80000100".U)
    d.io.branchCompletion.valid.poke(true.B)
    d.io.branchCompletion.ready.expect(true.B)
    d.io.redirect.valid.expect(true.B)
    d.clock.step()
    d.io.branchCompletion.valid.poke(false.B)
  }

  it should "preserve an older iterative DIV when a younger branch redirects" in {
    simulate(new ProductFpArithmeticRecoveryHarness(geometry)) { d =>
      initialize(d)
      val olderDiv = dispatchFp(d)
      val youngerBranch = dispatchBranch(d)
      olderDiv.index should not be youngerBranch.index
      redirectBranch(d, youngerBranch)
      var kills = 0
      var elapsed = 0
      while (d.io.recovering.peek().litToBoolean && elapsed < 24) {
        if (d.io.killObserved.peek().litToBoolean) kills += 1
        d.clock.step()
        elapsed += 1
      }
      kills shouldBe 0
      // The DIV can finish before or after recovery, but can retire only at
      // the real older ROB head and must preserve the original generation.
      elapsed = 0
      while (!d.io.retire.valid.peek().litToBoolean && elapsed < 400) {
        d.clock.step()
        elapsed += 1
      }
      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.token.index.expect(olderDiv.index.U)
      d.io.retire.bits.token.generation.expect(olderDiv.generation.U)
      d.io.retire.bits.wroteFloatingRegister.expect(true.B)
      d.io.retire.bits.fflags.expect(0x10.U) // FPR zero / zero: NV
      d.io.retire.ready.poke(true.B)
      d.clock.step()
      d.io.retire.bits.token.index.expect(youngerBranch.index.U)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }

  it should "kill a younger DIV on an older redirect and never retire its stale generation" in {
    simulate(new ProductFpArithmeticRecoveryHarness(geometry)) { d =>
      initialize(d)
      val olderBranch = dispatchBranch(d)
      val youngerDiv = dispatchFp(d)
      redirectBranch(d, olderBranch)
      var kills = 0
      var elapsed = 0
      while (d.io.recovering.peek().litToBoolean && elapsed < 24) {
        if (d.io.killObserved.peek().litToBoolean) {
          d.io.killToken.index.expect(youngerDiv.index.U)
          d.io.killToken.generation.expect(youngerDiv.generation.U)
          kills += 1
        }
        d.clock.step()
        elapsed += 1
      }
      kills shouldBe 1
      d.io.retire.ready.poke(true.B)
      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.token.index.expect(olderBranch.index.U)
      d.clock.step()
      d.io.retire.valid.expect(false.B)
      // The killed divider still drains internally. Its old result must not
      // complete into the new ROB lifetime, regardless of canceled latency.
      for (_ <- 0 until 200) {
        d.io.retire.valid.expect(false.B)
        d.io.staleCompletion.expect(false.B)
        d.clock.step()
      }
      d.io.occupancy.expect(0.U)
      // Recycling exactly the squashed ROB index is the decisive generation
      // check: a late canceled DIV must not overwrite this new ADD lifetime.
      d.io.retire.ready.poke(false.B)
      val reused = dispatchFp(d, FloatingOperationKind.Add)
      reused.index shouldBe youngerDiv.index
      reused.generation shouldBe (youngerDiv.generation + 1)
      var waited = 0
      while (!d.io.retire.valid.peek().litToBoolean && waited < 80) {
        d.clock.step()
        waited += 1
      }
      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.token.index.expect(reused.index.U)
      d.io.retire.bits.token.generation.expect(reused.generation.U)
      d.io.retire.bits.wroteFloatingRegister.expect(true.B)
      d.io.retire.bits.value.expect(0.U) // original architectural FPRs are zero
      d.io.retire.bits.fflags.expect(0.U)
      d.io.retire.ready.poke(true.B)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }
}
