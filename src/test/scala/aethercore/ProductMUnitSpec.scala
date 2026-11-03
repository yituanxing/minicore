package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.AluOp
import aethercore.core.product._

class ProductMUnitSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Shared-algorithm Product M execution"

  private val mask = (BigInt(1) << 64) - 1

  private def init(d: ProductMUnit): Unit = {
    d.io.request.valid.poke(false.B)
    d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
    d.io.response.ready.poke(false.B)
    d.clock.step()
  }

  private def runOperation(
    d: ProductMUnit,
    op: AluOp.Type,
    lhs: BigInt,
    rhs: BigInt,
    expected: BigInt,
    word: Boolean = false,
    iterative: Boolean = false
  ): Unit = {
    d.io.available.expect(true.B)
    d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
    d.io.request.bits.aluOp.poke(op)
    d.io.request.bits.lhs.poke((lhs & mask).U)
    d.io.request.bits.rhs.poke((rhs & mask).U)
    d.io.request.bits.wordOp.poke(word.B)
    d.io.request.bits.token.index.poke(3.U)
    d.io.request.bits.token.generation.poke(25.U)
    d.io.request.bits.destination.index.poke(37.U)
    d.io.request.bits.destination.generation.poke(11.U)
    d.io.request.bits.hasDestination.poke(true.B)
    d.io.request.valid.poke(true.B)
    d.io.request.ready.expect(true.B)
    d.clock.step()
    d.io.request.valid.poke(false.B)

    var cycles = 0
    while (!d.io.response.valid.peek().litToBoolean && cycles < 70) {
      d.clock.step()
      cycles += 1
    }
    d.io.response.valid.expect(true.B)
    if (iterative) cycles should be > 8
    d.io.response.bits.value.expect((expected & mask).U)
    d.io.response.bits.token.index.expect(3.U)
    d.io.response.bits.token.generation.expect(25.U)
    d.io.response.bits.destination.index.expect(37.U)
    d.io.response.bits.destination.generation.expect(11.U)
    d.io.response.bits.hasValue.expect(true.B)
    // Valid result and lifetime stay stable across downstream backpressure.
    for (_ <- 0 until 3) {
      d.clock.step()
      d.io.response.valid.expect(true.B)
      d.io.response.bits.value.expect((expected & mask).U)
    }
    d.io.response.ready.poke(true.B)
    d.clock.step()
    d.io.response.ready.poke(false.B)
    d.io.response.valid.expect(false.B)
    d.io.available.expect(true.B)
  }

  it should "handle all high multiply signedness variants and MULW" in {
    simulate(new ProductMUnit(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      runOperation(d, AluOp.Mul, -7, 3, -21)
      runOperation(d, AluOp.Mulh, -2, 3, -1)
      runOperation(d, AluOp.Mulhsu, -2, 3, -1)
      runOperation(d, AluOp.Mulhu, mask - 1, 3, 2)
      runOperation(d, AluOp.Mul, 0xffffffffL, 2, -2, word = true)
    }
  }

  it should "preserve signed divide/remainder zero and overflow semantics" in {
    simulate(new ProductMUnit(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      runOperation(d, AluOp.Div, -7, 3, -2, iterative = true)
      runOperation(d, AluOp.Rem, -7, 3, -1, iterative = true)
      runOperation(d, AluOp.Div, -7, 0, -1, iterative = true)
      runOperation(d, AluOp.Rem, -7, 0, -7, iterative = true)
      runOperation(d, AluOp.Div, BigInt(1) << 63, -1, BigInt(1) << 63,
        iterative = true)
      runOperation(d, AluOp.Rem, BigInt(1) << 63, -1, 0,
        iterative = true)
    }
  }

  it should "handle DIVU/REMU, DIVW and DIVUW sign extension" in {
    simulate(new ProductMUnit(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      runOperation(d, AluOp.Divu, mask, 3, BigInt("5555555555555555", 16),
        iterative = true)
      runOperation(d, AluOp.Remu, mask, 3, 0, iterative = true)
      runOperation(d, AluOp.Div, 0x80000000L, -1, BigInt("ffffffff80000000", 16),
        word = true, iterative = true)
      runOperation(d, AluOp.Divu, 0xffffffffL, 2, 0x7fffffffL,
        word = true, iterative = true)
      runOperation(d, AluOp.Rem, -7, 3, -1, word = true, iterative = true)
    }
  }
}
