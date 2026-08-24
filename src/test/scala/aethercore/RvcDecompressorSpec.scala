package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.RvcDecompressor

class RvcDecompressorSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "XLEN-aware RvcDecompressor"

  private final case class Expansion(name: String, raw: Int, expanded: BigInt)

  private val rv64OnlyCases = Seq(
    Expansion("C.ADDIW", 0x3575, BigInt("ffd5051b", 16)),
    Expansion("C.LD", 0x760c, BigInt("02863583", 16)),
    Expansion("C.SD", 0xf614, BigInt("02d63423", 16)),
    Expansion("C.SUBW", 0x9d0d, BigInt("40b5053b", 16)),
    Expansion("C.ADDW", 0x9d2d, BigInt("00b5053b", 16)),
    Expansion("C.SLLI shamt[5]", 0x1516, BigInt("02551513", 16)),
    Expansion("C.SRLI shamt[5]", 0x9115, BigInt("02555513", 16)),
    Expansion("C.SRAI shamt[5]", 0x9515, BigInt("42555513", 16)),
    Expansion("C.LDSP", 0x7766, BigInt("07813703", 16)),
    Expansion("C.SDSP", 0xfcbe, BigInt("06f13c23", 16))
  )

  it should "expand representative RV64C-only integer encodings into canonical 32-bit instructions" in {
    simulate(new RvcDecompressor(64)) { dut =>
      rv64OnlyCases.foreach { entry =>
        withClue(entry.name) {
          dut.io.raw.poke(entry.raw.U(16.W))
          dut.io.legal.expect(true.B)
          dut.io.expanded.expect(entry.expanded.U(32.W))
        }
      }
    }
  }

  it should "decode the shared quadrant-1 opcode according to XLEN" in {
    val raw = 0x3575

    simulate(new RvcDecompressor(32)) { dut =>
      dut.io.raw.poke(raw.U(16.W))
      dut.io.legal.expect(true.B)
      dut.io.expanded.expect("headff0ef".U(32.W)) // RV32C C.JAL
    }

    simulate(new RvcDecompressor(64)) { dut =>
      dut.io.raw.poke(raw.U(16.W))
      dut.io.legal.expect(true.B)
      dut.io.expanded.expect("hffd5051b".U(32.W)) // RV64C C.ADDIW
    }
  }

  it should "keep RV64-only load/store and word-arithmetic encodings illegal at RV32" in {
    val rv32Illegal = Seq(
      0x760c, // RV64C C.LD / RV32 floating-point alias
      0xf614, // RV64C C.SD / RV32 floating-point alias
      0x9d0d, // RV64C C.SUBW
      0x9d2d, // RV64C C.ADDW
      0x7766, // RV64C C.LDSP / RV32 floating-point alias
      0xfcbe // RV64C C.SDSP / RV32 floating-point alias
    )

    simulate(new RvcDecompressor(32)) { dut =>
      rv32Illegal.foreach { raw =>
        withClue(f"raw=0x$raw%04x") {
          dut.io.raw.poke(raw.U(16.W))
          dut.io.legal.expect(false.B)
          dut.io.expanded.expect("h00000013".U(32.W))
        }
      }
    }
  }

  it should "keep the RV64 C.ADDIW rd=x0 code point reserved" in {
    simulate(new RvcDecompressor(64)) { dut =>
      dut.io.raw.poke("h2001".U(16.W))
      dut.io.legal.expect(false.B)
      dut.io.expanded.expect("h00000013".U(32.W))
    }
  }
}
