package aethercore

import aethercore.common.MachineExceptionCode
import aethercore.core.product._
import aethercore.core.semantic.{FloatingFormat, FloatingOperationKind}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** IEEE FP execution regression covering bit-level operations and the
  * independently owned, HardFloat-backed arithmetic/rounding lane.
  */
class ProductFpUnitSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Small Product FP bit-level execution unit"

  private val profile = ProductCoreProfiles.smallEngineering
  private val Box = BigInt("ffffffff00000000", 16)

  private def init(d: ProductFpUnit): Unit = {
    d.io.request.valid.poke(false.B)
    d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
    d.io.response.ready.poke(false.B)
    d.io.dynamicRoundingMode.poke(0.U)
    d.io.kill.valid.poke(false.B)
    d.io.kill.bits.poke(0.U.asTypeOf(d.io.kill.bits))
    d.clock.step()
  }

  private def run(
      d: ProductFpUnit,
      kind: FloatingOperationKind.Type,
      format: FloatingFormat.Type,
      a: BigInt,
      b: BigInt = BigInt(0),
      subop: Int = 0,
      roundingMode: Int = 0,
      intLhs: BigInt = BigInt(0),
      expected: BigInt,
      fflags: Int = 0,
      file: ProductRegisterFile.Type = ProductRegisterFile.Floating,
      illegal: Boolean = false
  ): Unit = {
    d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
    d.io.request.bits.token.index.poke(2.U)
    d.io.request.bits.token.generation.poke(1.U)
    d.io.request.bits.destination.index.poke(7.U)
    d.io.request.bits.destination.generation.poke(1.U)
    d.io.request.bits.destinationFile.poke(file)
    d.io.request.bits.hasDestination.poke(true.B)
    d.io.request.bits.floating.kind.poke(kind)
    d.io.request.bits.floating.format.poke(format)
    d.io.request.bits.floating.subop.poke(subop.U)
    d.io.request.bits.floating.roundingMode.poke(roundingMode.U)
    d.io.request.bits.fpLhs.poke(a.U)
    d.io.request.bits.fpRhs.poke(b.U)
    d.io.request.bits.intLhs.poke(intLhs.U)
    d.io.request.bits.rawInst.poke("h53".U)
    d.io.request.valid.poke(true.B)
    d.io.request.ready.expect(true.B)
    d.clock.step()
    d.io.request.valid.poke(false.B)
    // Iterative IEEE div/sqrt and other qualified arithmetic need not
    // complete one cycle after issue; an ROB completion is still mandatory.
    var cycles = 0
    while (!d.io.response.valid.peek().litToBoolean && cycles < 400) {
      d.clock.step()
      cycles += 1
    }
    d.io.response.valid.expect(true.B)
    d.io.response.bits.token.index.expect(2.U)
    d.io.response.bits.token.generation.expect(1.U)
    d.io.response.bits.exception.valid.expect(illegal.B)
    if (illegal) {
      d.io.response.bits.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      d.io.response.bits.exception.value.expect("h53".U)
      // Even a faulting operation retains the same destination lease; the
      // ROB, not the FU, suppresses speculative PRF writeback on exceptions.
      d.io.response.bits.hasValue.expect(true.B)
    } else {
      d.io.response.bits.value.expect(expected.U)
      d.io.response.bits.fflags.expect(fflags.U)
      d.io.response.bits.destinationFile.expect(file)
    }
    d.io.request.ready.expect(false.B)
    d.clock.step(2)
    d.io.response.valid.expect(true.B)
    d.io.response.ready.poke(true.B)
    d.clock.step()
    d.io.response.ready.poke(false.B)
    d.io.response.valid.expect(false.B)
  }

  it should "perform boxed sign injection and canonicalize malformed F32 input boxes" in {
    simulate(new ProductFpUnit(profile.geometry)) { d =>
      init(d)
      val posOne = Box | BigInt("3f800000", 16)
      val negHalf = Box | BigInt("bf000000", 16)
      run(d, FloatingOperationKind.Sign, FloatingFormat.Single,
        posOne, negHalf, expected = Box | BigInt("bf800000", 16))
      run(d, FloatingOperationKind.Sign, FloatingFormat.Single,
        posOne, negHalf, subop = 1, expected = posOne)
      run(d, FloatingOperationKind.Sign, FloatingFormat.Single,
        posOne, negHalf, subop = 2, expected = Box | BigInt("bf800000", 16))
      run(d, FloatingOperationKind.Sign, FloatingFormat.Single,
        BigInt("3f800000", 16), negHalf,
        expected = Box | BigInt("ffc00000", 16))
    }
  }

  it should "obey FMIN/FMAX NaN and signed-zero rules with exact NV flags" in {
    simulate(new ProductFpUnit(profile.geometry)) { d =>
      init(d)
      val negZero = Box | BigInt("80000000", 16)
      val posZero = Box
      val quietNan = Box | BigInt("7fc00000", 16)
      val signalingNan = Box | BigInt("7f800001", 16)
      val two = Box | BigInt("40000000", 16)
      run(d, FloatingOperationKind.MinMax, FloatingFormat.Single,
        posZero, negZero, expected = negZero)
      run(d, FloatingOperationKind.MinMax, FloatingFormat.Single,
        negZero, posZero, subop = 1, expected = posZero)
      run(d, FloatingOperationKind.MinMax, FloatingFormat.Single,
        quietNan, two, expected = two)
      run(d, FloatingOperationKind.MinMax, FloatingFormat.Single,
        signalingNan, two, expected = two, fflags = 0x10)
      run(d, FloatingOperationKind.MinMax, FloatingFormat.Single,
        quietNan, signalingNan, expected = quietNan, fflags = 0x10)
    }
  }

  it should "implement FEQ quiet-NaN versus signaling-NaN invalid semantics" in {
    simulate(new ProductFpUnit(profile.geometry)) { d =>
      init(d)
      val quiet = Box | BigInt("7fc00000", 16)
      val signaling = Box | BigInt("7f800001", 16)
      val one = Box | BigInt("3f800000", 16)
      val negZero = Box | BigInt("80000000", 16)
      run(d, FloatingOperationKind.Compare, FloatingFormat.Single,
        quiet, one, subop = 2, expected = 0, file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.Compare, FloatingFormat.Single,
        signaling, one, subop = 2, expected = 0, fflags = 0x10,
        file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.Compare, FloatingFormat.Single,
        quiet, one, subop = 1, expected = 0, fflags = 0x10,
        file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.Compare, FloatingFormat.Single,
        one, one, subop = 2, expected = 1, file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.Compare, FloatingFormat.Single,
        negZero, Box, subop = 2, expected = 1, file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.Compare, FloatingFormat.Single,
        negZero, one, subop = 1, expected = 1, file = ProductRegisterFile.Integer)
    }
  }

  it should "preserve full IEEE double precision classification and signed zeros" in {
    simulate(new ProductFpUnit(profile.geometry)) { d =>
      init(d)
      val positiveOne = BigInt("3ff0000000000000", 16)
      val negativeTwo = BigInt("c000000000000000", 16)
      val positiveTwo = BigInt("4000000000000000", 16)
      val signaling = BigInt("7ff0000000000001", 16)
      run(d, FloatingOperationKind.Sign, FloatingFormat.Double,
        positiveOne, negativeTwo, expected = BigInt("bff0000000000000", 16))
      run(d, FloatingOperationKind.MinMax, FloatingFormat.Double,
        signaling, positiveTwo, expected = positiveTwo, fflags = 0x10)
      run(d, FloatingOperationKind.Classify, FloatingFormat.Double,
        BigInt("7ff0000000000000", 16), expected = 128,
        file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.Classify, FloatingFormat.Double,
        signaling, expected = 256, file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.Compare, FloatingFormat.Double,
        negativeTwo, positiveOne, subop = 1, expected = 1,
        file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.MinMax, FloatingFormat.Double,
        BigInt("8000000000000000", 16), BigInt(0), subop = 1,
        expected = 0)
    }
  }

  it should "classify NaN boxes and preserve raw FMV transfer bits" in {
    simulate(new ProductFpUnit(profile.geometry)) { d =>
      init(d)
      run(d, FloatingOperationKind.Classify, FloatingFormat.Single,
        Box | BigInt("80000001", 16), expected = 4,
        file = ProductRegisterFile.Integer) // negative subnormal
      run(d, FloatingOperationKind.Classify, FloatingFormat.Single,
        BigInt("3f800000", 16), expected = 512,
        file = ProductRegisterFile.Integer) // malformed box -> qNaN
      run(d, FloatingOperationKind.MoveToInt, FloatingFormat.Single,
        BigInt("deadbeef", 16), expected = BigInt("ffffffffdeadbeef", 16),
        file = ProductRegisterFile.Integer)
      run(d, FloatingOperationKind.MoveFromInt, FloatingFormat.Single,
        BigInt(0), intLhs = BigInt("80000000", 16),
        expected = Box | BigInt("80000000", 16))
      run(d, FloatingOperationKind.MoveToInt, FloatingFormat.Double,
        BigInt("c008000000000000", 16), expected = BigInt("c008000000000000", 16),
        file = ProductRegisterFile.Integer)
    }
  }

  it should "route IEEE arithmetic to the qualified engine without manufacturing results" in {
    simulate(new ProductFpUnit(profile.geometry)) { d =>
      init(d)
      run(d, FloatingOperationKind.Add, FloatingFormat.Double,
        BigInt("3ff0000000000000", 16), BigInt("4000000000000000", 16),
        expected = BigInt("4008000000000000", 16)) // 1+2=3
      run(d, FloatingOperationKind.Mul, FloatingFormat.Single,
        Box | BigInt("40000000", 16), Box | BigInt("40400000", 16),
        expected = Box | BigInt("40c00000", 16)) // 2*3=6
      run(d, FloatingOperationKind.Add, FloatingFormat.Double,
        BigInt("3ff0000000000000", 16), BigInt("3ff0000000000000", 16),
        roundingMode = 5, expected = 0, illegal = true)
    }
  }

  it should "preserve an older divide across an unrelated younger recovery token" in {
    simulate(new ProductFpUnit(profile.geometry)) { d =>
      init(d)
      d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.request.bits.token.index.poke(2.U)
      d.io.request.bits.token.generation.poke(6.U)
      d.io.request.bits.destination.index.poke(33.U)
      d.io.request.bits.destination.generation.poke(4.U)
      d.io.request.bits.destinationFile.poke(ProductRegisterFile.Floating)
      d.io.request.bits.hasDestination.poke(true.B)
      d.io.request.bits.floating.kind.poke(FloatingOperationKind.Div)
      d.io.request.bits.floating.format.poke(FloatingFormat.Double)
      d.io.request.bits.fpLhs.poke("h3ff0000000000000".U)
      d.io.request.bits.fpRhs.poke("h4008000000000000".U)
      d.io.request.valid.poke(true.B)
      d.io.request.ready.expect(true.B)
      d.clock.step()
      d.io.request.valid.poke(false.B)

      // Recovery of a different, younger ROB lifetime must not kill this
      // outstanding divide or release its FP issue slot prematurely.
      d.io.kill.valid.poke(true.B)
      d.io.kill.bits.index.poke(3.U)
      d.io.kill.bits.generation.poke(6.U)
      d.clock.step()
      d.io.kill.valid.poke(false.B)
      var cycles = 0
      while (!d.io.response.valid.peek().litToBoolean && cycles < 400) {
        d.clock.step()
        cycles += 1
      }
      d.io.response.valid.expect(true.B)
      d.io.response.bits.token.index.expect(2.U)
      d.io.response.bits.token.generation.expect(6.U)
      d.io.response.bits.value.expect("h3fd5555555555555".U)
      d.io.response.bits.fflags.expect(1.U) // inexact 1/3

      // Same index, *different generation* is also an unrelated lifetime.
      d.io.kill.valid.poke(true.B)
      d.io.kill.bits.index.poke(2.U)
      d.io.kill.bits.generation.poke(7.U)
      d.clock.step()
      d.io.response.valid.expect(true.B)
      d.io.kill.valid.poke(false.B)
      d.io.response.ready.poke(true.B)
      d.clock.step()
      d.io.response.valid.expect(false.B)
    }
  }
}
