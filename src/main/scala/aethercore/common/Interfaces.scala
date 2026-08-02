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
  val Alu, PcPlus4, Memory, Csr = Value
}

object CsrOp extends ChiselEnum {
  val None, Write, Set, Clear = Value
}

object BranchType extends ChiselEnum {
  val None, Eq, Ne, Lt, Ge, Ltu, Geu = Value
}

object MemSize extends ChiselEnum {
  val Byte, Half, Word, DWord = Value
}

object MachineExceptionCode {
  val InstructionAccessFault: Int = 1
  val IllegalInstruction: Int = 2
  val Breakpoint: Int = 3
  val LoadAccessFault: Int = 5
  val StoreAccessFault: Int = 7
  val EnvironmentCallFromM: Int = 11
}

class TrapInfo(val xlen: Int) extends Bundle {
  require(xlen == 32 || xlen == 64, s"trap XLEN must be 32 or 64, got $xlen")

  val valid = Bool()
  val cause = UInt(xlen.W)
  val value = UInt(xlen.W)
}

class InstructionBusIO(val addrBits: Int = 64) extends Bundle {
  require(addrBits > 0, s"instruction address width must be positive, got $addrBits")

  val addr = Output(UInt(addrBits.W))
  val inst = Input(UInt(32.W))
  val fault = Input(Bool())
}

class DataBusIO(val addrBits: Int = 64, val dataBits: Int = 64) extends Bundle {
  require(addrBits > 0, s"data address width must be positive, got $addrBits")
  require(dataBits > 0 && dataBits % 8 == 0, s"data width must be byte aligned, got $dataBits")

  val valid = Output(Bool())
  val write = Output(Bool())
  val addr = Output(UInt(addrBits.W))
  val wdata = Output(UInt(dataBits.W))
  val wmask = Output(UInt((dataBits / 8).W))
  val size = Output(MemSize())

  val ready = Input(Bool())
  val rdata = Input(UInt(dataBits.W))
  val fault = Input(Bool())
}

class CommitTrace(
    val xlen: Int = 64,
    val paddrBits: Int = 64,
    val busDataBits: Int = 64
) extends Bundle {
  require(xlen == 32 || xlen == 64, s"commit XLEN must be 32 or 64, got $xlen")
  require(paddrBits > 0, s"commit physical address width must be positive, got $paddrBits")
  require(busDataBits > 0 && busDataBits % 8 == 0, s"commit bus width must be byte aligned, got $busDataBits")

  val valid = Bool()
  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val rd = UInt(5.W)
  val rdWrite = Bool()
  val rdData = UInt(xlen.W)

  val memValid = Bool()
  val memWrite = Bool()
  val memAddr = UInt(paddrBits.W)
  val memWdata = UInt(busDataBits.W)
  val memWmask = UInt((busDataBits / 8).W)

  val exception = Bool()
  val exceptionCause = UInt(xlen.W)
  val exceptionValue = UInt(xlen.W)
}

class ControlSignals extends Bundle {
  val aluOp = AluOp()
  val immSel = ImmSel()
  val opASel = OpASel()
  val opBSel = OpBSel()
  val wbSel = WbSel()
  val csrOp = CsrOp()
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
  val csrUseImm = Bool()
  val trap = Bool()
  val illegal = Bool()
}
