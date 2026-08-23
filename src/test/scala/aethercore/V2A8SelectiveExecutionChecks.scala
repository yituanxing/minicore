package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, BranchType}
import aethercore.core.v2._

/** Prove selective availability is owned by the real execution resources. */
trait V2A8SelectiveExecutionChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeRequest(
      dut: TinySelectiveExecutionCluster,
      executionClass: ExecutionClass.Type,
      op: AluOp.Type,
      index: Int,
      lhs: BigInt,
      rhs: BigInt
  ): Unit = {
    dut.io.request.bits.robToken.index.poke(index.U)
    dut.io.request.bits.robToken.generation.poke(0.U)
    dut.io.request.bits.producerTag.id.poke(index.U)
    dut.io.request.bits.producerTag.generation.poke(0.U)
    dut.io.request.bits.valueRef.id.poke(index.U)
    dut.io.request.bits.valueRef.generation.poke(0.U)
    dut.io.request.bits.executionClass.poke(executionClass)
    dut.io.request.bits.aluOp.poke(op)
    dut.io.request.bits.wordOp.poke(false.B)
    dut.io.request.bits.controlFlowKind.poke(ControlFlowKind.None)
    dut.io.request.bits.branchType.poke(BranchType.None)
    dut.io.request.bits.lhs.poke(lhs.U)
    dut.io.request.bits.rhs.poke(rhs.U)
    dut.io.request.bits.pc.poke(0.U)
    dut.io.request.bits.instBytes.poke(4.U)
    dut.io.request.bits.immediate.poke(0.U)
  }

  behavior of "AetherCore v2 A8 selective execution availability"

  it should "report each real speculative resource independently without a shadow busy scoreboard" in {
    simulate(new TinySelectiveExecutionCluster(32, hasCompressed = false)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(false.B)

      dut.io.computeAvailability.integer.expect(true.B)
      dut.io.computeAvailability.branch.expect(true.B)
      dut.io.computeAvailability.multiply.expect(true.B)
      dut.io.computeAvailability.divide.expect(true.B)

      // Occupy the iterative divider. Other speculative resources remain available.
      pokeRequest(dut, ExecutionClass.MulDiv, AluOp.Divu, index = 3, lhs = 100, rhs = 7)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.computeAvailability.divide.expect(false.B)
      dut.io.computeAvailability.integer.expect(true.B)
      dut.io.computeAvailability.branch.expect(true.B)
      dut.io.computeAvailability.multiply.expect(true.B)

      // Let DIV finish while holding its completion. Before the edge that moves
      // the result out of the divider, the real iterative resource is still
      // unavailable. Once the completion drains, availability returns normally.
      var cycles = 0
      while (!dut.io.response.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(3.U)
      dut.io.computeAvailability.divide.expect(false.B)

      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.response.ready.poke(false.B)
      dut.io.computeAvailability.divide.expect(true.B)

      // P8.3 gives each one-cycle compute FU an elastic completion-egress slot.
      // A Branch result held at the shared drain therefore does not make the
      // Branch leaf itself unavailable: it can evacuate the old result into its
      // empty egress slot while accepting a new lifetime. This remains the real
      // resource request.ready signal, not a scheduler-owned prediction.
      pokeRequest(dut, ExecutionClass.Branch, AluOp.Add, index = 2, lhs = 1, rhs = 1)
      dut.io.request.bits.controlFlowKind.poke(ControlFlowKind.Conditional)
      dut.io.request.bits.branchType.poke(BranchType.Eq)
      dut.io.request.bits.pc.poke(0x1000.U)
      dut.io.request.bits.immediate.poke(8.U)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(2.U)
      dut.io.computeAvailability.branch.expect(true.B)
      dut.io.computeAvailability.integer.expect(true.B)
      dut.io.computeAvailability.multiply.expect(true.B)
      dut.io.computeAvailability.divide.expect(true.B)

      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.response.ready.poke(false.B)
      dut.io.computeAvailability.branch.expect(true.B)

      // Integer has the same elastic handoff contract. Finite backpressure once
      // both the leaf register and its egress slot are occupied is proved by the
      // dedicated P8.3 completion-overlap spec; this inherited A8 check only
      // asserts that availability continues to mirror the real leaf interface.
      pokeRequest(dut, ExecutionClass.Integer, AluOp.Add, index = 1, lhs = 4, rhs = 5)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(1.U)
      dut.io.computeAvailability.integer.expect(true.B)
      dut.io.computeAvailability.branch.expect(true.B)
      dut.io.computeAvailability.multiply.expect(true.B)
      dut.io.computeAvailability.divide.expect(true.B)
    }
  }
}
