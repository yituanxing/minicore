package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.AluOp
import aethercore.core.ALU

class RV64MALUSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64M ALU"

  private val Mask64 = (BigInt(1) << 64) - 1

  private def check(
      dut: ALU,
      op: AluOp.Type,
      a: BigInt,
      b: BigInt,
      wordOp: Boolean,
      expected: BigInt
  ): Unit = {
    dut.io.a.poke((a & Mask64).U(64.W))
    dut.io.b.poke((b & Mask64).U(64.W))
    dut.io.op.poke(op)
    dut.io.wordOp.poke(wordOp.B)
    dut.io.out.expect((expected & Mask64).U(64.W))
  }

  it should "implement low and high multiplication variants" in {
    simulate(new ALU) { dut =>
      check(dut, AluOp.Mul, BigInt("ffffffffffffffff", 16), 3, false,
        BigInt("fffffffffffffffd", 16))

      // The same bit pattern in b is signed -2^63 for MULH and unsigned 2^63
      // for MULHSU, so the expected high halves must differ.
      check(dut, AluOp.Mulh,
        BigInt("fffffffffffffffe", 16), BigInt("8000000000000000", 16), false, 1)
      check(dut, AluOp.Mulhsu,
        BigInt("fffffffffffffffe", 16), BigInt("8000000000000000", 16), false,
        BigInt("ffffffffffffffff", 16))
      check(dut, AluOp.Mulhu,
        BigInt("ffffffffffffffff", 16), 2, false, 1)
    }
  }

  it should "implement normal signed and unsigned division and remainder" in {
    simulate(new ALU) { dut =>
      check(dut, AluOp.Div, BigInt("ffffffffffffffec", 16), 3, false,
        BigInt("fffffffffffffffa", 16))
      check(dut, AluOp.Rem, BigInt("ffffffffffffffec", 16), 3, false,
        BigInt("fffffffffffffffe", 16))
      check(dut, AluOp.Divu, BigInt("ffffffffffffffff", 16), 2, false,
        BigInt("7fffffffffffffff", 16))
      check(dut, AluOp.Remu, BigInt("ffffffffffffffff", 16), 2, false, 1)
    }
  }

  it should "implement architectural divide-by-zero and signed-overflow results" in {
    simulate(new ALU) { dut =>
      val dividend = BigInt("8000000000000005", 16)
      val allOnes = BigInt("ffffffffffffffff", 16)
      val minSigned = BigInt("8000000000000000", 16)

      check(dut, AluOp.Div, dividend, 0, false, allOnes)
      check(dut, AluOp.Divu, dividend, 0, false, allOnes)
      check(dut, AluOp.Rem, dividend, 0, false, dividend)
      check(dut, AluOp.Remu, dividend, 0, false, dividend)

      check(dut, AluOp.Div, minSigned, allOnes, false, minSigned)
      check(dut, AluOp.Rem, minSigned, allOnes, false, 0)
    }
  }

  it should "implement and sign-extend all RV64M W-class results" in {
    simulate(new ALU) { dut =>
      val allOnes = BigInt("ffffffffffffffff", 16)
      val signedWordDividend = BigInt("00000000fffffff9", 16) // -7 as a word
      val unsignedWordDividend = BigInt("00000000ffffffff", 16)
      val zeroDivRemainder = BigInt("ffffffff80000001", 16)
      val minWord = BigInt("0000000080000000", 16)

      check(dut, AluOp.Mul, BigInt("0000000040000000", 16), 2, true,
        BigInt("ffffffff80000000", 16))
      check(dut, AluOp.Div, signedWordDividend, 2, true,
        BigInt("fffffffffffffffd", 16))
      check(dut, AluOp.Divu, unsignedWordDividend, 2, true,
        BigInt("000000007fffffff", 16))
      check(dut, AluOp.Rem, signedWordDividend, 2, true, allOnes)
      check(dut, AluOp.Remu, unsignedWordDividend, 2, true, 1)

      check(dut, AluOp.Div, minWord, 0, true, allOnes)
      check(dut, AluOp.Divu, minWord, 0, true, allOnes)
      check(dut, AluOp.Rem, minWord + 1, 0, true, zeroDivRemainder)
      check(dut, AluOp.Remu, minWord + 1, 0, true, zeroDivRemainder)

      check(dut, AluOp.Div, minWord, BigInt("00000000ffffffff", 16), true,
        BigInt("ffffffff80000000", 16))
      check(dut, AluOp.Rem, minWord, BigInt("00000000ffffffff", 16), true, 0)
    }
  }
}
