package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.Rv32CDecompressor

class Rv32CDecompressorSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Rv32CDecompressor"

  private final case class Expansion(name: String, raw: Int, expanded: BigInt)

  private val integerCases = Seq(
    Expansion("C.ADDI4SPN", 0x1264, BigInt("12c10493", 16)),
    Expansion("C.LW", 0x45e8, BigInt("04c5a503", 16)),
    Expansion("C.SW", 0xcaf0, BigInt("04c6aa23", 16)),
    Expansion("C.ADDI", 0x12e5, BigInt("ff928293", 16)),
    Expansion("C.NOP", 0x0001, BigInt("00000013", 16)),
    Expansion("C.JAL", 0x37d9, BigInt("fc7ff0ef", 16)),
    Expansion("C.LI", 0x5375, BigInt("ffd00313", 16)),
    Expansion("C.ADDI16SP", 0x715d, BigInt("fb010113", 16)),
    Expansion("C.LUI", 0x73f5, BigInt("ffffd3b7", 16)),
    Expansion("C.SRLI", 0x809d, BigInt("0074d493", 16)),
    Expansion("C.SRAI", 0x8525, BigInt("40955513", 16)),
    Expansion("C.ANDI", 0x99ed, BigInt("ffb5f593", 16)),
    Expansion("C.SUB", 0x8e15, BigInt("40d60633", 16)),
    Expansion("C.XOR", 0x8e35, BigInt("00d64633", 16)),
    Expansion("C.OR", 0x8e55, BigInt("00d66633", 16)),
    Expansion("C.AND", 0x8e75, BigInt("00d67633", 16)),
    Expansion("C.J", 0xa02d, BigInt("02a0006f", 16)),
    Expansion("C.BEQZ", 0xdf71, BigInt("fc070ee3", 16)),
    Expansion("C.BNEZ", 0xef9d, BigInt("02079f63", 16)),
    Expansion("C.SLLI", 0x028e, BigInt("00329293", 16)),
    Expansion("C.LWSP", 0x5336, BigInt("06c12303", 16)),
    Expansion("C.JR", 0x8382, BigInt("00038067", 16)),
    Expansion("C.MV", 0x8426, BigInt("00900433", 16)),
    Expansion("C.EBREAK", 0x9002, BigInt("00100073", 16)),
    Expansion("C.JALR", 0x9382, BigInt("000380e7", 16)),
    Expansion("C.ADD", 0x9426, BigInt("00940433", 16)),
    Expansion("C.SWSP", 0xdaaa, BigInt("06a12a23", 16))
  )

  it should "expand representative instructions from every supported RV32C integer family" in {
    simulate(new Rv32CDecompressor) { dut =>
      for (entry <- integerCases) {
        withClue(entry.name) {
          dut.io.raw.poke(entry.raw.U(16.W))
          dut.io.legal.expect(true.B)
          dut.io.expanded.expect(entry.expanded.U(32.W))
        }
      }
    }
  }

  it should "keep standard RVC HINT encodings legal" in {
    val hints = Seq(
      Expansion("C.ADDI hint", 0x0281, BigInt("00028293", 16)),
      Expansion("C.LUI x0 hint", 0x6005, BigInt("00001037", 16)),
      Expansion("C.MV x0 hint", 0x8026, BigInt("00900033", 16))
    )

    simulate(new Rv32CDecompressor) { dut =>
      for (entry <- hints) {
        withClue(entry.name) {
          dut.io.raw.poke(entry.raw.U(16.W))
          dut.io.legal.expect(true.B)
          dut.io.expanded.expect(entry.expanded.U(32.W))
        }
      }
    }
  }

  it should "reject reserved, custom, non-integer and non-RVC encodings" in {
    val illegal = Seq(
      0x0000, // permanently illegal all-zero instruction
      0x0004, // C.ADDI4SPN with nzuimm=0
      0x2000, // quadrant-0 floating-point encoding (F/D unavailable)
      0x6000, // RV32 floating-point load encoding (F unavailable)
      0x6101, // C.ADDI16SP with nzimm=0
      0x6001, // C.LUI with nzimm=0
      0x9005, // RV32 C.SRLI custom shamt[5]=1 space
      0x9c05, // RV64 arithmetic encoding / reserved for RV32C
      0x1002, // RV32 C.SLLI custom shamt[5]=1 space
      0x4002, // C.LWSP with rd=x0
      0x8002, // C.JR with rs1=x0
      0x2002, // quadrant-2 floating-point encoding (D unavailable)
      0x0003  // quadrant 3: instruction is wider than 16 bits
    )

    simulate(new Rv32CDecompressor) { dut =>
      for (raw <- illegal) {
        withClue(f"raw=0x$raw%04x") {
          dut.io.raw.poke(raw.U(16.W))
          dut.io.legal.expect(false.B)
          dut.io.expanded.expect("h00000013".U)
        }
      }
    }
  }
}
