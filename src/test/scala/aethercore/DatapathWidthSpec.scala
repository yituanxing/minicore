package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.AluOp
import aethercore.core.{ALU, RegisterFile}

class DatapathWidthSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "parameterized integer datapath components"

  it should "execute RV32 I/M arithmetic with 32-bit wraparound" in {
    simulate(new ALU(32)) { dut =>
      dut.io.wordOp.poke(false.B)

      dut.io.a.poke("hffffffff".U)
      dut.io.b.poke(1.U)
      dut.io.op.poke(AluOp.Add)
      dut.io.out.expect(0.U)

      dut.io.a.poke("h80000000".U)
      dut.io.b.poke(1.U)
      dut.io.op.poke(AluOp.Sra)
      dut.io.out.expect("hc0000000".U)

      dut.io.a.poke("hfffffffe".U)
      dut.io.b.poke(3.U)
      dut.io.op.poke(AluOp.Mulh)
      dut.io.out.expect("hffffffff".U)

      dut.io.a.poke("h80000000".U)
      dut.io.b.poke("hffffffff".U)
      dut.io.op.poke(AluOp.Div)
      dut.io.out.expect("h80000000".U)
    }
  }

  it should "store, bypass and preserve x0 at XLEN 32" in {
    simulate(new RegisterFile(32)) { dut =>
      dut.io.rs1Addr.poke(0.U)
      dut.io.rs2Addr.poke(0.U)
      dut.io.writeEnable.poke(false.B)
      dut.io.rdAddr.poke(0.U)
      dut.io.rdData.poke(0.U)
      dut.clock.step()

      dut.io.writeEnable.poke(true.B)
      dut.io.rdAddr.poke(7.U)
      dut.io.rdData.poke("h89abcdef".U)
      dut.io.rs1Addr.poke(7.U)
      dut.io.rs1Data.expect("h89abcdef".U)
      dut.clock.step()

      dut.io.writeEnable.poke(false.B)
      dut.io.rs1Data.expect("h89abcdef".U)

      dut.io.writeEnable.poke(true.B)
      dut.io.rdAddr.poke(0.U)
      dut.io.rdData.poke("hffffffff".U)
      dut.io.rs2Addr.poke(0.U)
      dut.clock.step()
      dut.io.rs2Data.expect(0.U)
    }
  }
}
