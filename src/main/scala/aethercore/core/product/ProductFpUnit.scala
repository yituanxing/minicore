package aethercore.core.product

import aethercore.common.MachineExceptionCode
import aethercore.core.semantic.{FloatingFormat, FloatingOperationKind}
import chisel3._
import chisel3.util._

/** FPGA-synthesizable RV64 F/D execution cluster with one FP lifetime.
  *
  * Real IEEE arithmetic, conversion and iterative division/square root use
  * HardFloat. Sign/minmax/compare/classify/move use exact bit-level logic.
  * Both share a single ROB completion interface, floating rounding state
  * and a token-qualified recovery path. Numerical qualification still
  * requires independently executed simulation/differential regressions.
  *
  * Sign, min/max, comparisons, class and moves are implemented with their
  * actual architectural floating-point semantics, including NaN boxing,
  * sNaN invalid flags, signed zeros, and raw FMV transfer exceptions.
  */
class ProductFpUnit(val geometry: ProductCoreGeometry, val paddrBits: Int = 56)
  extends Module {
  private val Xlen = 64
  private val I = log2Ceil(geometry.robEntries)
  private val P = log2Ceil(geometry.integerPhysicalRegisters)
  private val G = 16

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ProductFpIssue(Xlen, I, P, G)))
    val response = Decoupled(new ProductCompletion(Xlen, I, P, G, paddrBits))
    // Architectural FCSR.frm is sampled only when an rm=111 arithmetic uOp
    // actually issues. The exact-head SYSTEM lane serializes earlier CSR writes.
    val dynamicRoundingMode = Input(UInt(3.W))
    // One recovery victim per cycle, qualified by ROB index AND generation.
    // Never flush all FP work on redirect: older FP work must survive.
    val kill = Flipped(Valid(new ProductRobId(I, G)))
    val available = Output(Bool())
  })

  val req = io.request.bits
  // Long-latency IEEE arithmetic/conversion is owned by qualified HardFloat,
  // while bit-level moves, classification, comparison and sign/minmax stay
  // here. No fabricated integer approximation is permitted on the math path.
  val arithmetic = Module(new ProductFpArithmetic(geometry, paddrBits))
  val arithmeticKind = Seq(
    FloatingOperationKind.Add, FloatingOperationKind.Sub,
    FloatingOperationKind.Mul, FloatingOperationKind.Div,
    FloatingOperationKind.Sqrt, FloatingOperationKind.Fma,
    FloatingOperationKind.Fmsub, FloatingOperationKind.Fnmsub,
    FloatingOperationKind.Fnmadd, FloatingOperationKind.ConvertToInt,
    FloatingOperationKind.ConvertFromInt, FloatingOperationKind.ConvertFormat
  ).map(req.floating.kind === _).reduce(_ || _)
  val single = req.floating.format === FloatingFormat.Single
  val canonicalSingle = "h7fc00000".U(32.W)
  val canonicalDouble = "h7ff8000000000000".U(64.W)
  val boxedCanonicalSingle = Cat(Fill(32, 1.U(1.W)), canonicalSingle)

  // Every narrow arithmetic input (including FSGNJ/FCLASS/FCMP) must be
  // NaN-boxed. FMV.X.W/D and floating stores deliberately bypass this path.
  def unpack(raw: UInt): UInt = {
    val narrow = Mux(raw(63, 32).andR, raw(31, 0), canonicalSingle)
    Mux(single, Cat(Fill(32, 1.U(1.W)), narrow), raw)
  }
  val a = unpack(req.fpLhs)
  val b = unpack(req.fpRhs)
  val aSign = Mux(single, a(31), a(63))
  val bSign = Mux(single, b(31), b(63))

  val aExp = Mux(single, Cat(0.U(3.W), a(30, 23)), a(62, 52))
  val bExp = Mux(single, Cat(0.U(3.W), b(30, 23)), b(62, 52))
  val aFrac = Mux(single, Cat(0.U(29.W), a(22, 0)), a(51, 0))
  val bFrac = Mux(single, Cat(0.U(29.W), b(22, 0)), b(51, 0))
  val maxExp = Mux(single, 255.U(11.W), 2047.U(11.W))
  val aNaN = (aExp === maxExp) && aFrac.orR
  val bNaN = (bExp === maxExp) && bFrac.orR
  val aSNaN = aNaN && !Mux(single, a(22), a(51))
  val bSNaN = bNaN && !Mux(single, b(22), b(51))
  val aZero = (aExp === 0.U) && !aFrac.orR
  val bZero = (bExp === 0.U) && !bFrac.orR
  val aInf = (aExp === maxExp) && !aFrac.orR
  val canonicalNaN = Mux(single, boxedCanonicalSingle, canonicalDouble)

  // IEEE binary interchange encodings sort by magnitude for non-negative
  // operands; reverse the magnitude ordering when both are negative.
  val aMagnitude = Mux(single, Cat(0.U(32.W), a(30, 0)), a(62, 0))
  val bMagnitude = Mux(single, Cat(0.U(32.W), b(30, 0)), b(62, 0))
  val fpEqual = (aZero && bZero) || (a === b)
  val fpLess = !fpEqual && Mux(aSign =/= bSign, aSign,
    Mux(aSign, aMagnitude > bMagnitude, aMagnitude < bMagnitude))

  val signBit = WireDefault(bSign)
  switch(req.floating.subop) {
    is(1.U) { signBit := !bSign }
    is(2.U) { signBit := aSign ^ bSign }
  }
  val signResult = Mux(single,
    Cat(Fill(32, 1.U(1.W)), signBit, a(30, 0)), Cat(signBit, a(62, 0)))

  val minimum = req.floating.subop === 0.U
  val minmaxValue = WireDefault(a)
  when(aNaN && bNaN) {
    minmaxValue := canonicalNaN
  }.elsewhen(aNaN) {
    minmaxValue := b
  }.elsewhen(bNaN) {
    minmaxValue := a
  }.elsewhen(aZero && bZero) {
    // RISC-V FMIN picks -0 and FMAX picks +0, even for unequal zero signs.
    minmaxValue := Mux(minimum,
      Mux(aSign, a, b), Mux(aSign, b, a))
  }.otherwise {
    minmaxValue := Mux(minimum, Mux(fpLess, a, b), Mux(fpLess, b, a))
  }

  val compareValue = WireDefault(false.B)
  switch(req.floating.subop) {
    is(2.U) { compareValue := !aNaN && !bNaN && fpEqual } // FEQ
    is(1.U) { compareValue := !aNaN && !bNaN && fpLess } // FLT
    is(0.U) { compareValue := !aNaN && !bNaN && (fpLess || fpEqual) } // FLE
  }

  // FCLASS bit 9:quiet NaN through bit 0:negative infinity.  A malformed
  // single-precision box has become the canonical quiet NaN above.
  val positive = !aSign
  val normal = (aExp =/= 0.U) && !aNaN && !aInf
  val subnormal = (aExp === 0.U) && aFrac.orR
  val quietNaN = aNaN && !aSNaN
  val fpClass = Cat(
    quietNaN, aSNaN, positive && aInf,
    positive && normal, positive && subnormal, positive && aZero,
    aSign && aZero, aSign && subnormal,
    aSign && normal, aSign && aInf
  )

  val supported = WireDefault(false.B)
  val value = WireDefault(0.U(Xlen.W))
  val invalid = WireDefault(false.B)
  switch(req.floating.kind) {
    is(FloatingOperationKind.Sign) {
      supported := req.floating.subop <= 2.U
      value := signResult
    }
    is(FloatingOperationKind.MinMax) {
      supported := req.floating.subop <= 1.U
      value := minmaxValue
      invalid := aSNaN || bSNaN
    }
    is(FloatingOperationKind.Compare) {
      supported := req.floating.subop <= 2.U
      value := compareValue.asUInt
      invalid := Mux(req.floating.subop === 2.U,
        aSNaN || bSNaN, aNaN || bNaN)
    }
    is(FloatingOperationKind.Classify) {
      supported := true.B
      value := fpClass.pad(Xlen)
    }
    is(FloatingOperationKind.MoveToInt) {
      supported := true.B
      // FMV.X.W sign-extends the *raw bits*, even a malformed NaN box.
      value := Mux(single,
        Cat(Fill(32, req.fpLhs(31)), req.fpLhs(31, 0)), req.fpLhs)
    }
    is(FloatingOperationKind.MoveFromInt) {
      supported := true.B
      value := Mux(single,
        Cat(Fill(32, 1.U(1.W)), req.intLhs(31, 0)), req.intLhs)
    }
  }

  val held = RegInit(false.B)
  val arithmeticInFlight = RegInit(false.B)
  val arithmeticToken = Reg(new ProductRobId(I, G))
  val responseReg = Reg(new ProductCompletion(Xlen, I, P, G, paddrBits))
  val bitKilled = io.kill.valid && held &&
    io.kill.bits.index === responseReg.token.index &&
    io.kill.bits.generation === responseReg.token.generation
  val arithmeticKilled = io.kill.valid && arithmeticInFlight &&
    io.kill.bits.index === arithmeticToken.index &&
    io.kill.bits.generation === arithmeticToken.generation

  // Small, one-wide FPGA composition: at most one *FP* operation in flight.
  // Integer and memory instructions may still execute while HardFloat is
  // busy. The shared completion port has no hidden second retire owner.
  io.available := !held && !arithmeticInFlight && arithmetic.io.available &&
    !io.kill.valid
  // Availability is deliberately request-independent for the backend IQ
  // scheduler. Handshake readiness may additionally depend on the selected
  // operation: an iterative div/sqrt must wait for HardFloat.inReady, even
  // if the cluster otherwise has no outstanding FP lifetime.
  io.request.ready := io.available &&
    (!arithmeticKind || arithmetic.io.request.ready)
  arithmetic.io.request.valid := io.request.valid && io.available && arithmeticKind
  arithmetic.io.request.bits := req
  arithmetic.io.frm := io.dynamicRoundingMode
  arithmetic.io.flush := arithmeticKilled
  arithmetic.io.response.ready := io.response.ready && !held

  io.response.valid := Mux(held, !bitKilled, arithmetic.io.response.valid)
  io.response.bits := Mux(held, responseReg, arithmetic.io.response.bits)

  when(arithmetic.io.request.fire) {
    arithmeticInFlight := true.B
    arithmeticToken := req.token
  }.elsewhen(arithmeticKilled || arithmetic.io.response.fire) {
    arithmeticInFlight := false.B
  }

  when(bitKilled) {
    held := false.B
  }.elsewhen(io.request.fire && !arithmeticKind) {
    held := true.B
    responseReg := 0.U.asTypeOf(responseReg)
    responseReg.token := req.token
    responseReg.destination := req.destination
    responseReg.destinationFile := req.destinationFile
    // Completion.hasValue is a destination-ownership contract, not an
    // architectural commit enable. The ROB suppresses any PRF write on the
    // precise exception path, while still matching the original destination.
    responseReg.hasValue := req.hasDestination
    // An unsupported operation must not even place a plausible fabricated
    // arithmetic result on the completion bus.
    responseReg.value := Mux(supported, value, 0.U)
    // FFLAGS layout is NV,DZ,OF,UF,NX. No speculative exception flag writes.
    responseReg.fflags := Cat(supported && invalid, 0.U(4.W))
    responseReg.fpStateDirty := supported &&
      ((req.hasDestination && req.destinationFile === ProductRegisterFile.Floating) || invalid)
    responseReg.exception.valid := !supported
    responseReg.exception.cause := Mux(supported, 0.U,
      MachineExceptionCode.IllegalInstruction.U)
    responseReg.exception.value := Mux(supported, 0.U, req.rawInst)
  }.elsewhen(io.response.fire && held) {
    held := false.B
  }

  when(arithmeticInFlight) {
    assert(!held, "a one-wide FP unit cannot have simultaneous bit and arithmetic owners")
  }
}
