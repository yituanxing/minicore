package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyBareCore
import aethercore.memory.AetherMemOp

/** Regression for the real OpenSBI fdt_size_cells() frontier.
  *
  * A four-entry ROB with a two-bit generation repeats the same RobToken after
  * sixteen retired instructions. The once-only memory issue latch must be
  * scoped to the observed head lifetime, not to an indefinitely remembered
  * numeric token.
  */
trait V2F7GenerationWrapChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val Config = CoreProfiles.rv64imsuSv39PmpSoftware
  private val Reset = Config.platform.resetVector
  private val Nop = BigInt("00000013", 16)
  private val DataAddress = BigInt("100", 16)

  behavior of "AetherCore v2 bounded-generation issue ownership"

  it should "reissue a new load whose RobToken numerically repeats sixteen instructions later" in {
    simulate(new TinyBareCore(Config, PageTableGeometry.Sv39)) { dut =>
      dut.io.imem.inst.poke(Nop.U)
      dut.io.imem.fault.poke(false.B)
      dut.io.time.foreach(_.poke(0.U))
      dut.io.ptw.ready.poke(false.B)
      dut.io.ptw.rdata.poke(0.U)
      dut.io.ptw.fault.poke(false.B)

      dut.io.resolvedAttributes.cacheable.poke(true.B)
      dut.io.resolvedAttributes.idempotent.poke(true.B)
      dut.io.resolvedAttributes.sideEffecting.poke(false.B)
      dut.io.resolvedAttributes.ordered.poke(false.B)
      dut.io.resolvedAttributes.executable.poke(false.B)
      dut.io.resolvedAttributes.supportsAtomic.poke(false.B)
      dut.io.resolvedAttributes.supportsPartial.poke(true.B)

      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.txnId.poke(0.U)
      dut.io.memoryResponse.bits.rdata.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)

      // First load at instruction index 1. The second load is exactly sixteen
      // instructions later, so its 4-entry/2-bit-generation RobToken repeats.
      val program = (0 until 18).map { index =>
        val inst = index match {
          case 0  => BigInt("10000093", 16) // addi x1,x0,0x100
          case 1  => BigInt("0000a183", 16) // lw x3,0(x1)
          case 17 => BigInt("0000a283", 16) // lw x5,0(x1)
          case _  => Nop
        }
        (Reset + index * 4) -> inst
      }.toMap

      var pendingTxn: Option[BigInt] = None
      var firstRequestCycle = -1
      var reads = 0
      var secondLoadCommitted = false
      var sawEmptyAfterFirstRequest = false
      var cycles = 0

      while (cycles < 500 && !secondLoadCommitted) {
        val fetchAddress = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchAddress, Nop).U)

        // Delay the first response long enough to fill the tiny ROB. This keeps
        // head.valid continuously asserted across the 16-instruction interval,
        // reproducing the software condition that exposed the stale latch.
        val mayRespond = pendingTxn.nonEmpty &&
          (reads > 1 || cycles - firstRequestCycle >= 5)
        if (mayRespond) {
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.txnId.poke(pendingTxn.get.U)
          dut.io.memoryResponse.bits.rdata.poke(42.U)
        } else {
          dut.io.memoryResponse.valid.poke(false.B)
          dut.io.memoryResponse.bits.txnId.poke(0.U)
          dut.io.memoryResponse.bits.rdata.poke(0.U)
        }

        val responseFire = mayRespond && dut.io.memoryResponse.ready.peek().litToBoolean
        val requestFire = dut.io.memoryRequest.valid.peek().litToBoolean &&
          dut.io.memoryRequest.ready.peek().litToBoolean

        var newTxn: Option[BigInt] = None
        if (requestFire) {
          dut.io.memoryRequest.bits.op.peek().litValue shouldBe AetherMemOp.Read.litValue
          dut.io.memoryRequest.bits.paddr.expect(DataAddress.U)
          reads += 1
          if (reads == 1) firstRequestCycle = cycles
          newTxn = Some(dut.io.memoryRequest.bits.txnId.peek().litValue)
        }

        if (firstRequestCycle >= 0 && !secondLoadCommitted && dut.io.occupancy.peek().litValue == 0) {
          sawEmptyAfterFirstRequest = true
        }

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == Reset + 17 * 4) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(5.U)
          dut.io.commit.rdData.expect(42.U)
          secondLoadCommitted = true
        }

        dut.clock.step()
        cycles += 1

        if (responseFire) pendingTxn = None
        if (newTxn.nonEmpty) {
          withClue("blocking LSU accepted a new request with a response still pending: ") {
            pendingTxn shouldBe None
          }
          pendingTxn = newTxn
        }
      }

      withClue("the test accidentally allowed head.valid to become empty, masking the original bug: ") {
        sawEmptyAfterFirstRequest shouldBe false
      }
      withClue("the numerically repeated RobToken was incorrectly suppressed as already-issued: ") {
        secondLoadCommitted shouldBe true
      }
      reads shouldBe 2
    }
  }
}
