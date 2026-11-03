package aethercore

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.product._
import aethercore.core.semantic.SemanticDecode

private class ProductIntegerCoreTestHarness extends Module {
  private val cfg = ProductCoreProfiles.smallEngineering
  private val I = log2Ceil(cfg.geometry.robEntries)
  private val P = log2Ceil(cfg.geometry.integerPhysicalRegisters)
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val ready = Output(Bool())
    val pc = Input(UInt(64.W))
    val inst = Input(UInt(32.W))
    val prediction = Input(UInt(64.W))
    val retire = Decoupled(new ProductRetirement(64, I, P, 16))
    val redirect = Valid(new ProductRedirect(64, I, 16))
    val occupancy = Output(UInt(log2Ceil(cfg.geometry.robEntries + 1).W))
    val unsupported = Output(Bool())
    val mUnitNeeded = Output(Bool())
  })
  val decoder = Module(new SemanticDecode(cfg.software.isa))
  decoder.io.pc := io.pc
  decoder.io.inst := io.inst
  decoder.io.rawInst := io.inst
  decoder.io.instBytes := 4.U
  decoder.io.fetchException := 0.U.asTypeOf(decoder.io.fetchException)
  val core = Module(new ProductSmallIntegerCore(cfg))
  core.io.dispatch.valid := io.valid
  core.io.dispatch.bits.decoded := decoder.io.decoded
  core.io.dispatch.bits.predictedNextPc := io.prediction
  io.ready := core.io.dispatch.ready
  io.retire.valid := core.io.retire.valid
  io.retire.bits := core.io.retire.bits
  core.io.retire.ready := io.retire.ready
  io.redirect := core.io.redirect
  io.occupancy := core.io.occupancy
  io.unsupported := core.io.unsupported
  io.mUnitNeeded := core.io.mUnitNeeded
}

class ProductSmallIntegerCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Actual SmallOoO integer issue and executable branch/FU round trip"

  private def init(d: ProductIntegerCoreTestHarness): Unit = {
    d.io.pc.poke("h80000000".U)
    d.io.inst.poke("h00000013".U)
    d.io.prediction.poke("h80000004".U)
    d.io.valid.poke(false.B)
    d.io.retire.ready.poke(true.B)
    d.clock.step()
  }

  private def dispatch(d: ProductIntegerCoreTestHarness, pc: Long, inst: Long): Unit = {
    d.io.pc.poke(pc.U)
    d.io.inst.poke(inst.U)
    d.io.prediction.poke((pc + 4).U)
    d.io.valid.poke(true.B)
    d.io.ready.expect(true.B)
    d.clock.step()
    d.io.valid.poke(false.B)
  }

  it should "actually execute ADDI dependency and retire in program order" in {
    simulate(new ProductIntegerCoreTestHarness) { d =>
      init(d)
      dispatch(d, 0x80000000L, 0x00500093L) // addi x1,x0,5
      dispatch(d, 0x80000004L, 0x00108113L) // addi x2,x1,1
      var committed = List.empty[(BigInt, BigInt)]
      for (_ <- 0 until 18) {
        if (d.io.retire.valid.peek().litToBoolean) {
          d.io.retire.bits.exception.valid.expect(false.B)
          committed :+= (
            d.io.retire.bits.rd.peek().litValue,
            d.io.retire.bits.value.peek().litValue)
        }
        d.clock.step()
      }
      committed shouldBe List((BigInt(1), BigInt(5)), (BigInt(2), BigInt(6)))
      d.io.occupancy.expect(0.U)
    }
  }

  it should "perform a real BEQ redirect and squash the wrong-path ADDI" in {
    simulate(new ProductIntegerCoreTestHarness) { d =>
      init(d)
      dispatch(d, 0x80000000L, 0x00000463L) // BEQ x0,x0,+8
      dispatch(d, 0x80000004L, 0x00700113L) // wrong-path ADDI x2,x0,7
      var redirects = List.empty[BigInt]
      var writes = List.empty[BigInt]
      for (_ <- 0 until 20) {
        if (d.io.redirect.valid.peek().litToBoolean) {
          redirects :+= d.io.redirect.bits.target.peek().litValue
        }
        if (d.io.retire.valid.peek().litToBoolean) {
          if (d.io.retire.bits.wroteRegister.peek().litToBoolean) {
            writes :+= d.io.retire.bits.rd.peek().litValue
          }
        }
        d.clock.step()
      }
      redirects shouldBe List(BigInt("80000008", 16))
      writes shouldBe Nil
      d.io.occupancy.expect(0.U)
    }
  }

  it should "fail closed on M until the separate iterative FU is connected" in {
    simulate(new ProductIntegerCoreTestHarness) { d =>
      init(d)
      dispatch(d, 0x80000000L, 0x0220c1b3L) // div x3,x1,x2
      d.io.mUnitNeeded.expect(true.B)
      d.io.retire.valid.expect(false.B)
      for (_ <- 0 until 4) d.clock.step()
      d.io.occupancy.expect(1.U)
      d.io.retire.valid.expect(false.B)
    }
  }

  it should "reject memory instructions without a phantom retirement" in {
    simulate(new ProductIntegerCoreTestHarness) { d =>
      init(d)
      d.io.inst.poke("h0040a183".U)
      d.io.valid.poke(true.B)
      d.io.unsupported.expect(true.B)
      d.io.ready.expect(false.B)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }
}
