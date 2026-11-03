package aethercore.core.product

import chisel3._
import chisel3.util._
import aethercore.common.AluOp
import aethercore.core.ALU
import aethercore.core.arith.UnsignedRestoringDivider

/** Shared decode of M FU ownership, independent of the physical ROB index. */
object ProductMOperation {
  def isM(op: AluOp.Type): Bool =
    op === AluOp.Mul || op === AluOp.Mulh ||
    op === AluOp.Mulhsu || op === AluOp.Mulhu ||
    op === AluOp.Div || op === AluOp.Divu ||
    op === AluOp.Rem || op === AluOp.Remu

  def isDivide(op: AluOp.Type): Bool =
    op === AluOp.Div || op === AluOp.Divu ||
    op === AluOp.Rem || op === AluOp.Remu
}

/** A serialized M execution resource with independent tagged completion.
  *
  * Multiplication reuses the one canonical ALU but physically disables its
  * combinational division paths. Division uses the same shared iterative
  * restoring primitive as the legacy qualified v2 unit; no duplicate divide
  * recurrence or ROB4 identity is imported.
  *
  * One in-flight M request. Busy DIV never blocks independent Integer/Branch
  * work, because IQ receives distinct resource-availability inputs.
  */
class ProductMUnit(val geometry: ProductCoreGeometry) extends Module {
  private val I = log2Ceil(geometry.robEntries)
  private val P = log2Ceil(geometry.integerPhysicalRegisters)
  private val G = 16
  private val Xlen = 64
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ProductIssue(Xlen, I, P, G)))
    val response = Decoupled(new ProductCompletion(Xlen, I, P, G))
    val available = Output(Bool())
  })
  private val req = io.request.bits
  private val isM = ProductMOperation.isM(req.aluOp)
  private val divide = ProductMOperation.isDivide(req.aluOp)
  private val signed = req.aluOp === AluOp.Div || req.aluOp === AluOp.Rem
  private val remainder = req.aluOp === AluOp.Rem || req.aluOp === AluOp.Remu

  private val divider = Module(new UnsignedRestoringDivider(Xlen))
  private val multiplier = Module(new ALU(Xlen, withMulDiv = true,
    withMultiply = true, withDivide = false))
  multiplier.io.a := req.lhs
  multiplier.io.b := req.rhs
  multiplier.io.op := req.aluOp
  multiplier.io.wordOp := req.wordOp

  private val activeDivide = RegInit(false.B)
  private val multiplyHolding = RegInit(false.B)
  private val saved = Reg(new ProductIssue(Xlen, I, P, G))
  private val multiplyResponse = Reg(new ProductCompletion(Xlen, I, P, G))
  private val negativeQuotient = Reg(Bool())
  private val negativeRemainder = Reg(Bool())
  private val useRemainder = Reg(Bool())
  private val divisorZero = Reg(Bool())

  io.available := !activeDivide && !multiplyHolding && divider.io.request.ready
  io.request.ready := io.available && isM

  val signedLhsWord = Cat(Fill(32, req.lhs(31)), req.lhs(31, 0))
  val signedRhsWord = Cat(Fill(32, req.rhs(31)), req.rhs(31, 0))
  val unsignedLhsWord = Cat(0.U(32.W), req.lhs(31, 0))
  val unsignedRhsWord = Cat(0.U(32.W), req.rhs(31, 0))
  val normalizedLhs = Mux(req.wordOp,
    Mux(signed, signedLhsWord, unsignedLhsWord), req.lhs)
  val normalizedRhs = Mux(req.wordOp,
    Mux(signed, signedRhsWord, unsignedRhsWord), req.rhs)
  val lhsNegative = signed && normalizedLhs(63)
  val rhsNegative = signed && normalizedRhs(63)
  val absLhs = Mux(lhsNegative, (~normalizedLhs).asUInt + 1.U, normalizedLhs)
  val absRhs = Mux(rhsNegative, (~normalizedRhs).asUInt + 1.U, normalizedRhs)

  divider.io.request.valid := io.request.fire && divide
  divider.io.request.bits.dividend := absLhs
  divider.io.request.bits.divisor := absRhs
  divider.io.response.ready := io.response.ready && activeDivide

  when(io.request.fire) {
    assert(isM, "product M unit must receive only M-extension operations")
    saved := req
    when(divide) {
      activeDivide := true.B
      negativeQuotient := lhsNegative ^ rhsNegative
      negativeRemainder := lhsNegative
      useRemainder := remainder
      divisorZero := normalizedRhs === 0.U
    }.otherwise {
      multiplyHolding := true.B
      multiplyResponse := 0.U.asTypeOf(multiplyResponse)
      multiplyResponse.token := req.token
      multiplyResponse.destination := req.destination
      multiplyResponse.hasValue := req.hasDestination
      multiplyResponse.value := multiplier.io.out
    }
  }

  private val rawQ = divider.io.response.bits.quotient
  private val rawR = divider.io.response.bits.remainder
  private val signedQ = Mux(negativeQuotient, (~rawQ).asUInt + 1.U, rawQ)
  private val signedR = Mux(negativeRemainder, (~rawR).asUInt + 1.U, rawR)
  private val quotient = Mux(divisorZero, Fill(Xlen, 1.U(1.W)), signedQ)
  private val selected = Mux(useRemainder, signedR, quotient)
  private val divideValue = Mux(saved.wordOp,
    Cat(Fill(32, selected(31)), selected(31, 0)), selected)

  private val divideResponse = Wire(new ProductCompletion(Xlen, I, P, G))
  divideResponse := 0.U.asTypeOf(divideResponse)
  divideResponse.token := saved.token
  divideResponse.destination := saved.destination
  divideResponse.hasValue := saved.hasDestination
  divideResponse.value := divideValue

  io.response.valid := Mux(activeDivide, divider.io.response.valid, multiplyHolding)
  io.response.bits := Mux(activeDivide, divideResponse, multiplyResponse)

  when(io.response.fire) {
    when(activeDivide) {
      activeDivide := false.B
    }.otherwise {
      multiplyHolding := false.B
    }
  }
  assert(!(activeDivide && multiplyHolding), "one M FU owns at most one request")
}
