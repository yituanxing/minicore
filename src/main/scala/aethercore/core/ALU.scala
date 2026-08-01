package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.AluOp

class ALU(val xlen: Int = 64) extends Module {
  require(xlen == 32 || xlen == 64, s"ALU XLEN must be 32 or 64, got $xlen")

  val io = IO(new Bundle {
    val a = Input(UInt(xlen.W))
    val b = Input(UInt(xlen.W))
    val op = Input(AluOp())
    val wordOp = Input(Bool())
    val out = Output(UInt(xlen.W))
  })

  val shamt = io.b(log2Ceil(xlen) - 1, 0)

  // Extend both operands by one bit before multiplication. This preserves the
  // signed/unsigned interpretation while leaving a 2*XLEN architectural
  // product in the low portion of the generated result.
  val aSignedExt = Cat(io.a(xlen - 1), io.a).asSInt
  val bSignedExt = Cat(io.b(xlen - 1), io.b).asSInt
  val aUnsignedExt = Cat(0.U(1.W), io.a).asSInt
  val bUnsignedExt = Cat(0.U(1.W), io.b).asSInt

  val productSS = (aSignedExt * bSignedExt).asUInt
  val productSU = (aSignedExt * bUnsignedExt).asUInt
  val productUU = (aUnsignedExt * bUnsignedExt).asUInt

  val allOnes = ((BigInt(1) << xlen) - 1).U(xlen.W)
  val minSigned = (BigInt(1) << (xlen - 1)).U(xlen.W)
  val divByZero = io.b === 0.U
  val signedOverflow = io.a === minSigned && io.b === allOnes
  val signedExceptional = divByZero || signedOverflow

  // Chisel Muxes are hardware, not host-language short-circuit branches.
  // Feed safe operands to the generated divider even when the architectural
  // result is selected from an exceptional-case constant.
  val signedDividend = Mux(signedOverflow, 0.S(xlen.W), io.a.asSInt)
  val signedDivisor = Mux(signedExceptional, 1.S(xlen.W), io.b.asSInt)
  val signedQuotient = (signedDividend / signedDivisor).asUInt
  val signedRemainder = (signedDividend % signedDivisor).asUInt

  val unsignedDivisor = Mux(divByZero, 1.U(xlen.W), io.b)
  val unsignedQuotient = io.a / unsignedDivisor
  val unsignedRemainder = io.a % unsignedDivisor

  val result = WireDefault(0.U(xlen.W))
  switch(io.op) {
    is(AluOp.Add)    { result := io.a + io.b }
    is(AluOp.Sub)    { result := io.a - io.b }
    is(AluOp.Sll)    { result := io.a << shamt }
    is(AluOp.Slt)    { result := (io.a.asSInt < io.b.asSInt).asUInt }
    is(AluOp.Sltu)   { result := (io.a < io.b).asUInt }
    is(AluOp.Xor)    { result := io.a ^ io.b }
    is(AluOp.Srl)    { result := io.a >> shamt }
    is(AluOp.Sra)    { result := (io.a.asSInt >> shamt).asUInt }
    is(AluOp.Or)     { result := io.a | io.b }
    is(AluOp.And)    { result := io.a & io.b }
    is(AluOp.Mul)    { result := productUU(xlen - 1, 0) }
    is(AluOp.Mulh)   { result := productSS(2 * xlen - 1, xlen) }
    is(AluOp.Mulhsu) { result := productSU(2 * xlen - 1, xlen) }
    is(AluOp.Mulhu)  { result := productUU(2 * xlen - 1, xlen) }
    is(AluOp.Div) {
      result := Mux(divByZero, allOnes,
        Mux(signedOverflow, minSigned, signedQuotient))
    }
    is(AluOp.Divu) {
      result := Mux(divByZero, allOnes, unsignedQuotient)
    }
    is(AluOp.Rem) {
      result := Mux(divByZero, io.a,
        Mux(signedOverflow, 0.U(xlen.W), signedRemainder))
    }
    is(AluOp.Remu) {
      result := Mux(divByZero, io.a, unsignedRemainder)
    }
  }

  if (xlen == 64) {
    val a32 = io.a(31, 0)
    val b32 = io.b(31, 0)
    val shamt32 = b32(4, 0)
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

    io.out := Mux(io.wordOp, Cat(Fill(32, result32(31)), result32), result)
  } else {
    assert(!io.wordOp, "word operations are only valid for RV64")
    io.out := result
  }
}
