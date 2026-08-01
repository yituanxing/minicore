package aethercore.common

import chisel3._
import chisel3.util._

object AluOp extends ChiselEnum {
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And,
      Mul, Mulh, Mulhsu, Mulhu, Div, Divu, Rem, Remu = Value
}

object ImmSel extends ChiselEnum {
  val None, I, S, B, U, J = Value
}

object OpASel extends ChiselEnum {
  val Rs1, Pc, Zero = Value
}

object OpBSel extends ChiselEnum {
  val Rs2, Imm = Value
}

object WbSel extends ChiselEnum {
  val Alu, PcPlus4, Memory = Value
}

object BranchType extends ChiselEnum {
  val None, Eq, Ne, Lt, Ge, Ltu, Geu = Value
}

object MemSize extends ChiselEnum {
  val Byte, Half, Word, DWord = Value
}

class InstructionBusIO extends Bundle {
  val addr = Output(UInt(64.W))
  val inst = Input(UInt(32.W))
  val fault = Input(Bool())
}

class DataBusIO extends Bundle {
  val valid = Output(Bool())
  val write = Output(Bool())
  val addr = Output(UInt(64.W))
  val wdata = Output(UInt(64.W))
  val wmask = Output(UInt(8.W))
  val size = Output(MemSize())

  val ready = Input(Bool())
  val rdata = Input(UInt(64.W))
  val fault = Input(Bool())
}

class CommitTrace extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val rd = UInt(5.W)
  val rdWrite = Bool()
  val rdData = UInt(64.W)

  val memValid = Bool()
  val memWrite = Bool()
  val memAddr = UInt(64.W)
  val memWdata = UInt(64.W)
  val memWmask = UInt(8.W)

  val exception = Bool()
}

class ControlSignals extends Bundle {
  val aluOp = AluOp()
  val immSel = ImmSel()
  val opASel = OpASel()
  val opBSel = OpBSel()
  val wbSel = WbSel()
  val branch = BranchType()
  val memSize = MemSize()

  val regWrite = Bool()
  val memRead = Bool()
  val memWrite = Bool()
  val memUnsigned = Bool()
  val wordOp = Bool()
  val jump = Bool()
  val jalr = Bool()
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val trap = Bool()
  val illegal = Bool()
}
