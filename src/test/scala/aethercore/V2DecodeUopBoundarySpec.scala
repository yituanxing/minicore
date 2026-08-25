package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.AluOp
import aethercore.core.v2._

class V2DecodeUopBoundarySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore v2 decode to backend-uop boundary"

  private def neutral(dut: TinyBackendClassifier): Unit = {
    dut.io.aluOp.poke(AluOp.Add)
    dut.io.systemKind.poke(SystemOperationKind.None)
    dut.io.memoryKind.poke(MemoryOperationKind.None)
    dut.io.controlFlowKind.poke(ControlFlowKind.None)
    dut.io.writesRd.poke(false.B)
    dut.io.rd.poke(0.U)
    dut.io.exceptionValid.poke(false.B)
  }

  it should "classify only architectural semantic facts with stable priority" in {
    simulate(new TinyBackendClassifier) { dut =>
      neutral(dut)
      dut.clock.step()
      dut.io.executionClass.expect(ExecutionClass.Integer)

      dut.io.aluOp.poke(AluOp.Mul)
      dut.clock.step()
      dut.io.executionClass.expect(ExecutionClass.MulDiv)

      dut.io.controlFlowKind.poke(ControlFlowKind.Conditional)
      dut.clock.step()
      dut.io.executionClass.expect(ExecutionClass.Branch)

      dut.io.memoryKind.poke(MemoryOperationKind.Load)
      dut.clock.step()
      dut.io.executionClass.expect(ExecutionClass.Memory)

      dut.io.systemKind.poke(SystemOperationKind.Csr)
      dut.clock.step()
      dut.io.executionClass.expect(ExecutionClass.System)
    }
  }

  it should "derive value production from semantic destination facts only" in {
    simulate(new TinyBackendClassifier) { dut =>
      neutral(dut)
      dut.io.writesRd.poke(true.B)
      dut.io.rd.poke(5.U)
      dut.clock.step()
      dut.io.producesValue.expect(true.B)

      dut.io.rd.poke(0.U)
      dut.clock.step()
      dut.io.producesValue.expect(false.B)

      dut.io.rd.poke(5.U)
      dut.io.exceptionValid.poke(true.B)
      dut.clock.step()
      dut.io.producesValue.expect(false.B)
    }
  }
}
