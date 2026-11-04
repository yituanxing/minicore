package aethercore

import aethercore.common.{MachineExceptionCode, MemSize}
import aethercore.config.IsaConfig
import aethercore.core.RvcDecompressor
import aethercore.core.semantic._
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Instruction-level F/D contract: source/destination namespaces remain
  * architectural and never depend on the Product ROB or issue geometry.
  */
class FpSemanticDecodeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Shared RV64 F/D architectural semantics"

  private val rv64gc = IsaConfig(
    xlen = 64, extensions = Set('I', 'M', 'A', 'F', 'D', 'C'),
    privilegeModes = Set('M', 'S', 'U'))
  private val rv64imac = rv64gc.copy(extensions = Set('I', 'M', 'A', 'C'))

  private def op(opcode: Int, funct7: Int, rs2: Int,
                 rs1: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | opcode

  private def fma(opcode: Int, rs3: Int, fmt: Int, rs2: Int,
                  rs1: Int, rm: Int, rd: Int): BigInt =
    (BigInt(rs3) << 27) | (BigInt(fmt) << 25) |
      (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(rm) << 12) | (BigInt(rd) << 7) | opcode

  private def drive(d: SemanticDecode, inst: BigInt): Unit = {
    d.io.pc.poke("h80000000".U)
    d.io.inst.poke(inst.U)
    d.io.rawInst.poke(inst.U)
    d.io.instBytes.poke(4.U)
    d.io.fetchException.poke(0.U.asTypeOf(d.io.fetchException))
    d.clock.step()
  }

  it should "decode floating load/store with one memory lifetime and separate XPR/FPR namespaces" in {
    simulate(new SemanticDecode(rv64gc)) { d =>
      // FLD f3, 16(x4): the base uses x4; f3 is never an integer x3 write.
      val fld = (BigInt(16) << 20) | (BigInt(4) << 15) |
        (BigInt(3) << 12) | (BigInt(3) << 7) | 0x07
      drive(d, fld)
      d.io.decoded.exception.valid.expect(false.B)
      d.io.decoded.memory.kind.expect(MemoryOperationKind.Load)
      d.io.decoded.memory.size.expect(MemSize.DWord)
      d.io.decoded.immediate.expect(16.U)
      d.io.decoded.usesRs1.expect(true.B)
      d.io.decoded.usesRs2.expect(false.B)
      d.io.decoded.writesRd.expect(false.B)
      d.io.decoded.floating.kind.expect(FloatingOperationKind.Load)
      d.io.decoded.floating.rd.expect(3.U)
      d.io.decoded.floating.writesRd.expect(true.B)
      d.io.decoded.floating.usesRs1.expect(false.B)
      d.io.decoded.floating.format.expect(FloatingFormat.Double)

      // FSW f5,-8(x6): store data uses f5, not integer x5.
      val imm = BigInt("ff8", 16)
      val fsw = ((imm >> 5) << 25) | (BigInt(5) << 20) |
        (BigInt(6) << 15) | (BigInt(2) << 12) |
        ((imm & 31) << 7) | 0x27
      drive(d, fsw)
      d.io.decoded.exception.valid.expect(false.B)
      d.io.decoded.memory.kind.expect(MemoryOperationKind.Store)
      d.io.decoded.memory.size.expect(MemSize.Word)
      d.io.decoded.immediate.expect("hfffffffffffffff8".U)
      d.io.decoded.usesRs1.expect(true.B)
      d.io.decoded.usesRs2.expect(false.B)
      d.io.decoded.floating.kind.expect(FloatingOperationKind.Store)
      d.io.decoded.floating.usesRs2.expect(true.B)
      d.io.decoded.floating.rs2.expect(5.U)
      d.io.decoded.floating.writesRd.expect(false.B)
    }
  }

  it should "preserve fused rs3, dynamic rm and both cross-register-file conversion directions" in {
    simulate(new SemanticDecode(rv64gc)) { d =>
      drive(d, fma(0x43, rs3 = 17, fmt = 1, rs2 = 7, rs1 = 6, rm = 7, rd = 8))
      d.io.decoded.exception.valid.expect(false.B)
      d.io.decoded.floating.kind.expect(FloatingOperationKind.Fma)
      d.io.decoded.floating.format.expect(FloatingFormat.Double)
      d.io.decoded.floating.roundingMode.expect(7.U)
      d.io.decoded.floating.rs3.expect(17.U)
      d.io.decoded.floating.usesRs1.expect(true.B)
      d.io.decoded.floating.usesRs2.expect(true.B)
      d.io.decoded.floating.usesRs3.expect(true.B)
      d.io.decoded.floating.writesRd.expect(true.B)
      d.io.decoded.usesRs1.expect(false.B)
      d.io.decoded.writesRd.expect(false.B)

      // FCVT.WU.D x9,f11,rtz
      drive(d, op(0x53, funct7 = 0x61, rs2 = 1, rs1 = 11, funct3 = 1, rd = 9))
      d.io.decoded.floating.kind.expect(FloatingOperationKind.ConvertToInt)
      d.io.decoded.floating.usesRs1.expect(true.B)
      d.io.decoded.floating.writesRd.expect(false.B)
      d.io.decoded.floating.integerWidth.expect(false.B)
      d.io.decoded.floating.integerUnsigned.expect(true.B)
      d.io.decoded.writesRd.expect(true.B)
      d.io.decoded.usesRs1.expect(false.B)

      // FCVT.D.L f12,x10,rne
      drive(d, op(0x53, funct7 = 0x69, rs2 = 2, rs1 = 10, funct3 = 0, rd = 12))
      d.io.decoded.floating.kind.expect(FloatingOperationKind.ConvertFromInt)
      d.io.decoded.usesRs1.expect(true.B)
      d.io.decoded.writesRd.expect(false.B)
      d.io.decoded.floating.usesRs1.expect(false.B)
      d.io.decoded.floating.writesRd.expect(true.B)
      d.io.decoded.floating.integerWidth.expect(true.B)
      d.io.decoded.floating.integerUnsigned.expect(false.B)
    }
  }

  it should "fail closed for missing F/D, reserved fmt, rm and source encodings" in {
    val malformed = Seq(
      op(0x53, 0x2c, rs2 = 1, rs1 = 2, funct3 = 0, rd = 3), // FSQRT.S rs2!=0
      op(0x53, 0x00, rs2 = 1, rs1 = 2, funct3 = 5, rd = 3), // illegal rm
      fma(0x43, rs3 = 2, fmt = 2, rs2 = 1, rs1 = 2, rm = 0, rd = 3),
      op(0x53, 0x60, rs2 = 4, rs1 = 2, funct3 = 0, rd = 3)) // invalid FCVT rs2
    simulate(new SemanticDecode(rv64gc)) { d =>
      malformed.foreach { instruction =>
        drive(d, instruction)
        d.io.decoded.exception.valid.expect(true.B)
        d.io.decoded.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
        d.io.decoded.floating.kind.expect(FloatingOperationKind.None)
        d.io.decoded.floating.usesRs1.expect(false.B)
        d.io.decoded.floating.writesRd.expect(false.B)
      }
    }
    simulate(new SemanticDecode(rv64imac)) { d =>
      drive(d, op(0x53, 0x00, rs2 = 2, rs1 = 1, funct3 = 0, rd = 3))
      d.io.decoded.exception.valid.expect(true.B)
      d.io.decoded.floating.kind.expect(FloatingOperationKind.None)
    }
  }

  it should "decompress RV64C floating double memory only when D is enabled" in {
    val encodings = Seq(
      (BigInt("2000", 16), 0x07, 3, false, 0), // C.FLD
      (BigInt("a000", 16), 0x27, 3, true, 8),  // C.FSD
      (BigInt("2002", 16), 0x07, 3, false, 0), // C.FLDSP, f0 is legal
      (BigInt("a002", 16), 0x27, 3, true, 0))  // C.FSDSP, f0 source legal
    simulate(new RvcDecompressor(64, withDouble = true)) { d =>
      encodings.foreach { case (raw, expectedOpcode, expectedWidth, isStore, source) =>
        d.io.raw.poke(raw.U)
        d.clock.step()
        d.io.legal.expect(true.B)
        // Peek the whole output and slice the host BigInt. d.io.expanded(hi,lo)
        // would construct Chisel hardware outside a UserModule at test time.
        val expanded = d.io.expanded.peek().litValue
        (expanded & 0x7f) shouldBe BigInt(expectedOpcode)
        ((expanded >> 12) & 0x7) shouldBe BigInt(expectedWidth)
        if (isStore) ((expanded >> 20) & 0x1f) shouldBe BigInt(source)
      }
    }
    simulate(new RvcDecompressor(64)) { d =>
      encodings.foreach { case (raw, _, _, _, _) =>
        d.io.raw.poke(raw.U)
        d.clock.step()
        d.io.legal.expect(false.B)
      }
    }
  }
}
