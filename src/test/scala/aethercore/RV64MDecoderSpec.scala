package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.AluOp
import aethercore.core.Decoder

class RV64MDecoderSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64M Decoder"

  private case class DecodeCase(
      name: String,
      opcode: Int,
      funct3: Int,
      aluOp: AluOp.Type,
      wordOp: Boolean
  )

  private val cases = Seq(
    DecodeCase("MUL",    0x33, 0x0, AluOp.Mul,    false),
    DecodeCase("MULH",   0x33, 0x1, AluOp.Mulh,   false),
    DecodeCase("MULHSU", 0x33, 0x2, AluOp.Mulhsu, false),
    DecodeCase("MULHU",  0x33, 0x3, AluOp.Mulhu,  false),
    DecodeCase("DIV",    0x33, 0x4, AluOp.Div,    false),
    DecodeCase("DIVU",   0x33, 0x5, AluOp.Divu,   false),
    DecodeCase("REM",    0x33, 0x6, AluOp.Rem,    false),
    DecodeCase("REMU",   0x33, 0x7, AluOp.Remu,   false),
    DecodeCase("MULW",   0x3b, 0x0, AluOp.Mul,    true),
    DecodeCase("DIVW",   0x3b, 0x4, AluOp.Div,    true),
    DecodeCase("DIVUW",  0x3b, 0x5, AluOp.Divu,   true),
    DecodeCase("REMW",   0x3b, 0x6, AluOp.Rem,    true),
    DecodeCase("REMUW",  0x3b, 0x7, AluOp.Remu,   true)
  )

  private def encodeR(opcode: Int, funct3: Int): BigInt = {
    val funct7 = 0x01
    val rs2 = 2
    val rs1 = 1
    val rd = 3
    BigInt(
      (funct7 << 25) |
      (rs2 << 20) |
      (rs1 << 15) |
      (funct3 << 12) |
      (rd << 7) |
      opcode
    )
  }

  it should "accept all RV64M register encodings with the exact operation and width class" in {
    simulate(new Decoder) { dut =>
      for (test <- cases) {
        withClue(test.name) {
          dut.io.inst.poke(encodeR(test.opcode, test.funct3).U(32.W))
          dut.io.ctrl.aluOp.expect(test.aluOp)
          dut.io.ctrl.illegal.expect(false.B)
          dut.io.ctrl.regWrite.expect(true.B)
          dut.io.ctrl.usesRs1.expect(true.B)
          dut.io.ctrl.usesRs2.expect(true.B)
          dut.io.ctrl.wordOp.expect(test.wordOp.B)
          dut.io.ctrl.memRead.expect(false.B)
          dut.io.ctrl.memWrite.expect(false.B)
          dut.io.ctrl.jump.expect(false.B)
          dut.io.ctrl.trap.expect(false.B)
          dut.io.rs1.expect(1.U)
          dut.io.rs2.expect(2.U)
          dut.io.rd.expect(3.U)
        }
      }
    }
  }

  it should "keep reserved RV64M W-class funct3 values illegal" in {
    simulate(new Decoder) { dut =>
      for (funct3 <- Seq(1, 2, 3)) {
        dut.io.inst.poke(encodeR(0x3b, funct3).U(32.W))
        dut.io.ctrl.illegal.expect(true.B)
      }
    }
  }
}
