package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common._
import aethercore.config.{CoreProfiles, IsaConfig}

class Decoder(val isa: IsaConfig = CoreProfiles.rv64imCurrent.isa) extends Module {
  private val hasM = isa.hasM
  private val hasZicsr = isa.hasZicsr
  private val hasWordOps = isa.hasWordOps

  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val rs1 = Output(UInt(5.W))
    val rs2 = Output(UInt(5.W))
    val rd = Output(UInt(5.W))
    val ctrl = Output(new ControlSignals)
  })

  val opcode = io.inst(6, 0)
  val funct3 = io.inst(14, 12)
  val funct7 = io.inst(31, 25)
  val funct6 = io.inst(31, 26)

  val shiftLogicalImmediate =
    if (isa.xlen == 64) funct6 === "b000000".U else funct7 === "b0000000".U
  val shiftArithmeticImmediate =
    if (isa.xlen == 64) funct6 === "b010000".U else funct7 === "b0100000".U

  io.rs1 := io.inst(19, 15)
  io.rs2 := io.inst(24, 20)
  io.rd := io.inst(11, 7)

  val c = WireInit(0.U.asTypeOf(new ControlSignals))
  c.aluOp := AluOp.Add
  c.immSel := ImmSel.None
  c.opASel := OpASel.Rs1
  c.opBSel := OpBSel.Rs2
  c.wbSel := WbSel.Alu
  c.csrOp := CsrOp.None
  c.branch := BranchType.None
  if (hasWordOps) c.memSize := MemSize.DWord else c.memSize := MemSize.Word
  c.illegal := true.B

  switch(opcode) {
    is("b0110111".U) {
      c.illegal := false.B; c.regWrite := true.B; c.opASel := OpASel.Zero; c.opBSel := OpBSel.Imm; c.immSel := ImmSel.U
    }
    is("b0010111".U) {
      c.illegal := false.B; c.regWrite := true.B; c.opASel := OpASel.Pc; c.opBSel := OpBSel.Imm; c.immSel := ImmSel.U
    }
    is("b1101111".U) {
      c.illegal := false.B; c.regWrite := true.B; c.jump := true.B; c.immSel := ImmSel.J; c.wbSel := WbSel.PcPlus4
    }
    is("b1100111".U) {
      when(funct3 === 0.U) {
        c.illegal := false.B; c.regWrite := true.B; c.jump := true.B; c.jalr := true.B; c.usesRs1 := true.B; c.immSel := ImmSel.I; c.wbSel := WbSel.PcPlus4
      }
    }
    is("b1100011".U) {
      c.usesRs1 := true.B; c.usesRs2 := true.B; c.immSel := ImmSel.B
      switch(funct3) {
        is("b000".U) { c.illegal := false.B; c.branch := BranchType.Eq }
        is("b001".U) { c.illegal := false.B; c.branch := BranchType.Ne }
        is("b100".U) { c.illegal := false.B; c.branch := BranchType.Lt }
        is("b101".U) { c.illegal := false.B; c.branch := BranchType.Ge }
        is("b110".U) { c.illegal := false.B; c.branch := BranchType.Ltu }
        is("b111".U) { c.illegal := false.B; c.branch := BranchType.Geu }
      }
    }
    is("b0000011".U) {
      c.usesRs1 := true.B; c.regWrite := true.B; c.memRead := true.B; c.opBSel := OpBSel.Imm; c.immSel := ImmSel.I; c.wbSel := WbSel.Memory
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
    is("b0100011".U) {
      c.usesRs1 := true.B; c.usesRs2 := true.B; c.memWrite := true.B; c.opBSel := OpBSel.Imm; c.immSel := ImmSel.S
      switch(funct3) {
        is("b000".U) { c.illegal := false.B; c.memSize := MemSize.Byte }
        is("b001".U) { c.illegal := false.B; c.memSize := MemSize.Half }
        is("b010".U) { c.illegal := false.B; c.memSize := MemSize.Word }
        is("b011".U) {
          when(hasWordOps.B) { c.illegal := false.B; c.memSize := MemSize.DWord }
        }
      }
    }
    is("b0010011".U) {
      c.usesRs1 := true.B; c.regWrite := true.B; c.opBSel := OpBSel.Imm; c.immSel := ImmSel.I
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
    is("b0110011".U) {
      c.usesRs1 := true.B; c.usesRs2 := true.B; c.regWrite := true.B
      switch(funct3) {
        is("b000".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Add }
          when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sub }
          when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Mul }
        }
        is("b001".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Sll }
          when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Mulh }
        }
        is("b010".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Slt }
          when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Mulhsu }
        }
        is("b011".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Sltu }
          when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Mulhu }
        }
        is("b100".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Xor }
          when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Div }
        }
        is("b101".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Srl }
          when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sra }
          when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Divu }
        }
        is("b110".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Or }
          when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Rem }
        }
        is("b111".U) {
          when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.And }
          when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Remu }
        }
      }
    }
    is("b0011011".U) {
      if (hasWordOps) {
        c.usesRs1 := true.B; c.regWrite := true.B; c.opBSel := OpBSel.Imm; c.immSel := ImmSel.I; c.wordOp := true.B
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
    is("b0111011".U) {
      if (hasWordOps) {
        c.usesRs1 := true.B; c.usesRs2 := true.B; c.regWrite := true.B; c.wordOp := true.B
        switch(funct3) {
          is("b000".U) {
            when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Add }
            when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sub }
            when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Mul }
          }
          is("b001".U) { when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Sll } }
          is("b100".U) { when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Div } }
          is("b101".U) {
            when(funct7 === "b0000000".U) { c.illegal := false.B; c.aluOp := AluOp.Srl }
            when(funct7 === "b0100000".U) { c.illegal := false.B; c.aluOp := AluOp.Sra }
            when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Divu }
          }
          is("b110".U) { when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Rem } }
          is("b111".U) { when(funct7 === "b0000001".U && hasM.B) { c.illegal := false.B; c.aluOp := AluOp.Remu } }
        }
      }
    }
    is("b0001111".U) { when(funct3 === 0.U || funct3 === 1.U) { c.illegal := false.B } }
    is("b1110011".U) {
      switch(funct3) {
        is("b000".U) {
          when(io.inst === "h00000073".U || io.inst === "h00100073".U) {
            c.illegal := false.B
            c.trap := true.B
          }.elsewhen(io.inst === "h30200073".U && hasZicsr.B) {
            c.illegal := false.B
            c.mret := true.B
          }
        }
        is("b001".U) {
          when(hasZicsr.B) {
            c.illegal := false.B; c.regWrite := true.B; c.wbSel := WbSel.Csr
            c.csrOp := CsrOp.Write; c.usesRs1 := true.B
          }
        }
        is("b010".U) {
          when(hasZicsr.B) {
            c.illegal := false.B; c.regWrite := true.B; c.wbSel := WbSel.Csr
            c.csrOp := CsrOp.Set; c.usesRs1 := true.B
          }
        }
        is("b011".U) {
          when(hasZicsr.B) {
            c.illegal := false.B; c.regWrite := true.B; c.wbSel := WbSel.Csr
            c.csrOp := CsrOp.Clear; c.usesRs1 := true.B
          }
        }
        is("b101".U) {
          when(hasZicsr.B) {
            c.illegal := false.B; c.regWrite := true.B; c.wbSel := WbSel.Csr
            c.csrOp := CsrOp.Write; c.csrUseImm := true.B
          }
        }
        is("b110".U) {
          when(hasZicsr.B) {
            c.illegal := false.B; c.regWrite := true.B; c.wbSel := WbSel.Csr
            c.csrOp := CsrOp.Set; c.csrUseImm := true.B
          }
        }
        is("b111".U) {
          when(hasZicsr.B) {
            c.illegal := false.B; c.regWrite := true.B; c.wbSel := WbSel.Csr
            c.csrOp := CsrOp.Clear; c.csrUseImm := true.B
          }
        }
      }
    }
  }

  io.ctrl := c
}
