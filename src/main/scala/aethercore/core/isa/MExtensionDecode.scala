package aethercore.core.isa

import chisel3._
import chisel3.util._
import aethercore.common._

/** M-extension semantic decode.
  *
  * M shares the base OP/OP-32 opcodes but owns only funct7=0000001 patterns.
  */
object MExtensionDecode {
  def decodeOp(c: ControlSignals, funct3: UInt, funct7: UInt, hasM: Boolean): Unit = {
    when(funct7 === "b0000001".U && hasM.B) {
      switch(funct3) {
        is("b000".U) { c.illegal := false.B; c.aluOp := AluOp.Mul }
        is("b001".U) { c.illegal := false.B; c.aluOp := AluOp.Mulh }
        is("b010".U) { c.illegal := false.B; c.aluOp := AluOp.Mulhsu }
        is("b011".U) { c.illegal := false.B; c.aluOp := AluOp.Mulhu }
        is("b100".U) { c.illegal := false.B; c.aluOp := AluOp.Div }
        is("b101".U) { c.illegal := false.B; c.aluOp := AluOp.Divu }
        is("b110".U) { c.illegal := false.B; c.aluOp := AluOp.Rem }
        is("b111".U) { c.illegal := false.B; c.aluOp := AluOp.Remu }
      }
    }
  }

  def decodeOp32(
      c: ControlSignals,
      funct3: UInt,
      funct7: UInt,
      hasM: Boolean,
      hasWordOps: Boolean
  ): Unit = {
    if (hasWordOps) {
      when(funct7 === "b0000001".U && hasM.B) {
        switch(funct3) {
          is("b000".U) { c.illegal := false.B; c.aluOp := AluOp.Mul }
          is("b100".U) { c.illegal := false.B; c.aluOp := AluOp.Div }
          is("b101".U) { c.illegal := false.B; c.aluOp := AluOp.Divu }
          is("b110".U) { c.illegal := false.B; c.aluOp := AluOp.Rem }
          is("b111".U) { c.illegal := false.B; c.aluOp := AluOp.Remu }
        }
      }
    }
  }
}
