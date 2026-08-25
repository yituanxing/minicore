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
    dut.io.decoded.inst.poke(0.U)
    dut.io.decoded.rawInst.poke(0.U)
    dut.io.decoded.aluOp.poke(AluOp.Add)
    dut.io.decoded.system.kind.poke(SystemOperationKind.None)
    dut.io.decoded.memory.kind.poke(MemoryOperationKind.None)
    dut.io.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    dut.io.decoded.writesRd.poke(false.B)
    dut.io.decoded.rd.poke(0.U)
    dut.io.decoded.exception.valid.poke(false.B)
  }

  it should "classify only decoded architectural semantics with stable priority" in {
    simulate(new TinyBackendClassifier(64)) { dut =>
      neutral(dut)
      dut.clock.step()
      dut.io.dispatch.executionClass.expect(ExecutionClass.Integer)

      // Raw/canonical instruction evidence is deliberately irrelevant to
      // backend classification once architectural decode has completed.
      dut.io.decoded.inst.poke("hffffffff".U)
      dut.io.decoded.rawInst.poke("hdeadbeef".U)
      dut.clock.step()
      dut.io.dispatch.executionClass.expect(ExecutionClass.Integer)

      dut.io.decoded.aluOp.poke(AluOp.Mul)
      dut.clock.step()
      dut.io.dispatch.executionClass.expect(ExecutionClass.MulDiv)

      dut.io.decoded.controlFlow.kind.poke(ControlFlowKind.Conditional)
      dut.clock.step()
      dut.io.dispatch.executionClass.expect(ExecutionClass.Branch)

      dut.io.decoded.memory.kind.poke(MemoryOperationKind.Load)
      dut.clock.step()
      dut.io.dispatch.executionClass.expect(ExecutionClass.Memory)

      dut.io.decoded.system.kind.poke(SystemOperationKind.Csr)
      dut.clock.step()
      dut.io.dispatch.executionClass.expect(ExecutionClass.System)
    }
  }

  it should "derive value production from semantic destination facts only" in {
    simulate(new TinyBackendClassifier(64)) { dut =>
      neutral(dut)
      dut.io.decoded.writesRd.poke(true.B)
      dut.io.decoded.rd.poke(5.U)
      dut.clock.step()
      dut.io.dispatch.producesValue.expect(true.B)

      dut.io.decoded.rd.poke(0.U)
      dut.clock.step()
      dut.io.dispatch.producesValue.expect(false.B)

      dut.io.decoded.rd.poke(5.U)
      dut.io.decoded.exception.valid.poke(true.B)
      dut.clock.step()
      dut.io.dispatch.producesValue.expect(false.B)
    }
  }
}
