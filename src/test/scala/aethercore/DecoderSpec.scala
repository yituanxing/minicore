package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common._
import aethercore.config.{CoreProfiles, IsaConfig}
import aethercore.core.Decoder

class DecoderSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Decoder"

  private def csr(csr: Int, source: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(csr & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  private def amo(
      funct5: Int,
      rs2: Int,
      rs1: Int = 1,
      rd: Int = 3,
      aq: Boolean = false,
      rl: Boolean = false,
      funct3: Int = 2
  ): BigInt =
    (BigInt(funct5 & 0x1f) << 27) |
      (if (aq) BigInt(1) << 26 else BigInt(0)) |
      (if (rl) BigInt(1) << 25 else BigInt(0)) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x2f)

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

      // SLLI with shamt[5] set is legal in RV64 but not RV32.
      dut.io.inst.poke("h02001013".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.aluOp.expect(AluOp.Sll)

      dut.io.inst.poke("h00100073".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.trap.expect(true.B)
    }
  }

  it should "decode all register and immediate Zicsr forms plus WFI and MRET" in {
    simulate(new Decoder(CoreProfiles.rv32imSoftware.isa)) { dut =>
      val cases = Seq(
        (csr(0x340, 2, 1, 3), CsrOp.Write, false, true),
        (csr(0x340, 2, 2, 3), CsrOp.Set, false, true),
        (csr(0x340, 2, 3, 3), CsrOp.Clear, false, true),
        (csr(0x340, 2, 5, 3), CsrOp.Write, true, false),
        (csr(0x340, 2, 6, 3), CsrOp.Set, true, false),
        (csr(0x340, 2, 7, 3), CsrOp.Clear, true, false)
      )

      for ((instruction, operation, immediate, usesRs1) <- cases) {
        dut.io.inst.poke(instruction.U)
        dut.io.ctrl.illegal.expect(false.B)
        dut.io.ctrl.regWrite.expect(true.B)
        dut.io.ctrl.wbSel.expect(WbSel.Csr)
        dut.io.ctrl.csrOp.expect(operation)
        dut.io.ctrl.csrUseImm.expect(immediate.B)
        dut.io.ctrl.usesRs1.expect(usesRs1.B)
      }

      dut.io.inst.poke("h10500073".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.wfi.expect(true.B)
      dut.io.ctrl.xret.expect(XRetOp.None)
      dut.io.ctrl.trap.expect(false.B)
      dut.io.ctrl.regWrite.expect(false.B)
      dut.io.ctrl.memWrite.expect(false.B)

      dut.io.inst.poke("h30200073".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.wfi.expect(false.B)
      dut.io.ctrl.xret.expect(XRetOp.Machine)
      dut.io.ctrl.trap.expect(false.B)
      dut.io.ctrl.regWrite.expect(false.B)
      dut.io.ctrl.memWrite.expect(false.B)
    }
  }

  it should "distinguish MRET and SRET as typed return operations" in {
    simulate(new Decoder(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      dut.io.inst.poke("h30200073".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.xret.expect(XRetOp.Machine)

      dut.io.inst.poke("h10200073".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.xret.expect(XRetOp.Supervisor)
    }
  }

  it should "exclude RV64-only encodings, Zicsr, WFI and MRET from a plain RV32I profile" in {
    val rv32i = IsaConfig(
      xlen = 32,
      extensions = Set('I'),
      privilegeModes = Set('M')
    )

    simulate(new Decoder(rv32i)) { dut =>
      dut.io.inst.poke("h00002083".U) // lw x1, 0(x0)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.memSize.expect(MemSize.Word)

      dut.io.inst.poke("h00003083".U) // ld x1, 0(x0)
      dut.io.ctrl.illegal.expect(true.B)

      dut.io.inst.poke("h00006083".U) // lwu x1, 0(x0)
      dut.io.ctrl.illegal.expect(true.B)

      dut.io.inst.poke("h00103023".U) // sd x1, 0(x0)
      dut.io.ctrl.illegal.expect(true.B)

      dut.io.inst.poke("h0010809b".U) // addiw x1, x1, 1
      dut.io.ctrl.illegal.expect(true.B)

      dut.io.inst.poke("h02001013".U) // RV64 slli shamt=32
      dut.io.ctrl.illegal.expect(true.B)

      dut.io.inst.poke("h022081b3".U) // mul x3, x1, x2
      dut.io.ctrl.illegal.expect(true.B)

      dut.io.inst.poke(csr(0x340, 1, 1, 2).U)
      dut.io.ctrl.illegal.expect(true.B)

      dut.io.inst.poke("h10500073".U)
      dut.io.ctrl.illegal.expect(true.B)
      dut.io.ctrl.wfi.expect(false.B)
      dut.io.ctrl.xret.expect(XRetOp.None)

      dut.io.inst.poke("h30200073".U)
      dut.io.ctrl.illegal.expect(true.B)
      dut.io.ctrl.xret.expect(XRetOp.None)
    }
  }

  it should "gate FENCE.I on Zifencei while retaining base FENCE" in {
    val rv32i = IsaConfig(
      xlen = 32,
      extensions = Set('I'),
      privilegeModes = Set('M')
    )

    simulate(new Decoder(rv32i)) { dut =>
      dut.io.inst.poke("h0000000f".U) // fence
      dut.io.ctrl.illegal.expect(false.B)

      dut.io.inst.poke("h0000100f".U) // fence.i
      dut.io.ctrl.illegal.expect(true.B)
    }

    simulate(new Decoder(CoreProfiles.rv32imauPmpOsSoftware.isa)) { dut =>
      dut.io.inst.poke("h0000100f".U)
      dut.io.ctrl.illegal.expect(false.B)
    }
  }

  it should "enable M instructions only when configured" in {
    val rv32im = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M')
    )

    simulate(new Decoder(rv32im)) { dut =>
      dut.io.inst.poke("h022081b3".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.aluOp.expect(AluOp.Mul)
      dut.io.ctrl.wordOp.expect(false.B)
    }
  }

  it should "decode the complete RV32A word operation family only when A is configured" in {
    val rv32ima = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M', 'A'),
      privilegeModes = Set('M', 'U')
    )
    val cases = Seq(
      (0x02, 0, AtomicOp.Lr, true, false, false),
      (0x03, 2, AtomicOp.Sc, false, true, true),
      (0x01, 2, AtomicOp.Swap, true, true, true),
      (0x00, 2, AtomicOp.Add, true, true, true),
      (0x04, 2, AtomicOp.Xor, true, true, true),
      (0x0c, 2, AtomicOp.And, true, true, true),
      (0x08, 2, AtomicOp.Or, true, true, true),
      (0x10, 2, AtomicOp.Min, true, true, true),
      (0x14, 2, AtomicOp.Max, true, true, true),
      (0x18, 2, AtomicOp.Minu, true, true, true),
      (0x1c, 2, AtomicOp.Maxu, true, true, true)
    )

    simulate(new Decoder(rv32ima)) { dut =>
      for (((funct5, rs2, operation, reads, writes, usesRs2), index) <- cases.zipWithIndex) {
        dut.io.inst.poke(amo(funct5, rs2, aq = index == 3, rl = index == 3).U)
        dut.io.ctrl.illegal.expect(false.B)
        dut.io.ctrl.atomicOp.expect(operation)
        dut.io.ctrl.regWrite.expect(true.B)
        dut.io.ctrl.memRead.expect(reads.B)
        dut.io.ctrl.memWrite.expect(writes.B)
        dut.io.ctrl.memSize.expect(MemSize.Word)
        dut.io.ctrl.usesRs1.expect(true.B)
        dut.io.ctrl.usesRs2.expect(usesRs2.B)
        dut.io.ctrl.opBSel.expect(OpBSel.Imm)
        dut.io.ctrl.wbSel.expect(WbSel.Memory)
      }

      // LR.W reserves the rs1 word and therefore requires the architectural
      // rs2 field to be zero.
      dut.io.inst.poke(amo(0x02, rs2 = 2).U)
      dut.io.ctrl.illegal.expect(true.B)
      dut.io.ctrl.atomicOp.expect(AtomicOp.None)

      // RV32A word atomics use funct3=010.
      dut.io.inst.poke(amo(0x01, rs2 = 2, funct3 = 3).U)
      dut.io.ctrl.illegal.expect(true.B)
    }
  }

  it should "decode complete RV64A W and D operation families with typed widths" in {
    val rv64ima = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M', 'A'),
      privilegeModes = Set('M')
    )
    val cases = Seq(
      (0x02, 0, AtomicOp.Lr, true, false, false),
      (0x03, 2, AtomicOp.Sc, false, true, true),
      (0x01, 2, AtomicOp.Swap, true, true, true),
      (0x00, 2, AtomicOp.Add, true, true, true),
      (0x04, 2, AtomicOp.Xor, true, true, true),
      (0x0c, 2, AtomicOp.And, true, true, true),
      (0x08, 2, AtomicOp.Or, true, true, true),
      (0x10, 2, AtomicOp.Min, true, true, true),
      (0x14, 2, AtomicOp.Max, true, true, true),
      (0x18, 2, AtomicOp.Minu, true, true, true),
      (0x1c, 2, AtomicOp.Maxu, true, true, true)
    )

    simulate(new Decoder(rv64ima)) { dut =>
      for ((funct3, size) <- Seq(2 -> MemSize.Word, 3 -> MemSize.DWord)) {
        for (((funct5, rs2, operation, reads, writes, usesRs2), index) <- cases.zipWithIndex) {
          dut.io.inst.poke(amo(
            funct5,
            rs2,
            aq = index == 3,
            rl = index == 3,
            funct3 = funct3
          ).U)
          dut.io.ctrl.illegal.expect(false.B)
          dut.io.ctrl.atomicOp.expect(operation)
          dut.io.ctrl.regWrite.expect(true.B)
          dut.io.ctrl.memRead.expect(reads.B)
          dut.io.ctrl.memWrite.expect(writes.B)
          dut.io.ctrl.memSize.expect(size)
          dut.io.ctrl.usesRs1.expect(true.B)
          dut.io.ctrl.usesRs2.expect(usesRs2.B)
          dut.io.ctrl.wbSel.expect(WbSel.Memory)
        }
      }

      // LR.W and LR.D both require rs2=x0.
      for (funct3 <- Seq(2, 3)) {
        dut.io.inst.poke(amo(0x02, rs2 = 2, funct3 = funct3).U)
        dut.io.ctrl.illegal.expect(true.B)
        dut.io.ctrl.atomicOp.expect(AtomicOp.None)
      }

      // No other funct3 is part of RV64A's W/D integer atomic surface.
      dut.io.inst.poke(amo(0x01, rs2 = 2, funct3 = 1).U)
      dut.io.ctrl.illegal.expect(true.B)
      dut.io.inst.poke(amo(0x01, rs2 = 2, funct3 = 4).U)
      dut.io.ctrl.illegal.expect(true.B)
    }
  }

  it should "reject atomic encodings when A is absent" in {
    val rv32im = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M')
    )
    val rv64im = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M')
    )

    for (isa <- Seq(rv32im, rv64im)) {
      simulate(new Decoder(isa)) { dut =>
        dut.io.inst.poke(amo(0x01, rs2 = 2).U)
        dut.io.ctrl.illegal.expect(true.B)
        dut.io.ctrl.atomicOp.expect(AtomicOp.None)
      }
    }
  }
}
