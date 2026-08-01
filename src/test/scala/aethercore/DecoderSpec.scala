package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common._
import aethercore.core.Decoder

class DecoderSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Decoder"

  it should "decode representative RV64I instructions" in {
    simulate(new Decoder) { dut =>
      dut.io.inst.poke("h002081b3".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.regWrite.expect(true.B)
      dut.io.ctrl.aluOp.expect(AluOp.Add)
      dut.io.rs1.expect(1.U)
      dut.io.rs2.expect(2.U)
      dut.io.rd.expect(3.U)

      dut.io.inst.poke("h00628023".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.memWrite.expect(true.B)
      dut.io.ctrl.memSize.expect(MemSize.Byte)

      dut.io.inst.poke("h00100073".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.trap.expect(true.B)
    }
  }
}
