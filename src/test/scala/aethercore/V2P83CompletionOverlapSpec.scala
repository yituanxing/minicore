package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, BranchType}
import aethercore.core.v2._

/** P8.3 focused proof for elastic compute-completion egress. */
class V2P83CompletionOverlapSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private def pokeInteger(
      dut: TinySelectiveExecutionCluster,
      index: Int,
      generation: Int,
      lhs: BigInt,
      rhs: BigInt
  ): Unit = {
    dut.io.request.bits.robToken.index.poke(index.U)
    dut.io.request.bits.robToken.generation.poke(generation.U)
    dut.io.request.bits.producerTag.id.poke(index.U)
    dut.io.request.bits.producerTag.generation.poke(generation.U)
    dut.io.request.bits.valueRef.id.poke(index.U)
    dut.io.request.bits.valueRef.generation.poke(generation.U)
    dut.io.request.bits.executionClass.poke(ExecutionClass.Integer)
    dut.io.request.bits.aluOp.poke(AluOp.Add)
    dut.io.request.bits.wordOp.poke(false.B)
    dut.io.request.bits.controlFlowKind.poke(ControlFlowKind.None)
    dut.io.request.bits.branchType.poke(BranchType.None)
    dut.io.request.bits.lhs.poke(lhs.U)
    dut.io.request.bits.rhs.poke(rhs.U)
    dut.io.request.bits.pc.poke(0.U)
    dut.io.request.bits.instBytes.poke(4.U)
    dut.io.request.bits.immediate.poke(0.U)
  }

  behavior of "AetherCore v2 P8.3 completion overlap"

  it should "preserve the uncontended one-cycle Integer completion latency" in {
    simulate(new TinySelectiveExecutionCluster(32, hasCompressed = false)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)

      pokeInteger(dut, index = 0, generation = 1, lhs = 10, rhs = 1)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      // The elastic egress slot must flow through when empty. P8.3 may absorb
      // collisions, but it must not add a storage cycle to the normal path.
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(0.U)
      dut.io.response.bits.robToken.generation.expect(1.U)
      dut.io.response.bits.value.expect(11.U)
      dut.clock.step()
      dut.io.response.valid.expect(false.B)
    }
  }

  it should "release a one-cycle FU into its egress slot while the shared drain is blocked" in {
    simulate(new TinySelectiveExecutionCluster(32, hasCompressed = false)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(false.B)

      // First result enters the Integer leaf response register.
      pokeInteger(dut, index = 0, generation = 1, lhs = 10, rhs = 1)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      // Even though the shared response sink is blocked, the empty egress slot
      // can capture generation 1 on this edge. The leaf therefore accepts the
      // next lifetime instead of staying pinned behind the global drain.
      pokeInteger(dut, index = 0, generation = 2, lhs = 20, rhs = 2)
      dut.io.computeAvailability.integer.expect(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      // The egress slot now owns generation 1 and the leaf owns generation 2.
      // With both slots occupied, finite backpressure must return normally and
      // the oldest buffered completion must remain bit-stable.
      pokeInteger(dut, index = 0, generation = 3, lhs = 30, rhs = 3)
      dut.io.computeAvailability.integer.expect(false.B)
      dut.io.request.ready.expect(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.generation.expect(1.U)
      dut.io.response.bits.value.expect(11.U)
      dut.clock.step()
      dut.io.response.bits.robToken.generation.expect(1.U)
      dut.io.response.bits.value.expect(11.U)

      // Opening the drain must simultaneously retire generation 1 from the
      // egress slot, move generation 2 into that slot, and accept generation 3
      // into the now-released Integer leaf. This is the P8.3 overlap property.
      dut.io.response.ready.poke(true.B)
      dut.io.computeAvailability.integer.expect(true.B)
      dut.io.request.ready.expect(true.B)
      dut.io.response.bits.robToken.generation.expect(1.U)
      dut.io.response.bits.value.expect(11.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.generation.expect(2.U)
      dut.io.response.bits.value.expect(22.U)
      dut.clock.step()

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.generation.expect(3.U)
      dut.io.response.bits.value.expect(33.U)
      dut.clock.step()
      dut.io.response.valid.expect(false.B)
    }
  }
}
