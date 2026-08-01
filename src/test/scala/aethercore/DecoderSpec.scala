package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common._
import aethercore.config.IsaConfig
import aethercore.core.Decoder

class DecoderSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Decoder"

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

  it should "exclude RV64-only encodings from RV32I" in {
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
