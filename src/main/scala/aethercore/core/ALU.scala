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

  // Extend both operands to 65 bits before multiplication. This gives one
  // explicit sign/zero-extension bit and a 130-bit product from which the
  // architectural 128-bit low/high halves can be selected consistently.
  val aSigned65 = Cat(io.a(63), io.a).asSInt
  val bSigned65 = Cat(io.b(63), io.b).asSInt
  val aUnsigned65 = Cat(0.U(1.W), io.a).asSInt
  val bUnsigned65 = Cat(0.U(1.W), io.b).asSInt

  val productSS = (aSigned65 * bSigned65).asUInt
  val productSU = (aSigned65 * bUnsigned65).asUInt
  val productUU = (aUnsigned65 * bUnsigned65).asUInt

  val allOnes64 = "hffffffffffffffff".U(64.W)
  val minSigned64 = "h8000000000000000".U(64.W)
  val divByZero64 = io.b === 0.U
  val signedOverflow64 = io.a === minSigned64 && io.b === allOnes64
  val signedExceptional64 = divByZero64 || signedOverflow64

  // Chisel Muxes are hardware, not host-language short-circuit branches.
  // Feed safe operands to the generated divider even when the architectural
  // result is selected from an exceptional-case constant.
  val signedDividend64 = Mux(signedOverflow64, 0.S(64.W), io.a.asSInt)
  val signedDivisor64 = Mux(signedExceptional64, 1.S(64.W), io.b.asSInt)
  val signedQuotient64 = (signedDividend64 / signedDivisor64).asUInt
  val signedRemainder64 = (signedDividend64 % signedDivisor64).asUInt

  val unsignedDivisor64 = Mux(divByZero64, 1.U(64.W), io.b)
  val unsignedQuotient64 = io.a / unsignedDivisor64
  val unsignedRemainder64 = io.a % unsignedDivisor64

  val result64 = WireDefault(0.U(64.W))
  switch(io.op) {
    is(AluOp.Add)    { result64 := io.a + io.b }
    is(AluOp.Sub)    { result64 := io.a - io.b }
    is(AluOp.Sll)    { result64 := io.a << shamt64 }
    is(AluOp.Slt)    { result64 := (io.a.asSInt < io.b.asSInt).asUInt }
    is(AluOp.Sltu)   { result64 := (io.a < io.b).asUInt }
    is(AluOp.Xor)    { result64 := io.a ^ io.b }
    is(AluOp.Srl)    { result64 := io.a >> shamt64 }
    is(AluOp.Sra)    { result64 := (io.a.asSInt >> shamt64).asUInt }
    is(AluOp.Or)     { result64 := io.a | io.b }
    is(AluOp.And)    { result64 := io.a & io.b }
    is(AluOp.Mul)    { result64 := productUU(63, 0) }
    is(AluOp.Mulh)   { result64 := productSS(127, 64) }
    is(AluOp.Mulhsu) { result64 := productSU(127, 64) }
    is(AluOp.Mulhu)  { result64 := productUU(127, 64) }
    is(AluOp.Div) {
      result64 := Mux(divByZero64, allOnes64,
        Mux(signedOverflow64, minSigned64, signedQuotient64))
    }
    is(AluOp.Divu) {
      result64 := Mux(divByZero64, allOnes64, unsignedQuotient64)
    }
    is(AluOp.Rem) {
      result64 := Mux(divByZero64, io.a,
        Mux(signedOverflow64, 0.U(64.W), signedRemainder64))
    }
    is(AluOp.Remu) {
      result64 := Mux(divByZero64, io.a, unsignedRemainder64)
    }
  }

  val a32 = io.a(31, 0)
  val b32 = io.b(31, 0)
  val product32 = a32 * b32

  val allOnes32 = "hffffffff".U(32.W)
  val minSigned32 = "h80000000".U(32.W)
  val divByZero32 = b32 === 0.U
  val signedOverflow32 = a32 === minSigned32 && b32 === allOnes32
  val signedExceptional32 = divByZero32 || signedOverflow32

  val signedDividend32 = Mux(signedOverflow32, 0.S(32.W), a32.asSInt)
  val signedDivisor32 = Mux(signedExceptional32, 1.S(32.W), b32.asSInt)
  val signedQuotient32 = (signedDividend32 / signedDivisor32).asUInt
  val signedRemainder32 = (signedDividend32 % signedDivisor32).asUInt

  val unsignedDivisor32 = Mux(divByZero32, 1.U(32.W), b32)
  val unsignedQuotient32 = a32 / unsignedDivisor32
  val unsignedRemainder32 = a32 % unsignedDivisor32

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
    is(AluOp.Mul)  { result32 := product32(31, 0) }
    is(AluOp.Div) {
      result32 := Mux(divByZero32, allOnes32,
        Mux(signedOverflow32, minSigned32, signedQuotient32))
    }
    is(AluOp.Divu) {
      result32 := Mux(divByZero32, allOnes32, unsignedQuotient32)
    }
    is(AluOp.Rem) {
      result32 := Mux(divByZero32, a32,
        Mux(signedOverflow32, 0.U(32.W), signedRemainder32))
    }
    is(AluOp.Remu) {
      result32 := Mux(divByZero32, a32, unsignedRemainder32)
    }
  }

  io.out := Mux(io.wordOp, Cat(Fill(32, result32(31)), result32), result64)
}
