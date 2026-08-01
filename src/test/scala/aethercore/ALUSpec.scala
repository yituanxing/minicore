package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.AluOp
import aethercore.core.ALU

class ALUSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "ALU"

  it should "implement RV64 and RV64W arithmetic" in {
    simulate(new ALU) { dut =>
      dut.io.a.poke(7.U)
      dut.io.b.poke(5.U)
      dut.io.op.poke(AluOp.Add)
      dut.io.wordOp.poke(false.B)
      dut.io.out.expect(12.U)

      dut.io.op.poke(AluOp.Sub)
      dut.io.out.expect(2.U)

      dut.io.a.poke("h00000000ffffffff".U)
      dut.io.b.poke(1.U)
      dut.io.op.poke(AluOp.Add)
      dut.io.wordOp.poke(true.B)
      dut.io.out.expect(0.U)

      dut.io.a.poke("h0000000080000000".U)
      dut.io.b.poke(1.U)
      dut.io.op.poke(AluOp.Sra)
      dut.io.wordOp.poke(true.B)
      dut.io.out.expect("hffffffffc0000000".U)
    }
  }
}
