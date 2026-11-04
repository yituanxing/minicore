package aethercore

import aethercore.common.MachineExceptionCode
import aethercore.core.product._
import aethercore.core.semantic.{FloatingFormat, FloatingOperationKind}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Real IEEE F/D arithmetic must prove precise numerical bits *and* lifetime.
  * This suite targets the HardFloat-backed ProductFpArithmetic, not the
  * intentionally limited bit-level ProductFpUnit. All expected values are
  * architectural IEEE encodings; no host Double arithmetic is an oracle.
  */
class ProductFpArithmeticSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product HardFloat-backed RV64 F/D arithmetic"

  private val geometry = ProductCoreProfiles.smallGeometry
  private val Box = BigInt("ffffffff00000000", 16)
  private def boxed(bits: Long): BigInt = Box | BigInt(bits & 0xffffffffL)

  private def init(d: ProductFpArithmetic): Unit = {
    d.io.request.valid.poke(false.B)
    d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
    d.io.response.ready.poke(false.B)
    d.io.frm.poke(0.U)
    d.io.flush.poke(false.B)
    d.clock.step()
  }

  private def request(
      d: ProductFpArithmetic,
      kind: FloatingOperationKind.Type,
      format: FloatingFormat.Type,
      a: BigInt,
      b: BigInt = BigInt(0),
      c: BigInt = BigInt(0),
      sourceFormat: FloatingFormat.Type = FloatingFormat.Double,
      rm: Int = 0,
      frm: Int = 0,
      intLhs: BigInt = BigInt(0),
      integerWidth: Boolean = false,
      integerUnsigned: Boolean = false,
      toInteger: Boolean = false,
      token: Int = 3
  ): Unit = {
    d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
    val q = d.io.request.bits
    q.token.index.poke(token.U)
    q.token.generation.poke(9.U)
    q.destination.index.poke(35.U)
    q.destination.generation.poke(12.U)
    q.destinationFile.poke(if (toInteger)
      ProductRegisterFile.Integer else ProductRegisterFile.Floating)
    q.hasDestination.poke(true.B)
    q.floating.kind.poke(kind)
    q.floating.format.poke(format)
    q.floating.sourceFormat.poke(sourceFormat)
    q.floating.roundingMode.poke(rm.U)
    q.floating.integerWidth.poke(integerWidth.B)
    q.floating.integerUnsigned.poke(integerUnsigned.B)
    q.fpLhs.poke(a.U)
    q.fpRhs.poke(b.U)
    q.fpRs3.poke(c.U)
    q.intLhs.poke(intLhs.U)
    q.rawInst.poke("h53".U)
    d.io.frm.poke(frm.U)
    d.io.request.valid.poke(true.B)
    d.io.request.ready.expect(true.B)
    d.clock.step()
    d.io.request.valid.poke(false.B)
  }

  private def awaitCompletion(d: ProductFpArithmetic, maxCycles: Int = 360): Unit = {
    var elapsed = 0
    while (!d.io.response.valid.peek().litToBoolean && elapsed < maxCycles) {
      d.clock.step()
      elapsed += 1
    }
    withClue(s"FP arithmetic did not complete within $maxCycles clocks: ") {
      d.io.response.valid.expect(true.B)
    }
  }

  private def checkCompletion(
      d: ProductFpArithmetic, expected: BigInt, flags: Int = 0,
      illegal: Boolean = false, toInteger: Boolean = false, token: Int = 3
  ): Unit = {
    awaitCompletion(d)
    val c = d.io.response.bits
    c.token.index.expect(token.U)
    c.token.generation.expect(9.U)
    c.destination.index.expect(35.U)
    c.destination.generation.expect(12.U)
    c.destinationFile.expect(if (toInteger)
      ProductRegisterFile.Integer else ProductRegisterFile.Floating)
    c.hasValue.expect(true.B)
    c.exception.valid.expect(illegal.B)
    if (illegal) {
      c.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      c.exception.value.expect("h53".U)
    } else {
      c.value.expect(expected.U)
      c.fflags.expect(flags.U)
    }
    // The ROB may backpressure for an arbitrary duration: no one-cycle
    // response pulse can lose flags, destination ownership or data.
    d.clock.step(3)
    d.io.response.valid.expect(true.B)
    c.token.index.expect(token.U)
    c.exception.valid.expect(illegal.B)
    if (!illegal) {
      c.value.expect(expected.U)
      c.fflags.expect(flags.U)
    }
    d.io.response.ready.poke(true.B)
    d.clock.step()
    d.io.response.ready.poke(false.B)
    d.io.response.valid.expect(false.B)
  }

  it should "execute exact F32/F64 add subtract and multiply with NaN boxing" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      request(d, FloatingOperationKind.Add, FloatingFormat.Single,
        boxed(0x3f800000L), boxed(0x40000000L))
      checkCompletion(d, boxed(0x40400000L)) // 1+2=3
      request(d, FloatingOperationKind.Sub, FloatingFormat.Double,
        BigInt("4008000000000000", 16), BigInt("3ff0000000000000", 16))
      checkCompletion(d, BigInt("4000000000000000", 16)) // 3-1=2
      request(d, FloatingOperationKind.Mul, FloatingFormat.Double,
        BigInt("4000000000000000", 16), BigInt("4008000000000000", 16))
      checkCompletion(d, BigInt("4018000000000000", 16)) // 2*3=6
    }
  }

  it should "apply static and dynamic IEEE rounding modes with exact NX flags" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      val one = boxed(0x3f800000L)
      val halfUlp = boxed(0x33800000L) // 2^-24 at 1.0, exact tie
      request(d, FloatingOperationKind.Add, FloatingFormat.Single,
        one, halfUlp, rm = 0) // RNE tie-to-even
      checkCompletion(d, one, flags = 0x01)
      request(d, FloatingOperationKind.Add, FloatingFormat.Single,
        one, halfUlp, rm = 3) // RUP
      checkCompletion(d, boxed(0x3f800001L), flags = 0x01)
      request(d, FloatingOperationKind.Add, FloatingFormat.Single,
        one, halfUlp, rm = 7, frm = 3) // dynamic frm=RUP
      checkCompletion(d, boxed(0x3f800001L), flags = 0x01)
      request(d, FloatingOperationKind.Add, FloatingFormat.Single,
        one, halfUlp, rm = 5)
      checkCompletion(d, 0, illegal = true)
      request(d, FloatingOperationKind.Add, FloatingFormat.Single,
        one, halfUlp, rm = 7, frm = 6)
      checkCompletion(d, 0, illegal = true)
    }
  }

  it should "compute truly fused multiply-add before rounding" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      // (1+2^-23)(1-2^-23)-1 = -2^-46. A MUL then ADD sequence
      // would first round the product to 1.0 and incorrectly return zero.
      request(d, FloatingOperationKind.Fma, FloatingFormat.Single,
        boxed(0x3f800001L), boxed(0x3f7ffffeL), boxed(0xbf800000L))
      checkCompletion(d, boxed(0xa8800000L), flags = 0)
      // FMA must also honor fused semantics for binary64.
      request(d, FloatingOperationKind.Fma, FloatingFormat.Double,
        BigInt("3ff0000000000001", 16), BigInt("3feffffffffffffe", 16),
        BigInt("bff0000000000000", 16))
      checkCompletion(d, BigInt("b970000000000000", 16), flags = 0)
    }
  }

  it should "preserve every FMA sign variant without changing RS3 or rounding" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      val two = boxed(0x40000000L)
      val three = boxed(0x40400000L)
      val one = boxed(0x3f800000L)
      request(d, FloatingOperationKind.Fma, FloatingFormat.Single,
        two, three, one)
      checkCompletion(d, boxed(0x40e00000L)) // +(2*3)+1 = 7
      request(d, FloatingOperationKind.Fmsub, FloatingFormat.Single,
        two, three, one)
      checkCompletion(d, boxed(0x40a00000L)) // +(2*3)-1 = 5
      request(d, FloatingOperationKind.Fnmsub, FloatingFormat.Single,
        two, three, one)
      checkCompletion(d, boxed(0xc0a00000L)) // -(2*3)+1 = -5
      request(d, FloatingOperationKind.Fnmadd, FloatingFormat.Single,
        two, three, one)
      checkCompletion(d, boxed(0xc0e00000L)) // -(2*3)-1 = -7
    }
  }

  it should "distinguish overflow and tininess-after-rounding underflow flags" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      request(d, FloatingOperationKind.Mul, FloatingFormat.Single,
        boxed(0x7f7fffffL), boxed(0x40000000L)) // maximum finite * 2
      checkCompletion(d, boxed(0x7f800000L), flags = 0x05) // OF|NX
      request(d, FloatingOperationKind.Div, FloatingFormat.Single,
        boxed(0x00000001L), boxed(0x40000000L)) // minimum subnormal / 2
      checkCompletion(d, boxed(0x00000000L), flags = 0x03) // UF|NX
      request(d, FloatingOperationKind.Div, FloatingFormat.Single,
        boxed(0x00800000L), boxed(0x40000000L)) // minimum normal / 2 exact
      checkCompletion(d, boxed(0x00400000L), flags = 0x00)
    }
  }

  it should "handle iterative divide sqrt flags and stable ROB backpressure" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      request(d, FloatingOperationKind.Div, FloatingFormat.Single,
        boxed(0x3f800000L), boxed(0x00000000L))
      checkCompletion(d, boxed(0x7f800000L), flags = 0x08) // DZ
      request(d, FloatingOperationKind.Sqrt, FloatingFormat.Single,
        boxed(0xbf800000L))
      checkCompletion(d, boxed(0x7fc00000L), flags = 0x10) // NV
      request(d, FloatingOperationKind.Sqrt, FloatingFormat.Double,
        BigInt("4010000000000000", 16))
      checkCompletion(d, BigInt("4000000000000000", 16)) // sqrt(4)=2
    }
  }

  it should "canonicalize unboxed and signaling F32 NaNs while retaining flags" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      request(d, FloatingOperationKind.Add, FloatingFormat.Single,
        BigInt("3f800000", 16), boxed(0x3f800000L))
      checkCompletion(d, boxed(0x7fc00000L), flags = 0)
      request(d, FloatingOperationKind.Mul, FloatingFormat.Single,
        boxed(0x7f800001L), boxed(0x3f800000L))
      checkCompletion(d, boxed(0x7fc00000L), flags = 0x10)
    }
  }

  it should "round FP to integer and convert exact integer and F64 to F32" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      request(d, FloatingOperationKind.ConvertToInt, FloatingFormat.Double,
        BigInt("400d99999999999a", 16), rm = 1, toInteger = true)
      checkCompletion(d, 3, flags = 0x01, toInteger = true) // trunc(3.7)
      request(d, FloatingOperationKind.ConvertFromInt, FloatingFormat.Double,
        BigInt(0), intLhs = BigInt(42), integerWidth = false)
      checkCompletion(d, BigInt("4045000000000000", 16)) // 42.0
      request(d, FloatingOperationKind.ConvertFormat, FloatingFormat.Single,
        BigInt("3ff0000000000000", 16), sourceFormat = FloatingFormat.Double)
      checkCompletion(d, boxed(0x3f800000L))
      // FCVT.S.D is a single rounding operation, not a host-Double cast.
      // 1+2^-24 is halfway between adjacent F32 encodings.
      val tieDouble = BigInt("3ff0000010000000", 16)
      request(d, FloatingOperationKind.ConvertFormat, FloatingFormat.Single,
        tieDouble, sourceFormat = FloatingFormat.Double, rm = 0)
      checkCompletion(d, boxed(0x3f800000L), flags = 0x01)
      request(d, FloatingOperationKind.ConvertFormat, FloatingFormat.Single,
        tieDouble, sourceFormat = FloatingFormat.Double, rm = 3)
      checkCompletion(d, boxed(0x3f800001L), flags = 0x01)
    }
  }

  it should "kill an outstanding iterative operation without leaking its old token" in {
    simulate(new ProductFpArithmetic(geometry)) { d =>
      init(d)
      request(d, FloatingOperationKind.Div, FloatingFormat.Double,
        BigInt("3ff0000000000000", 16), BigInt("4008000000000000", 16), token = 2)
      d.io.flush.poke(true.B)
      d.clock.step()
      d.io.flush.poke(false.B)
      // Flush cancels the architectural result even when HardFloat's
      // internal iterative pipeline must finish/drain before accepting more.
      var drained = d.io.request.ready.peek().litToBoolean
      var cycles = 0
      while (!drained && cycles < 400) {
        d.io.response.valid.expect(false.B)
        d.clock.step()
        drained = d.io.request.ready.peek().litToBoolean
        cycles += 1
      }
      drained shouldBe true
      d.io.response.valid.expect(false.B)
      request(d, FloatingOperationKind.Add, FloatingFormat.Double,
        BigInt("3ff0000000000000", 16), BigInt("3ff0000000000000", 16), token = 5)
      checkCompletion(d, BigInt("4000000000000000", 16), token = 5)
    }
  }
}
