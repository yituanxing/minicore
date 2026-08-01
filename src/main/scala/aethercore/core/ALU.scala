package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.AluOp

class ALU extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(64.W))
    val b = Input(UInt(64.W))
    val op = Input(AluOp())
    val wordOp = Input(Bool())
    val out = Output(UInt(64.W))
  })

  val shamt64 = io.b(5, 0)
  val shamt32 = io.b(4, 0)

  val result64 = WireDefault(0.U(64.W))
  switch(io.op) {
    is(AluOp.Add)  { result64 := io.a + io.b }
    is(AluOp.Sub)  { result64 := io.a - io.b }
    is(AluOp.Sll)  { result64 := io.a << shamt64 }
    is(AluOp.Slt)  { result64 := (io.a.asSInt < io.b.asSInt).asUInt }
    is(AluOp.Sltu) { result64 := (io.a < io.b).asUInt }
    is(AluOp.Xor)  { result64 := io.a ^ io.b }
    is(AluOp.Srl)  { result64 := io.a >> shamt64 }
    is(AluOp.Sra)  { result64 := (io.a.asSInt >> shamt64).asUInt }
    is(AluOp.Or)   { result64 := io.a | io.b }
    is(AluOp.And)  { result64 := io.a & io.b }
  }

  val a32 = io.a(31, 0)
  val b32 = io.b(31, 0)
  val result32 = WireDefault(0.U(32.W))
  switch(io.op) {
    is(AluOp.Add)  { result32 := a32 + b32 }
    is(AluOp.Sub)  { result32 := a32 - b32 }
    is(AluOp.Sll)  { result32 := a32 << shamt32 }
    is(AluOp.Slt)  { result32 := (a32.asSInt < b32.asSInt).asUInt }
    is(AluOp.Sltu) { result32 := (a32 < b32).asUInt }
    is(AluOp.Xor)  { result32 := a32 ^ b32 }
    is(AluOp.Srl)  { result32 := a32 >> shamt32 }
    is(AluOp.Sra)  { result32 := (a32.asSInt >> shamt32).asUInt }
    is(AluOp.Or)   { result32 := a32 | b32 }
    is(AluOp.And)  { result32 := a32 & b32 }
  }

  io.out := Mux(io.wordOp, Cat(Fill(32, result32(31)), result32), result64)
}
