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

  private def atomic(
      funct5: Int,
      aq: Boolean,
      rl: Boolean,
      rs2: Int,
      rs1: Int,
      funct3: Int,
      rd: Int
  ): BigInt =
    (BigInt(funct5 & 0x1f) << 27) |
      (BigInt(if (aq) 1 else 0) << 26) |
      (BigInt(if (rl) 1 else 0) << 25) |
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
      dut.io.ctrl.mret.expect(false.B)
      dut.io.ctrl.trap.expect(false.B)
      dut.io.ctrl.regWrite.expect(false.B)
      dut.io.ctrl.memWrite.expect(false.B)

      dut.io.inst.poke("h30200073".U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.wfi.expect(false.B)
      dut.io.ctrl.mret.expect(true.B)
      dut.io.ctrl.trap.expect(false.B)
      dut.io.ctrl.regWrite.expect(false.B)
      dut.io.ctrl.memWrite.expect(false.B)
    }
  }

  it should "decode RV64 LR and SC widths plus aq and rl without claiming AMOs" in {
    val rv64a = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M', 'A'),
      privilegeModes = Set('M')
    )

    simulate(new Decoder(rv64a)) { dut =>
      dut.io.inst.poke(atomic(2, aq = true, rl = false, rs2 = 0, rs1 = 4, funct3 = 2, rd = 5).U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.atomicOp.expect(AtomicOp.LoadReserved)
      dut.io.ctrl.wbSel.expect(WbSel.Atomic)
      dut.io.ctrl.memRead.expect(true.B)
      dut.io.ctrl.memWrite.expect(false.B)
      dut.io.ctrl.memSize.expect(MemSize.Word)
      dut.io.ctrl.usesRs1.expect(true.B)
      dut.io.ctrl.usesRs2.expect(false.B)
      dut.io.ctrl.atomicAcquire.expect(true.B)
      dut.io.ctrl.atomicRelease.expect(false.B)

      dut.io.inst.poke(atomic(2, aq = false, rl = true, rs2 = 0, rs1 = 4, funct3 = 3, rd = 5).U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.atomicOp.expect(AtomicOp.LoadReserved)
      dut.io.ctrl.memSize.expect(MemSize.DWord)
      dut.io.ctrl.atomicAcquire.expect(false.B)
      dut.io.ctrl.atomicRelease.expect(true.B)

      dut.io.inst.poke(atomic(3, aq = true, rl = true, rs2 = 6, rs1 = 4, funct3 = 3, rd = 5).U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.atomicOp.expect(AtomicOp.StoreConditional)
      dut.io.ctrl.memRead.expect(false.B)
      dut.io.ctrl.memWrite.expect(true.B)
      dut.io.ctrl.memSize.expect(MemSize.DWord)
      dut.io.ctrl.usesRs1.expect(true.B)
      dut.io.ctrl.usesRs2.expect(true.B)
      dut.io.ctrl.atomicAcquire.expect(true.B)
      dut.io.ctrl.atomicRelease.expect(true.B)

      // LR requires rs2=x0.
      dut.io.inst.poke(atomic(2, aq = false, rl = false, rs2 = 1, rs1 = 4, funct3 = 3, rd = 5).U)
      dut.io.ctrl.illegal.expect(true.B)

      // AMOADD remains illegal until the read-modify-write state machine is qualified.
      dut.io.inst.poke(atomic(0, aq = false, rl = false, rs2 = 6, rs1 = 4, funct3 = 3, rd = 5).U)
      dut.io.ctrl.illegal.expect(true.B)
    }
  }

  it should "keep atomic encodings illegal when A is absent or the width is unavailable" in {
    val rv32a = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'A'),
      privilegeModes = Set('M')
    )

    simulate(new Decoder(CoreProfiles.rv64imCurrent.isa)) { dut =>
      dut.io.inst.poke(atomic(2, aq = false, rl = false, rs2 = 0, rs1 = 1, funct3 = 3, rd = 2).U)
      dut.io.ctrl.illegal.expect(true.B)
      dut.io.ctrl.atomicOp.expect(AtomicOp.None)
    }

    simulate(new Decoder(rv32a)) { dut =>
      dut.io.inst.poke(atomic(2, aq = false, rl = false, rs2 = 0, rs1 = 1, funct3 = 2, rd = 2).U)
      dut.io.ctrl.illegal.expect(false.B)
      dut.io.ctrl.memSize.expect(MemSize.Word)

      dut.io.inst.poke(atomic(2, aq = false, rl = false, rs2 = 0, rs1 = 1, funct3 = 3, rd = 2).U)
      dut.io.ctrl.illegal.expect(true.B)
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

      dut.io.inst.poke("h30200073".U)
      dut.io.ctrl.illegal.expect(true.B)
      dut.io.ctrl.mret.expect(false.B)
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
}
