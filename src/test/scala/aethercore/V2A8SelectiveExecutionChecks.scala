package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, BranchType}
import aethercore.core.v2._

/** Prove compute availability is owned by the real execution resources. */
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

  it should "report each real compute resource independently without a shadow busy scoreboard" in {
    simulate(new TinySelectiveExecutionCluster(32, hasCompressed = false)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(false.B)

      dut.io.computeAvailability.integer.expect(true.B)
      dut.io.computeAvailability.multiply.expect(true.B)
      dut.io.computeAvailability.divide.expect(true.B)

      // Occupy the iterative divider. Other compute resources remain available.
      pokeRequest(dut, ExecutionClass.MulDiv, AluOp.Divu, index = 3, lhs = 100, rhs = 7)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.computeAvailability.divide.expect(false.B)
      dut.io.computeAvailability.integer.expect(true.B)
      dut.io.computeAvailability.multiply.expect(true.B)

      // Let DIV finish while holding its completion. Its resource stays busy
      // until the real response handshake, not until a scheduler prediction.
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

      // A held one-cycle integer completion similarly affects only Integer.
      pokeRequest(dut, ExecutionClass.Integer, AluOp.Add, index = 1, lhs = 4, rhs = 5)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(1.U)
      dut.io.computeAvailability.integer.expect(false.B)
      dut.io.computeAvailability.multiply.expect(true.B)
      dut.io.computeAvailability.divide.expect(true.B)
    }
  }
}
