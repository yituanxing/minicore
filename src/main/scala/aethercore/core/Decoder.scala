package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common._
import aethercore.config.{CoreProfiles, IsaConfig}
import aethercore.core.isa.{AExtensionDecode, BaseIDecode, MExtensionDecode, SystemDecode}

/** Top-level ISA semantic decode composition.
  *
  * This module owns the stable Decoder IO and default control contract. The
  * instruction semantics themselves are delegated to extension-specific
  * decode owners under core/isa so RV32/RV64 and optional extensions do not
  * accumulate in one monolithic opcode switch.
  */
class Decoder(val isa: IsaConfig = CoreProfiles.rv64imCurrent.isa) extends Module {
  private val hasM = isa.hasM
  private val hasA = isa.hasA
  private val hasZicsr = isa.hasZicsr
  private val hasZifencei = isa.hasZifencei
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
  val funct5 = io.inst(31, 27)

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
  c.atomicOp := AtomicOp.None
  c.xret := XRetOp.None
  if (hasWordOps) c.memSize := MemSize.DWord else c.memSize := MemSize.Word
  c.illegal := true.B

  switch(opcode) {
    is("b0110111".U) { BaseIDecode.decodeLui(c) }
    is("b0010111".U) { BaseIDecode.decodeAuipc(c) }
    is("b1101111".U) { BaseIDecode.decodeJal(c) }
    is("b1100111".U) { BaseIDecode.decodeJalr(c, funct3) }
    is("b1100011".U) { BaseIDecode.decodeBranch(c, funct3) }
    is("b0000011".U) { BaseIDecode.decodeLoad(c, funct3, hasWordOps) }
    is("b0100011".U) { BaseIDecode.decodeStore(c, funct3, hasWordOps) }

    is("b0101111".U) {
      AExtensionDecode.decode(c, io.rs2, funct3, funct5, isa.xlen, hasA)
    }

    is("b0010011".U) {
      BaseIDecode.decodeOpImm(c, funct3, funct6, funct7, isa.xlen)
    }

    is("b0110011".U) {
      // Base-I and M own disjoint funct7 patterns under the shared OP opcode.
      BaseIDecode.decodeOp(c, funct3, funct7)
      MExtensionDecode.decodeOp(c, funct3, funct7, hasM)
    }

    is("b0011011".U) {
      BaseIDecode.decodeOpImm32(c, funct3, funct7, hasWordOps)
    }

    is("b0111011".U) {
      // RV64 base word operations and M word operations likewise compose under
      // one architectural opcode without sharing semantic ownership.
      BaseIDecode.decodeOp32(c, funct3, funct7, hasWordOps)
      MExtensionDecode.decodeOp32(c, funct3, funct7, hasM, hasWordOps)
    }

    is("b0001111".U) {
      BaseIDecode.decodeFence(c, funct3)
      SystemDecode.decodeFenceI(c, funct3, hasZifencei)
    }

    is("b1110011".U) {
      SystemDecode.decodeSystem(c, io.inst, funct3, hasZicsr, isa.hasS)
    }
  }

  io.ctrl := c
}
