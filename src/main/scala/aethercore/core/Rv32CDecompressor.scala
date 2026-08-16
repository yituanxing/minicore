package aethercore.core

import chisel3._
import chisel3.util._

/** Standalone RV32C integer decompressor.
  *
  * This module deliberately has no dependency on CoreConfig and is not wired
  * into the fetch path yet. It translates one 16-bit standard RV32C integer
  * encoding into the single canonical RV32I instruction consumed by the
  * existing decoder. Encodings that require floating-point support, are
  * reserved/custom for RV32C, or are wider than 16 bits report legal=false.
  * Standard RVC HINT encodings remain legal and expand to harmless RV32I
  * operations whose architectural effect is a no-op.
  */
class Rv32CDecompressor extends Module {
  val io = IO(new Bundle {
    val raw = Input(UInt(16.W))
    val expanded = Output(UInt(32.W))
    val legal = Output(Bool())
  })

  private val OpLoad = "b0000011".U(7.W)
  private val OpImm = "b0010011".U(7.W)
  private val OpStore = "b0100011".U(7.W)
  private val Op = "b0110011".U(7.W)
  private val OpLui = "b0110111".U(7.W)
  private val OpBranch = "b1100011".U(7.W)
  private val OpJalr = "b1100111".U(7.W)
  private val OpJal = "b1101111".U(7.W)

  private def iType(imm12: UInt, rs1: UInt, funct3: UInt, rd: UInt, opcode: UInt): UInt =
    Cat(imm12(11, 0), rs1(4, 0), funct3(2, 0), rd(4, 0), opcode(6, 0))

  private def sType(imm12: UInt, rs2: UInt, rs1: UInt, funct3: UInt): UInt =
    Cat(imm12(11, 5), rs2(4, 0), rs1(4, 0), funct3(2, 0), imm12(4, 0), OpStore)

  private def bType(imm13: UInt, rs2: UInt, rs1: UInt, funct3: UInt): UInt =
    Cat(
      imm13(12),
      imm13(10, 5),
      rs2(4, 0),
      rs1(4, 0),
      funct3(2, 0),
      imm13(4, 1),
      imm13(11),
      OpBranch
    )

  private def uType(imm20: UInt, rd: UInt): UInt =
    Cat(imm20(19, 0), rd(4, 0), OpLui)

  private def jType(imm21: UInt, rd: UInt): UInt =
    Cat(
      imm21(20),
      imm21(10, 1),
      imm21(11),
      imm21(19, 12),
      rd(4, 0),
      OpJal
    )

  private def rType(funct7: UInt, rs2: UInt, rs1: UInt, funct3: UInt, rd: UInt): UInt =
    Cat(funct7(6, 0), rs2(4, 0), rs1(4, 0), funct3(2, 0), rd(4, 0), Op)

  val c = io.raw
  val quadrant = c(1, 0)
  val funct3 = c(15, 13)
  val rd = c(11, 7)
  val rs2 = c(6, 2)
  val rdPrime = Cat("b01".U(2.W), c(4, 2))
  val rs1Prime = Cat("b01".U(2.W), c(9, 7))
  val rs2Prime = Cat("b01".U(2.W), c(4, 2))

  // Common immediates reconstructed in the width expected by the canonical
  // RV32 instruction encoding.
  val ciImm12 = Cat(Fill(6, c(12)), c(12), c(6, 2))
  val addi4spnImm12 = Cat(0.U(2.W), c(10, 7), c(12, 11), c(5), c(6), 0.U(2.W))
  val lwImm12 = Cat(0.U(5.W), c(5), c(12, 10), c(6), 0.U(2.W))
  val addi16spImm12 = Cat(Fill(3, c(12)), c(4, 3), c(5), c(2), c(6), 0.U(4.W))
  val luiImm20 = Cat(Fill(14, c(12)), c(12), c(6, 2))
  val lwspImm12 = Cat(0.U(4.W), c(3, 2), c(12), c(6, 4), 0.U(2.W))
  val swspImm12 = Cat(0.U(4.W), c(8, 7), c(12, 9), 0.U(2.W))

  val compactJumpImm = Cat(
    c(12), c(8), c(10, 9), c(6), c(7), c(2), c(11), c(5, 3), 0.U(1.W)
  )
  val jumpImm21 = Cat(Fill(9, c(12)), compactJumpImm)

  val compactBranchImm = Cat(c(12), c(6, 5), c(2), c(11, 10), c(4, 3), 0.U(1.W))
  val branchImm13 = Cat(Fill(4, c(12)), compactBranchImm)

  val expanded = WireDefault("h00000013".U(32.W)) // canonical NOP on illegal input
  val legal = WireDefault(false.B)

