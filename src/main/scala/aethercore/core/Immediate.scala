package aethercore.core

import chisel3._
import chisel3.util._

object Immediate {
  private def signExtend(value: UInt, valueBits: Int, xlen: Int): UInt = {
    require(xlen == 32 || xlen == 64, s"immediate XLEN must be 32 or 64, got $xlen")
    require(valueBits <= xlen, s"cannot extend $valueBits-bit immediate to XLEN $xlen")

    if (valueBits == xlen) value
    else Cat(Fill(xlen - valueBits, value(valueBits - 1)), value)
  }

  def i(inst: UInt, xlen: Int = 64): UInt =
    signExtend(inst(31, 20), 12, xlen)

  def s(inst: UInt, xlen: Int = 64): UInt =
    signExtend(Cat(inst(31, 25), inst(11, 7)), 12, xlen)

  def b(inst: UInt, xlen: Int = 64): UInt =
    signExtend(Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)), 13, xlen)

  def u(inst: UInt, xlen: Int = 64): UInt =
    signExtend(Cat(inst(31, 12), 0.U(12.W)), 32, xlen)

  def j(inst: UInt, xlen: Int = 64): UInt =
    signExtend(Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)), 21, xlen)
}
