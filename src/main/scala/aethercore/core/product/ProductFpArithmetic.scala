package aethercore.core.product

import aethercore.common.MachineExceptionCode
import aethercore.core.semantic.{FloatingFormat, FloatingOperationKind, FloatingRoundingMode}
import chisel3._
import chisel3.util._
import hardfloat._

/** One IEEE-754 datapath for either binary32 or binary64.
  *
  * HardFloat takes recoded inputs (one exponent bit wider than IEEE storage)
  * and produces architectural NV/DZ/OF/UF/NX flags. All finite arithmetic,
  * including FMA, happens in the selected *destination* format: promoting a
  * binary32 operation to binary64 and narrowing later would double-round.
  *
  * Multiplication shares the fused multiply-add datapath by adding a signed
  * zero of the product's sign. This preserves the sign of a zero product.
  */
private[product] class ProductFpFormatArithmetic(expWidth: Int, sigWidth: Int)
    extends Module {
  private val isSingle = expWidth == 8
  require((expWidth == 8 && sigWidth == 24) || (expWidth == 11 && sigWidth == 53))
  private val fpWidth = expWidth + sigWidth
  private val otherExp = if (isSingle) 11 else 8
  private val otherSig = if (isSingle) 53 else 24

  val io = IO(new Bundle {
    val kind = Input(FloatingOperationKind())
    val a = Input(UInt(64.W))
    val b = Input(UInt(64.W))
    val c = Input(UInt(64.W))
    val intLhs = Input(UInt(64.W))
    val integerWidth = Input(Bool())
    val integerUnsigned = Input(Bool())
    val rm = Input(UInt(3.W))
    val divFire = Input(Bool())
    val result = Output(UInt(64.W))
    val flags = Output(UInt(5.W))
    val divReady = Output(Bool())
    val divDone = Output(Bool())
    val divResult = Output(UInt(64.W))
    val divFlags = Output(UInt(5.W))
  })

  val canonicalSingle = "h7fc00000".U(32.W)
  val canonicalDouble = "h7ff8000000000000".U(64.W)
  def boxSingle(bits: UInt): UInt = Cat(Fill(32, 1.U(1.W)), bits(31, 0))
  def canonicalize(bits: UInt, e: Int, s: Int): UInt = {
    val isNaN = bits(e + s - 2, s - 1).andR && bits(s - 2, 0).orR
    Mux(isNaN, if (e == 8) canonicalSingle else canonicalDouble, bits)
  }
  def ieeeOperand(value: UInt): UInt = {
    if (isSingle) {
      Mux(value(63, 32).andR, value(31, 0), canonicalSingle)
    } else {
      value
    }
  }
  def ieeeToResult(bits: UInt): UInt = {
    val normalized = canonicalize(bits, expWidth, sigWidth)
    if (isSingle) boxSingle(normalized) else normalized
  }
  val aBits = ieeeOperand(io.a)
  val bBits = ieeeOperand(io.b)
  val cBits = ieeeOperand(io.c)
  val aRec = recFNFromFN(expWidth, sigWidth, aBits)
  val bRec = recFNFromFN(expWidth, sigWidth, bBits)
  val cRec = recFNFromFN(expWidth, sigWidth, cBits)

  // RISC-V specifies tininess detection *after* rounding.
  val afterRounding = 1.U(1.W)

  val add = Module(new AddRecFN(expWidth, sigWidth))
  add.io.subOp := io.kind === FloatingOperationKind.Sub
  add.io.a := aRec
  add.io.b := bRec
  add.io.roundingMode := io.rm
  add.io.detectTininess := afterRounding.asBool

  val mulAdd = Module(new MulAddRecFN(expWidth, sigWidth))
  val productSign = aBits(fpWidth - 1) ^ bBits(fpWidth - 1)
  val sameSignZero = recFNFromFN(expWidth, sigWidth,
    Cat(productSign, 0.U((fpWidth - 1).W)))
  mulAdd.io.a := aRec
  mulAdd.io.b := bRec
  mulAdd.io.c := Mux(io.kind === FloatingOperationKind.Mul, sameSignZero, cRec)
  mulAdd.io.op := MuxLookup(io.kind.asUInt, 0.U(2.W))(Seq(
    FloatingOperationKind.Fmsub.asUInt -> 1.U(2.W),
    FloatingOperationKind.Fnmsub.asUInt -> 2.U(2.W),
    FloatingOperationKind.Fnmadd.asUInt -> 3.U(2.W)))
  mulAdd.io.roundingMode := io.rm
  mulAdd.io.detectTininess := afterRounding

  val intInput = Mux(io.integerWidth, io.intLhs,
    Mux(io.integerUnsigned, Cat(0.U(32.W), io.intLhs(31, 0)),
      Cat(Fill(32, io.intLhs(31)), io.intLhs(31, 0))))
  val fromInt = Module(new INToRecFN(64, expWidth, sigWidth))
  fromInt.io.in := intInput
  fromInt.io.signedIn := !io.integerUnsigned
  fromInt.io.roundingMode := io.rm
  fromInt.io.detectTininess := afterRounding

  val toW = Module(new RecFNToIN(expWidth, sigWidth, 32))
  toW.io.in := aRec
  toW.io.roundingMode := io.rm
  toW.io.signedOut := !io.integerUnsigned

  val toL = Module(new RecFNToIN(expWidth, sigWidth, 64))
  toL.io.in := aRec
  toL.io.roundingMode := io.rm
  toL.io.signedOut := !io.integerUnsigned

  // HardFloat integer conversion flags are (invalid, overflow, inexact).
  // RISC-V collapses invalid/overflow to NV and uses NX for inexact.
  val intFlags = Mux(io.integerWidth, toL.io.intExceptionFlags,
    toW.io.intExceptionFlags)
  val riscVIntFlags = Cat(intFlags(2, 1).orR, 0.U(3.W), intFlags(0))

  // FCVT.S.D and FCVT.D.S use the source precision's own recoding.
  // A malformed narrow source becomes a canonical quiet NaN.
  val sourceBits =
    if (isSingle) io.a else Mux(io.a(63, 32).andR, io.a(31, 0), canonicalSingle)
  val fromOther = Module(new RecFNToRecFN(otherExp, otherSig, expWidth, sigWidth))
  fromOther.io.in := recFNFromFN(otherExp, otherSig, sourceBits)
  fromOther.io.roundingMode := io.rm
  fromOther.io.detectTininess := afterRounding

  // The small iterative divider uses far fewer resources than a
  // fully-combinational divide/sqrt datapath, and it pulses outValid.
  val div = Module(new DivSqrtRecFN_small(expWidth, sigWidth, 0))
  div.io.inValid := io.divFire
  div.io.sqrtOp := io.kind === FloatingOperationKind.Sqrt
  div.io.a := aRec
  div.io.b := bRec
  div.io.roundingMode := io.rm
  div.io.detectTininess := afterRounding
  io.divReady := div.io.inReady
  io.divDone := div.io.outValid_div || div.io.outValid_sqrt
  io.divResult := ieeeToResult(fNFromRecFN(expWidth, sigWidth, div.io.out))
  io.divFlags := div.io.exceptionFlags

  io.result := 0.U
  io.flags := 0.U
  switch(io.kind) {
    is(FloatingOperationKind.Add, FloatingOperationKind.Sub) {
      io.result := ieeeToResult(fNFromRecFN(expWidth, sigWidth, add.io.out))
      io.flags := add.io.exceptionFlags
    }
    is(FloatingOperationKind.Mul, FloatingOperationKind.Fma,
       FloatingOperationKind.Fmsub, FloatingOperationKind.Fnmsub,
       FloatingOperationKind.Fnmadd) {
      io.result := ieeeToResult(fNFromRecFN(expWidth, sigWidth, mulAdd.io.out))
      io.flags := mulAdd.io.exceptionFlags
    }
    is(FloatingOperationKind.ConvertToInt) {
      io.result := Mux(io.integerWidth, toL.io.out,
        Cat(Fill(32, toW.io.out(31)), toW.io.out))
      io.flags := riscVIntFlags
    }
    is(FloatingOperationKind.ConvertFromInt) {
      io.result := ieeeToResult(fNFromRecFN(expWidth, sigWidth, fromInt.io.out))
      io.flags := fromInt.io.exceptionFlags
    }
    is(FloatingOperationKind.ConvertFormat) {
      io.result := ieeeToResult(fNFromRecFN(expWidth, sigWidth, fromOther.io.out))
      io.flags := fromOther.io.exceptionFlags
    }
  }
}

