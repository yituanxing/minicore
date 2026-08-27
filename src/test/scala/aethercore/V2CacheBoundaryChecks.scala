package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore

trait V2CacheBoundaryChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val Config = CoreProfiles.rv32imasuSv32Software
  private val Geometry = PageTableGeometry.Sv32
  private val Reset = Config.platform.resetVector

  private def initialize(dut: TinyPagedCore): Unit = {
    dut.io.imem.inst.poke("h00000013".U)
    dut.io.imem.fault.poke(false.B)
    dut.io.imemReady.foreach(_.poke(true.B))
    dut.io.time.foreach(_.poke(0.U))

    dut.io.ptw.ready.poke(true.B)
    dut.io.ptw.rdata.poke(0.U)
    dut.io.ptw.fault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(true.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(true.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  behavior of "AetherCore v2 cache boundary"

  it should "hold a translated instruction response until the cache-facing ready seam accepts it" in {
    simulate(new TinyPagedCore(
      Config,
      Geometry,
      enableInstructionBackpressure = true
    )) { dut =>
      initialize(dut)
      dut.io.imemReady.get.poke(false.B)
      dut.io.imem.inst.poke("h00100093".U) // addi x1,x0,1

      var wait = 0
      while (wait < 20 && !dut.io.imem.valid.peek().litToBoolean) {
        dut.io.commit.valid.expect(false.B)
        dut.clock.step()
        wait += 1
      }
      withClue("translated instruction request never reached the stallable imem seam: ") {
        dut.io.imem.valid.peek().litToBoolean shouldBe true
      }

      val heldAddress = dut.io.imem.addr.peek().litValue
      heldAddress shouldBe Reset

      for (_ <- 0 until 5) {
        dut.io.imem.valid.expect(true.B)
        dut.io.imem.addr.expect(heldAddress.U)
        dut.io.frontendPc.expect(Reset.U)
        dut.io.commit.valid.expect(false.B)
        dut.clock.step()
      }

      dut.io.imemReady.get.poke(true.B)

      var retired = false
      var cycles = 0
      while (cycles < 40 && !retired) {
        dut.io.imem.inst.poke(
          (if (dut.io.imem.addr.peek().litValue == Reset) BigInt("00100093", 16)
           else BigInt("00000013", 16)).U
        )
        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == Reset) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(1.U)
          dut.io.commit.rdData.expect(1.U)
          retired = true
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("stalled instruction did not retire after imemReady reopened: ") {
        retired shouldBe true
      }
    }
  }

  it should "emit one precise retire-time FENCE.I pulse before subsequent fetch resumes" in {
    simulate(new TinyPagedCore(
      Config,
      Geometry,
      enableInstructionBackpressure = true
    )) { dut =>
      initialize(dut)
      dut.io.imemReady.get.poke(true.B)

      val program = Map(
        Reset -> BigInt("0000100f", 16),     // fence.i
        (Reset + 4) -> BigInt("00200113", 16) // addi x2,x0,2
      )

      var fencePulses = 0
      var secondRetired = false
      var cycles = 0
      while (cycles < 80 && !secondRetired) {
        val addr = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(addr, BigInt("00000013", 16)).U)

        if (dut.io.instructionFence.peek().litToBoolean) {
          fencePulses += 1
          dut.io.commit.valid.expect(true.B)
          dut.io.commit.pc.expect(Reset.U)
          // Serialized frontend remains closed on the retirement cycle. The
          // external I-cache can invalidate at this edge before the next fetch.
          dut.io.imem.valid.expect(false.B)
        }

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == Reset + 4) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(2.U)
          dut.io.commit.rdData.expect(2.U)
          secondRetired = true
        }

        dut.clock.step()
        cycles += 1
      }

      fencePulses shouldBe 1
      withClue("instruction after FENCE.I never retired: ") {
        secondRetired shouldBe true
      }
    }
  }
}

class V2CacheBoundarySpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2CacheBoundaryChecks
