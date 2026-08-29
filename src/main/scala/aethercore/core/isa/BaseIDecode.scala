package aethercore.core.isa

import chisel3._
import chisel3.util._
import aethercore.common._

/** Base integer semantic decode shared by RV32I and RV64I.
  *
  * RV64-only word operations are guarded by `hasWordOps`; M-extension patterns
  * are intentionally not decoded here even though they share OP/OP-32 opcodes.
  */
object BaseIDecode {
  def decodeLui(c: ControlSignals): Unit = {
    c.illegal := false.B
    c.regWrite := true.B
    c.opASel := OpASel.Zero
    c.opBSel := OpBSel.Imm
    c.immSel := ImmSel.U
  }

  def decodeAuipc(c: ControlSignals): Unit = {
    c.illegal := false.B
    c.regWrite := true.B
    c.opASel := OpASel.Pc
    c.opBSel := OpBSel.Imm
    c.immSel := ImmSel.U
  }

  def decodeJal(c: ControlSignals): Unit = {
    c.illegal := false.B
    c.regWrite := true.B
    c.jump := true.B
    c.immSel := ImmSel.J
    c.wbSel := WbSel.PcPlus4
  }

  def decodeJalr(c: ControlSignals, funct3: UInt): Unit = {
    when(funct3 === 0.U) {
      c.illegal := false.B
      c.regWrite := true.B
      c.jump := true.B
      c.jalr := true.B
      c.usesRs1 := true.B
      c.immSel := ImmSel.I
      c.wbSel := WbSel.PcPlus4
    }
  }

  def decodeBranch(c: ControlSignals, funct3: UInt): Unit = {
    c.usesRs1 := true.B
    c.usesRs2 := true.B
    c.immSel := ImmSel.B
    switch(funct3) {
      is("b000".U) { c.illegal := false.B; c.branch := BranchType.Eq }
      is("b001".U) { c.illegal := false.B; c.branch := BranchType.Ne }
      is("b100".U) { c.illegal := false.B; c.branch := BranchType.Lt }
      is("b101".U) { c.illegal := false.B; c.branch := BranchType.Ge }
      is("b110".U) { c.illegal := false.B; c.branch := BranchType.Ltu }
      is("b111".U) { c.illegal := false.B; c.branch := BranchType.Geu }
    }
  }

  def decodeLoad(c: ControlSignals, funct3: UInt, hasWordOps: Boolean): Unit = {
    c.usesRs1 := true.B
    c.regWrite := true.B
    c.memRead := true.B
    c.opBSel := OpBSel.Imm
    c.immSel := ImmSel.I
    c.wbSel := WbSel.Memory
    switch(funct3) {
      is("b000".U) { c.illegal := false.B; c.memSize := MemSize.Byte }
      is("b001".U) { c.illegal := false.B; c.memSize := MemSize.Half }
      is("b010".U) { c.illegal := false.B; c.memSize := MemSize.Word }
      is("b011".U) {
        when(hasWordOps.B) { c.illegal := false.B; c.memSize := MemSize.DWord }
      }
      is("b100".U) { c.illegal := false.B; c.memSize := MemSize.Byte; c.memUnsigned := true.B }
      is("b101".U) { c.illegal := false.B; c.memSize := MemSize.Half; c.memUnsigned := true.B }
      is("b110".U) {
        when(hasWordOps.B) { c.illegal := false.B; c.memSize := MemSize.Word; c.memUnsigned := true.B }
      }
    }
  }

  def decodeStore(c: ControlSignals, funct3: UInt, hasWordOps: Boolean): Unit = {
    c.usesRs1 := true.B
    c.usesRs2 := true.B
    c.memWrite := true.B
    c.opBSel := OpBSel.Imm
    c.immSel := ImmSel.S
    switch(funct3) {
      is("b000".U) { c.illegal := false.B; c.memSize := MemSize.Byte }
      is("b001".U) { c.illegal := false.B; c.memSize := MemSize.Half }
      is("b010".U) { c.illegal := false.B; c.memSize := MemSize.Word }
      is("b011".U) {
        when(hasWordOps.B) { c.illegal := false.B; c.memSize := MemSize.DWord }
      }
    }
  }

  def decodeOpImm(c: ControlSignals, funct3: UInt, funct6: UInt, funct7: UInt, xlen: Int): Unit = {
    val shiftLogicalImmediate =
      if (xlen == 64) funct6 === "b000000".U else funct7 === "b0000000".U
    val shiftArithmeticImmediate =
      if (xlen == 64) funct6 === "b010000".U else funct7 === "b0100000".U

    c.usesRs1 := true.B
    c.regWrite := true.B
    c.opBSel := OpBSel.Imm
    c.immSel := ImmSel.I
    switch(funct3) {
      is("b000".U) { c.illegal := false.B; c.aluOp := AluOp.Add }
      is("b010".U) { c.illegal := false.B; c.aluOp := AluOp.Slt }
      is("b011".U) { c.illegal := false.B; c.aluOp := AluOp.Sltu }
      is("b100".U) { c.illegal := false.B; c.aluOp := AluOp.Xor }
      is("b110".U) { c.illegal := false.B; c.aluOp := AluOp.Or }
      is("b111".U) { c.illegal := false.B; c.aluOp := AluOp.And }
      is("b001".U) { when(shiftLogicalImmediate) { c.illegal := false.B; c.aluOp := AluOp.Sll } }
      is("b101".U) {
        when(shiftLogicalImmediate) { c.illegal := false.B; c.aluOp := AluOp.Srl }
        when(shiftArithmeticImmediate) { c.illegal := false.B; c.aluOp := AluOp.Sra }
      }
    }
  }

  def decodeOp(c: ControlSignals, funct3: UInt, funct7: UInt): Unit = {
    c.usesRs1 := true.B
    c.usesRs2 := true.B
    c.regWrite := true.B
    switch(funct3) {
      is("b000".U) {
        when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Add }
        when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sub }
      }
      is("b001".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Sll } }
      is("b010".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Slt } }
      is("b011".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Sltu } }
      is("b100".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Xor } }
      is("b101".U) {
        when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Srl }
        when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sra }
      }
      is("b110".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Or } }
      is("b111".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.And } }
    }
  }

  def decodeOpImm32(c: ControlSignals, funct3: UInt, funct7: UInt, hasWordOps: Boolean): Unit = {
    if (hasWordOps) {
      c.usesRs1 := true.B
      c.regWrite := true.B
      c.opBSel := OpBSel.Imm
      c.immSel := ImmSel.I
      c.wordOp := true.B
      switch(funct3) {
        is("b000".U) { c.illegal := false.B; c.aluOp := AluOp.Add }
        is("b001".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Sll } }
        is("b101".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Srl }
          when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sra }
        }
      }
    }
  }

  def decodeOp32(c: ControlSignals, funct3: UInt, funct7: UInt, hasWordOps: Boolean): Unit = {
    if (hasWordOps) {
      c.usesRs1 := true.B
      c.usesRs2 := true.B
      c.regWrite := true.B
      c.wordOp := true.B
      switch(funct3) {
        is("b000".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Add }
          when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sub }
        }
        is("b001".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Sll } }
        is("b101".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Srl }
          when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sra }
        }
      }
    }
  }

  def decodeFence(c: ControlSignals, funct3: UInt): Unit = {
    when(funct3 === 0.U) { c.illegal := false.B }
  }
}