/** RV64 F/D arithmetic with one outstanding ROB lifetime.
  *
  * Ordinary operations register their completion on request.fire. Iterative
  * divide/sqrt keeps its original token until HardFloat asserts outValid,
  * then buffers the completion across arbitrary downstream backpressure.
  *
  * io.flush cancels ONLY this in-flight lifetime, after the caller matches a
  * victim ROB token. Never apply an unconditional branch-redirect flush:
  * an older FP divide may legitimately survive a younger mispredicted branch.
  * Canceled iterative operations drain internally before accepting new work.
  */
class ProductFpArithmetic(val geometry: ProductCoreGeometry, val paddrBits: Int = 56)
    extends Module {
  private val Xlen = 64
  private val I = log2Ceil(geometry.robEntries)
  private val P = log2Ceil(geometry.integerPhysicalRegisters)
  private val G = 16
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ProductFpIssue(Xlen, I, P, G)))
    val response = Decoupled(new ProductCompletion(Xlen, I, P, G, paddrBits))
    val frm = Input(UInt(3.W))
    val flush = Input(Bool())
    val available = Output(Bool())
  })

  val req = io.request.bits
  val (roundMode, validRoundMode) =
    FloatingRoundingMode.resolveForArithmetic(req.floating.roundingMode, io.frm)
  val kind = req.floating.kind
  val iterative = kind === FloatingOperationKind.Div ||
    kind === FloatingOperationKind.Sqrt
  val supported = kind === FloatingOperationKind.Add ||
    kind === FloatingOperationKind.Sub ||
    kind === FloatingOperationKind.Mul ||
    kind === FloatingOperationKind.Div ||
    kind === FloatingOperationKind.Sqrt ||
    kind === FloatingOperationKind.Fma ||
    kind === FloatingOperationKind.Fmsub ||
    kind === FloatingOperationKind.Fnmsub ||
    kind === FloatingOperationKind.Fnmadd ||
    kind === FloatingOperationKind.ConvertToInt ||
    kind === FloatingOperationKind.ConvertFromInt ||
    kind === FloatingOperationKind.ConvertFormat
  val illegal = !supported || !validRoundMode
  val isSingle = req.floating.format === FloatingFormat.Single

  val s = Module(new ProductFpFormatArithmetic(8, 24))
  val d = Module(new ProductFpFormatArithmetic(11, 53))
  Seq(s, d).foreach { unit =>
    unit.io.kind := req.floating.kind
    unit.io.a := req.fpLhs
    unit.io.b := req.fpRhs
    unit.io.c := req.fpRs3
    unit.io.intLhs := req.intLhs
    unit.io.integerWidth := req.floating.integerWidth
    unit.io.integerUnsigned := req.floating.integerUnsigned
    unit.io.rm := roundMode
  }

  val held = RegInit(false.B)
  val waitingDiv = RegInit(false.B)
  val canceledDiv = RegInit(false.B)
  val divWasSingle = RegInit(false.B)
  val pending = Reg(new ProductCompletion(Xlen, I, P, G, paddrBits))
  val responseReg = Reg(new ProductCompletion(Xlen, I, P, G, paddrBits))

  val divReady = Mux(isSingle, s.io.divReady, d.io.divReady)
  io.request.ready := !io.flush && !held && !waitingDiv &&
    (!iterative || illegal || divReady)
  // The scheduler uses available before it has selected a concrete request.
  // Never feed the request kind back into availability or form an issue loop.
  io.available := !io.flush && !held && !waitingDiv
  val launchDiv = io.request.fire && iterative && !illegal
  s.io.divFire := launchDiv && isSingle
  d.io.divFire := launchDiv && !isSingle

  io.response.valid := held && !io.flush
  io.response.bits := responseReg

  // A single, explicit completion constructor avoids losing any ROB fields.
  val incoming = Wire(new ProductCompletion(Xlen, I, P, G, paddrBits))
  incoming := 0.U.asTypeOf(incoming)
  incoming.token := req.token
  incoming.destination := req.destination
  incoming.destinationFile := req.destinationFile
  incoming.hasValue := req.hasDestination
  incoming.value := Mux(illegal, 0.U,
    Mux(isSingle, s.io.result, d.io.result))
  incoming.fflags := Mux(illegal, 0.U,
    Mux(isSingle, s.io.flags, d.io.flags))
  incoming.fpStateDirty := !illegal &&
    ((req.hasDestination && req.destinationFile === ProductRegisterFile.Floating) ||
      incoming.fflags.orR)
  incoming.exception.valid := illegal
  incoming.exception.cause := Mux(illegal,
    MachineExceptionCode.IllegalInstruction.U, 0.U)
  incoming.exception.value := Mux(illegal, req.rawInst, 0.U)

  val activeDivDone = Mux(divWasSingle, s.io.divDone, d.io.divDone)
  val activeDivResult = Mux(divWasSingle, s.io.divResult, d.io.divResult)
  val activeDivFlags = Mux(divWasSingle, s.io.divFlags, d.io.divFlags)
  when(io.flush) {
    held := false.B
    when(waitingDiv) {
      // outValid is a pulse. If a matching kill coincides with completion,
      // consume it NOW; waiting for another pulse would deadlock forever.
      when(activeDivDone) {
        waitingDiv := false.B
        canceledDiv := false.B
      }.otherwise {
        canceledDiv := true.B
      }
    }
  }.otherwise {
    when(io.response.fire) {
      held := false.B
    }
    when(waitingDiv && activeDivDone) {
      waitingDiv := false.B
      when(!canceledDiv) {
        responseReg := pending
        responseReg.value := activeDivResult
        responseReg.fflags := activeDivFlags
        responseReg.fpStateDirty :=
          (pending.hasValue && pending.destinationFile === ProductRegisterFile.Floating) ||
          activeDivFlags.orR
        held := true.B
      }
    }
    when(io.request.fire) {
      when(launchDiv) {
        waitingDiv := true.B
        canceledDiv := false.B
        divWasSingle := isSingle
        pending := incoming
      }.otherwise {
        held := true.B
        responseReg := incoming
      }
    }
  }
}