  switch(quadrant) {
    is("b00".U) {
      switch(funct3) {
        is("b000".U) { // C.ADDI4SPN
          when(addi4spnImm12 =/= 0.U) {
            expanded := iType(addi4spnImm12, 2.U, 0.U, rdPrime, OpImm)
            legal := true.B
          }
        }
        is("b010".U) { // C.LW
          expanded := iType(lwImm12, rs1Prime, 2.U, rdPrime, OpLoad)
          legal := true.B
        }
        is("b110".U) { // C.SW
          expanded := sType(lwImm12, rs2Prime, rs1Prime, 2.U)
          legal := true.B
        }
        // Other quadrant-0 standard encodings require F/D, which AetherCore
        // does not implement, or are reserved.
      }
    }

    is("b01".U) {
      switch(funct3) {
        is("b000".U) { // C.ADDI / C.NOP / HINTs
          expanded := iType(ciImm12, rd, 0.U, rd, OpImm)
          legal := true.B
        }
        is("b001".U) { // C.JAL (RV32C)
          expanded := jType(jumpImm21, 1.U)
          legal := true.B
        }
        is("b010".U) { // C.LI (rd=x0 is a HINT)
          expanded := iType(ciImm12, 0.U, 0.U, rd, OpImm)
          legal := true.B
        }
        is("b011".U) {
          when(rd === 2.U) { // C.ADDI16SP
            when(Cat(c(12), c(6, 2)) =/= 0.U) {
              expanded := iType(addi16spImm12, 2.U, 0.U, 2.U, OpImm)
              legal := true.B
            }
          }.otherwise { // C.LUI, including rd=x0 HINTs
            when(Cat(c(12), c(6, 2)) =/= 0.U) {
              expanded := uType(luiImm20, rd)
              legal := true.B
            }
          }
        }
        is("b100".U) {
          switch(c(11, 10)) {
            is("b00".U) { // C.SRLI; RV32 shamt[5]=1 is custom
              when(!c(12)) {
                expanded := iType(Cat(0.U(7.W), c(6, 2)), rs1Prime, 5.U, rs1Prime, OpImm)
                legal := true.B
              }
            }
            is("b01".U) { // C.SRAI; RV32 shamt[5]=1 is custom
              when(!c(12)) {
                expanded := iType(Cat("b0100000".U(7.W), c(6, 2)), rs1Prime, 5.U, rs1Prime, OpImm)
                legal := true.B
              }
            }
            is("b10".U) { // C.ANDI
              expanded := iType(ciImm12, rs1Prime, 7.U, rs1Prime, OpImm)
              legal := true.B
            }
            is("b11".U) { // C.SUB/XOR/OR/AND; bit12=1 is RV64-only/reserved here
              when(!c(12)) {
                switch(c(6, 5)) {
                  is("b00".U) {
                    expanded := rType("b0100000".U, rs2Prime, rs1Prime, 0.U, rs1Prime)
                    legal := true.B
                  }
                  is("b01".U) {
                    expanded := rType(0.U, rs2Prime, rs1Prime, 4.U, rs1Prime)
                    legal := true.B
                  }
                  is("b10".U) {
                    expanded := rType(0.U, rs2Prime, rs1Prime, 6.U, rs1Prime)
                    legal := true.B
                  }
                  is("b11".U) {
                    expanded := rType(0.U, rs2Prime, rs1Prime, 7.U, rs1Prime)
                    legal := true.B
                  }
                }
              }
            }
          }
        }
        is("b101".U) { // C.J
          expanded := jType(jumpImm21, 0.U)
          legal := true.B
        }
        is("b110".U) { // C.BEQZ
          expanded := bType(branchImm13, 0.U, rs1Prime, 0.U)
          legal := true.B
        }
        is("b111".U) { // C.BNEZ
          expanded := bType(branchImm13, 0.U, rs1Prime, 1.U)
          legal := true.B
        }
      }
    }

    is("b10".U) {
      switch(funct3) {
        is("b000".U) { // C.SLLI; RV32 shamt[5]=1 is custom
          when(!c(12)) {
            expanded := iType(Cat(0.U(7.W), c(6, 2)), rd, 1.U, rd, OpImm)
            legal := true.B
          }
        }
        is("b010".U) { // C.LWSP
          when(rd =/= 0.U) {
            expanded := iType(lwspImm12, 2.U, 2.U, rd, OpLoad)
            legal := true.B
          }
        }
        is("b100".U) {
          when(!c(12)) {
            when(rs2 === 0.U) { // C.JR; rs1=x0 is reserved
              when(rd =/= 0.U) {
                expanded := iType(0.U(12.W), rd, 0.U, 0.U, OpJalr)
                legal := true.B
              }
            }.otherwise { // C.MV; rd=x0 is a HINT
              expanded := rType(0.U, rs2, 0.U, 0.U, rd)
              legal := true.B
            }
          }.otherwise {
            when(rs2 === 0.U) {
              when(rd === 0.U) { // C.EBREAK
                expanded := "h00100073".U
                legal := true.B
              }.otherwise { // C.JALR
                expanded := iType(0.U(12.W), rd, 0.U, 1.U, OpJalr)
                legal := true.B
              }
            }.otherwise { // C.ADD; rd=x0 is a HINT
              expanded := rType(0.U, rs2, rd, 0.U, rd)
              legal := true.B
            }
          }
        }
        is("b110".U) { // C.SWSP
          expanded := sType(swspImm12, rs2, 2.U, 2.U)
          legal := true.B
        }
        // Other quadrant-2 standard encodings require F/D and are therefore
        // illegal for the current integer-only AetherCore surface.
      }
    }

    // Quadrant 3 denotes instructions wider than 16 bits, not RVC input.
  }

  io.expanded := expanded
  io.legal := legal
}
