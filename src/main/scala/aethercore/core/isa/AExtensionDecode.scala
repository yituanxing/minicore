package aethercore.core.isa

import chisel3._
import chisel3.util._
import aethercore.common._

/** A-extension semantic decode.
  *
  * Width legality is architectural: W exists on RV32/RV64; D is RV64-only.
  * aq/rl remain ordering annotations and do not change the current execution
  * control shape.
  */
object AExtensionDecode {
  def decode(
      c: ControlSignals,
      rs2: UInt,
      funct3: UInt,
      funct5: UInt,
      xlen: Int,
      hasA: Boolean
  ): Unit = {
    val atomicWidthLegal =
      if (xlen == 64) funct3 === "b010".U || funct3 === "b011".U
      else funct3 === "b010".U

    when(hasA.B && atomicWidthLegal) {
      c.usesRs1 := true.B
      c.regWrite := true.B
      c.opBSel := OpBSel.Imm
      c.wbSel := WbSel.Memory
      if (xlen == 64) {
        c.memSize := Mux(funct3 === "b011".U, MemSize.DWord, MemSize.Word)
      } else {
        c.memSize := MemSize.Word
      }

      switch(funct5) {
        is("b00010".U) {
          when(rs2 === 0.U) {
            c.illegal := false.B
            c.memRead := true.B
            c.atomicOp := AtomicOp.Lr
          }
        }
        is("b00011".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Sc
        }
        is("b00001".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Swap
        }
        is("b00000".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Add
        }
        is("b00100".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Xor
        }
        is("b01100".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.And
        }
        is("b01000".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Or
        }
        is("b10000".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Min
        }
        is("b10100".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Max
        }
        is("b11000".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Minu
        }
        is("b11100".U) {
          c.illegal := false.B
          c.usesRs2 := true.B
          c.memRead := true.B
          c.memWrite := true.B
          c.atomicOp := AtomicOp.Maxu
        }
      }
    }
  }
}
