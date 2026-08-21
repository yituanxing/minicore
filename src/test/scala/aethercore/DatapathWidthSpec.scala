package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.AluOp
import aethercore.core.{ALU, RegisterFile}
import aethercore.core.v2.{BackendUop, ExecutionClass}
import aethercore.memory.{AetherMemOp, AetherMemRequest}

private class V2FoundationWidthSmoke(val xlen: Int) extends Module {
  private val paddrBits = if (xlen == 32) 34 else 56

  val io = IO(new Bundle {
    val uopIn = Input(new BackendUop(xlen, identityBits = 3, generationBits = 2))
    val uopOut = Output(new BackendUop(xlen, identityBits = 3, generationBits = 2))
    val memIn = Input(new AetherMemRequest(paddrBits, dataBits = xlen, txnIdBits = 2))
    val memOut = Output(new AetherMemRequest(paddrBits, dataBits = xlen, txnIdBits = 2))
  })

  io.uopOut := io.uopIn
  io.memOut := io.memIn
}

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

  it should "elaborate the v2 semantic, identity and memory contracts at both XLENs" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new V2FoundationWidthSmoke(xlen)) { dut =>
        dut.io.uopIn.executionClass.poke(ExecutionClass.Integer)
        dut.io.uopIn.robToken.index.poke(6.U)
        dut.io.uopIn.robToken.generation.poke(1.U)
        dut.io.uopIn.decoded.pc.poke("h80000000".U)

        dut.io.uopOut.executionClass.expect(ExecutionClass.Integer)
        dut.io.uopOut.robToken.index.expect(6.U)
        dut.io.uopOut.robToken.generation.expect(1.U)
        dut.io.uopOut.decoded.pc.expect("h80000000".U)

        dut.io.memIn.txnId.poke(2.U)
        dut.io.memIn.op.poke(AetherMemOp.Read)
        dut.io.memIn.paddr.poke("h1000".U)

        dut.io.memOut.txnId.expect(2.U)
        dut.io.memOut.op.expect(AetherMemOp.Read)
        dut.io.memOut.paddr.expect("h1000".U)
      }
    }
  }
}
