package aethercore.core

import chisel3._
import chisel3.util._

object Immediate {
  def i(inst: UInt): UInt = Cat(Fill(52, inst(31)), inst(31, 20))
  def s(inst: UInt): UInt = Cat(Fill(52, inst(31)), inst(31, 25), inst(11, 7))
  def b(inst: UInt): UInt = Cat(Fill(51, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  def u(inst: UInt): UInt = Cat(Fill(32, inst(31)), inst(31, 12), 0.U(12.W))
  def j(inst: UInt): UInt = Cat(Fill(43, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
}
