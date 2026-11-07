package aethercore

import aethercore.core.product._
import aethercore.core.semantic.{ControlFlowKind, FloatingFormat, FloatingOperationKind}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The same one-wide ROB must support *two independent register namespaces*.
  * FP f0 is writable; integer x0 is not. No FPU arithmetic model is needed to
  * validate RAT/PRF ownership, tagged completion, precise retirement or undo.
  */
class ProductFpBackendSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Small Product OoO floating register rename and unified ROB"

  private val geometry = ProductCoreProfiles.smallGeometry

  private case class Ticket(index: BigInt, generation: BigInt,
                            phys: BigInt, physGeneration: BigInt,
                            destinationFile: BigInt, writes: Boolean)

  private def initialize(d: ProductSmallOoOBackend): Unit = {
    d.io.dispatch.valid.poke(false.B)
    d.io.dispatch.bits.poke(0.U.asTypeOf(d.io.dispatch.bits))
    d.io.issue.ready.poke(true.B)
    d.io.fpIssue.get.ready.poke(true.B)
    d.io.integerAvailable.poke(true.B)
    d.io.mAvailable.poke(true.B)
    d.io.fpAvailable.get.poke(true.B)
    d.io.memoryAvailable.poke(true.B)
    d.io.memoryReserve.ready.poke(true.B)
    d.io.systemHead.ready.poke(false.B)
    d.io.completion.valid.poke(false.B)
    d.io.completion.bits.poke(0.U.asTypeOf(d.io.completion.bits))
    d.io.retire.ready.poke(false.B)
    d.clock.step()
  }

  private def floatingDispatch(d: ProductSmallOoOBackend, kind: FloatingOperationKind.Type,
                               fpRd: Int, fpRs1: Int = 0, fpRs2: Int = 0,
                               fpUse1: Boolean = false, fpUse2: Boolean = false,
                               writesFp: Boolean = true, writesInt: Boolean = false,
                               intRd: Int = 0, fpRs3: Int = 0,
                               fpUse3: Boolean = false): Unit = {
    d.io.dispatch.bits.poke(0.U.asTypeOf(d.io.dispatch.bits))
    val decoded = d.io.dispatch.bits.decoded
    decoded.pc.poke("h80000000".U)
    decoded.inst.poke("h20000053".U)
    decoded.rawInst.poke("h20000053".U)
    decoded.instBytes.poke(4.U)
    decoded.rd.poke(intRd.U)
    decoded.writesRd.poke(writesInt.B)
    decoded.floating.kind.poke(kind)
    decoded.floating.format.poke(FloatingFormat.Double)
    decoded.floating.rs1.poke(fpRs1.U)
    decoded.floating.rs2.poke(fpRs2.U)
    decoded.floating.rs3.poke(fpRs3.U)
    decoded.floating.rd.poke(fpRd.U)
    decoded.floating.usesRs1.poke(fpUse1.B)
    decoded.floating.usesRs2.poke(fpUse2.B)
    decoded.floating.usesRs3.poke(fpUse3.B)
    decoded.floating.writesRd.poke(writesFp.B)
    d.io.dispatch.bits.predictedNextPc.poke("h80000004".U)
    d.io.dispatch.valid.poke(true.B)
    d.io.dispatch.ready.expect(true.B)
    d.clock.step()
    d.io.dispatch.valid.poke(false.B)
  }

  private def issueFp(d: ProductSmallOoOBackend): Ticket = {
    val port = d.io.fpIssue.get
    port.valid.expect(true.B)
    d.io.issue.valid.expect(false.B)
    val bits = port.bits
    val t = Ticket(bits.token.index.peek().litValue,
      bits.token.generation.peek().litValue,
      bits.destination.index.peek().litValue,
      bits.destination.generation.peek().litValue,
      // A simulator peek already returns a literal enum value. Calling
      // .asUInt here constructs hardware outside a Chisel Module.
      bits.destinationFile.peek().litValue,
      bits.hasDestination.peek().litToBoolean)
    d.clock.step()
    t
  }

  private def complete(d: ProductSmallOoOBackend, t: Ticket, value: BigInt,
                       fflags: Int = 0, dirty: Boolean = true): Unit = {
    val c = d.io.completion.bits
    c.poke(0.U.asTypeOf(c))
    c.token.index.poke(t.index.U)
    c.token.generation.poke(t.generation.U)
    c.destination.index.poke(t.phys.U)
    c.destination.generation.poke(t.physGeneration.U)
    c.destinationFile.poke(if (t.destinationFile == 1)
      ProductRegisterFile.Floating else ProductRegisterFile.Integer)
    c.hasValue.poke(t.writes.B)
    c.value.poke(value.U)
    c.fflags.poke(fflags.U)
    c.fpStateDirty.poke(dirty.B)
    d.io.completion.valid.poke(true.B)
    d.io.completion.ready.expect(true.B)
    d.clock.step()
    d.io.completion.valid.poke(false.B)
  }

  it should "rename writable f0 independently from x0 and wake FP RAW dependencies" in {
    simulate(new ProductSmallOoOBackend(geometry, enableFloating = true)) { d =>
      initialize(d)
      floatingDispatch(d, FloatingOperationKind.MoveFromInt, fpRd = 0)
      val producer = issueFp(d)
      producer.phys should not be BigInt(0)
      producer.destinationFile shouldBe BigInt(1)
      floatingDispatch(d, FloatingOperationKind.Sign, fpRd = 2,
        fpRs1 = 0, fpRs2 = 0, fpUse1 = true, fpUse2 = true)
      d.io.fpIssue.get.valid.expect(false.B)
      complete(d, producer, BigInt("123456789abcdef0", 16))
      d.io.fpIssue.get.valid.expect(true.B)
      d.io.fpIssue.get.bits.fpLhs.expect("h123456789abcdef0".U)
      d.io.fpIssue.get.bits.fpRhs.expect("h123456789abcdef0".U)
      val consumer = issueFp(d)
      complete(d, consumer, BigInt("123456789abcdef0", 16), fflags = 0x10)

      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.rd.expect(0.U)
      d.io.retire.bits.wroteRegister.expect(false.B)
      d.io.retire.bits.wroteFloatingRegister.expect(true.B)
      d.io.retire.bits.fpDirty.expect(true.B)
      d.io.retire.bits.fflags.expect(0.U)
      d.io.retire.ready.poke(true.B)
      d.clock.step()
      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.rd.expect(2.U)
      d.io.retire.bits.wroteFloatingRegister.expect(true.B)
      d.io.retire.bits.fflags.expect(0x10.U)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }

  it should "route FP comparison results to integer PRF without corrupting FP RAT" in {
    simulate(new ProductSmallOoOBackend(geometry, enableFloating = true)) { d =>
      initialize(d)
      floatingDispatch(d, FloatingOperationKind.Compare, fpRd = 0,
        fpRs1 = 1, fpRs2 = 2, fpUse1 = true, fpUse2 = true,
        writesFp = false, writesInt = true, intRd = 5)
      val t = issueFp(d)
      t.destinationFile shouldBe BigInt(0)
      complete(d, t, 1, fflags = 0x10)
      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.rd.expect(5.U)
      d.io.retire.bits.wroteRegister.expect(true.B)
      d.io.retire.bits.wroteFloatingRegister.expect(false.B)
      d.io.retire.bits.fflags.expect(0x10.U)
      d.io.retire.bits.fpDirty.expect(true.B)
      d.io.retire.ready.poke(true.B)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }

  it should "keep the third FMA operand dependent on its own FP physical mapping" in {
    simulate(new ProductSmallOoOBackend(geometry, enableFloating = true)) { d =>
      initialize(d)
      floatingDispatch(d, FloatingOperationKind.MoveFromInt, fpRd = 3)
      val producer = issueFp(d)
      floatingDispatch(d, FloatingOperationKind.Fma, fpRd = 4,
        fpRs1 = 1, fpRs2 = 2, fpRs3 = 3,
        fpUse1 = true, fpUse2 = true, fpUse3 = true)
      d.io.fpIssue.get.valid.expect(false.B)
      complete(d, producer, BigInt("fedcba9876543210", 16))
      val fp = d.io.fpIssue.get
      fp.valid.expect(true.B)
      fp.bits.fpLhs.expect(0.U)
      fp.bits.fpRhs.expect(0.U)
      fp.bits.fpRs3.expect("hfedcba9876543210".U)
      val fma = issueFp(d)
      // This test injects a synthetic arithmetic completion: it validates
      // 3-source rename/ordering independently of the FP unit implementation.
      complete(d, fma, BigInt("123456789abcdef0", 16))
      d.io.retire.ready.poke(true.B)
      d.clock.step(2)
      d.io.occupancy.expect(0.U)
    }
  }

  it should "drop a foreign-register-file completion for an otherwise live FP ROB token" in {
    simulate(new ProductSmallOoOBackend(geometry, enableFloating = true)) { d =>
      initialize(d)
      floatingDispatch(d, FloatingOperationKind.Sign, fpRd = 6,
        fpRs1 = 0, fpUse1 = true)
      val producer = issueFp(d)
      val wrongFile = producer.copy(destinationFile = BigInt(0))
      complete(d, wrongFile, BigInt("deadbeef", 16))
      d.io.retire.valid.expect(false.B)
      complete(d, producer, BigInt("feedface", 16))
      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.destinationFile.expect(ProductRegisterFile.Floating)
      d.io.retire.bits.value.expect("hfeedface".U)
      d.io.retire.ready.poke(true.B)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }

  it should "undo a younger FP destination after an older branch redirect" in {
    simulate(new ProductSmallOoOBackend(geometry, enableFloating = true)) { d =>
      initialize(d)
      floatingDispatch(d, FloatingOperationKind.MoveFromInt, fpRd = 0)
      val committed = issueFp(d)
      complete(d, committed, BigInt("1111222233334444", 16))
      d.io.retire.ready.poke(true.B)
      d.clock.step()
      d.io.retire.ready.poke(false.B)

      d.io.dispatch.bits.poke(0.U.asTypeOf(d.io.dispatch.bits))
      d.io.dispatch.bits.decoded.pc.poke("h80000004".U)
      d.io.dispatch.bits.decoded.inst.poke("h00000463".U)
      d.io.dispatch.bits.decoded.rawInst.poke("h00000463".U)
      d.io.dispatch.bits.decoded.instBytes.poke(4.U)
      d.io.dispatch.bits.decoded.controlFlow.kind.poke(ControlFlowKind.Conditional)
      d.io.dispatch.bits.predictedNextPc.poke("h80000008".U)
      d.io.dispatch.valid.poke(true.B)
      d.io.dispatch.ready.expect(true.B)
      d.clock.step()
      d.io.dispatch.valid.poke(false.B)
      d.io.issue.valid.expect(true.B)
      val branchToken = d.io.issue.bits.token.index.peek().litValue
      val branchGeneration = d.io.issue.bits.token.generation.peek().litValue
      d.clock.step()

      floatingDispatch(d, FloatingOperationKind.MoveFromInt, fpRd = 0)
      val younger = issueFp(d)
      complete(d, younger, BigInt("deadbeefdeadbeef", 16))

      val c = d.io.completion.bits
      c.poke(0.U.asTypeOf(c))
      c.token.index.poke(branchToken.U)
      c.token.generation.poke(branchGeneration.U)
      c.branchValid.poke(true.B)
      c.nextPc.poke("h80000100".U)
      d.io.completion.valid.poke(true.B)
      d.io.redirect.valid.expect(true.B)
      d.clock.step()
      d.io.completion.valid.poke(false.B)
      d.io.recovering.expect(true.B)
      for (_ <- 0 until 12 if d.io.recovering.peek().litToBoolean) d.clock.step()
      d.io.recovering.expect(false.B)

      // The younger FP write completed but lost its precise ROB lifetime.
      // Its speculative RAT rename is gone; the older committed f0 survives.
      d.io.retire.ready.poke(true.B)
      d.io.retire.valid.expect(true.B)
      d.clock.step()
      d.io.retire.ready.poke(false.B)
      floatingDispatch(d, FloatingOperationKind.Sign, fpRd = 1,
        fpRs1 = 0, fpRs2 = 0, fpUse1 = true, fpUse2 = true)
      d.io.fpIssue.get.valid.expect(true.B)
      d.io.fpIssue.get.bits.fpLhs.expect("h1111222233334444".U)
      d.io.fpIssue.get.bits.fpRhs.expect("h1111222233334444".U)
    }
  }
}
