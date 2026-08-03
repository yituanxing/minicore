package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.AtomicReservation

class AtomicReservationSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AtomicReservation"

  private def idle(dut: AtomicReservation): Unit = {
    dut.io.lrComplete.poke(false.B)
    dut.io.lrAddress.poke(0.U)
    dut.io.lrBytes.poke(0.U)
    dut.io.scAttempt.poke(false.B)
    dut.io.scAddress.poke(0.U)
    dut.io.scBytes.poke(0.U)
    dut.io.localStoreAttempt.poke(false.B)
    dut.io.clear.poke(false.B)
  }

  it should "succeed exactly once for a matching SC" in {
    simulate(new AtomicReservation(64)) { dut =>
      idle(dut)
      dut.io.valid.expect(false.B)

      dut.io.lrComplete.poke(true.B)
      dut.io.lrAddress.poke("h80001000".U)
      dut.io.lrBytes.poke(8.U)
      dut.clock.step()

      idle(dut)
      dut.io.valid.expect(true.B)
      dut.io.address.expect("h80001000".U)
      dut.io.bytes.expect(8.U)

      dut.io.scAttempt.poke(true.B)
      dut.io.scAddress.poke("h80001000".U)
      dut.io.scBytes.poke(8.U)
      dut.io.scSuccess.expect(true.B)
      dut.clock.step()

      idle(dut)
      dut.io.valid.expect(false.B)
      dut.io.scAttempt.poke(true.B)
      dut.io.scAddress.poke("h80001000".U)
      dut.io.scBytes.poke(8.U)
      dut.io.scSuccess.expect(false.B)
    }
  }

  it should "fail and consume a mismatched address or width" in {
    simulate(new AtomicReservation(64)) { dut =>
      idle(dut)
      dut.io.lrComplete.poke(true.B)
      dut.io.lrAddress.poke("h80002000".U)
      dut.io.lrBytes.poke(4.U)
      dut.clock.step()

      idle(dut)
      dut.io.scAttempt.poke(true.B)
      dut.io.scAddress.poke("h80002008".U)
      dut.io.scBytes.poke(4.U)
      dut.io.scSuccess.expect(false.B)
      dut.clock.step()
      dut.io.valid.expect(false.B)

      idle(dut)
      dut.io.lrComplete.poke(true.B)
      dut.io.lrAddress.poke("h80002000".U)
      dut.io.lrBytes.poke(4.U)
      dut.clock.step()

      idle(dut)
      dut.io.scAttempt.poke(true.B)
      dut.io.scAddress.poke("h80002000".U)
      dut.io.scBytes.poke(8.U)
      dut.io.scSuccess.expect(false.B)
      dut.clock.step()
      dut.io.valid.expect(false.B)
    }
  }

  it should "invalidate conservatively on any local store attempt" in {
    simulate(new AtomicReservation(64)) { dut =>
      idle(dut)
      dut.io.lrComplete.poke(true.B)
      dut.io.lrAddress.poke("h80003000".U)
      dut.io.lrBytes.poke(8.U)
      dut.clock.step()

      idle(dut)
      dut.io.localStoreAttempt.poke(true.B)
      dut.clock.step()

      idle(dut)
      dut.io.valid.expect(false.B)
      dut.io.scAttempt.poke(true.B)
      dut.io.scAddress.poke("h80003000".U)
      dut.io.scBytes.poke(8.U)
      dut.io.scSuccess.expect(false.B)
    }
  }

  it should "clear on a trap or context boundary" in {
    simulate(new AtomicReservation(64)) { dut =>
      idle(dut)
      dut.io.lrComplete.poke(true.B)
      dut.io.lrAddress.poke("h80004000".U)
      dut.io.lrBytes.poke(8.U)
      dut.clock.step()

      idle(dut)
      dut.io.clear.poke(true.B)
      dut.clock.step()

      idle(dut)
      dut.io.valid.expect(false.B)
    }
  }

  it should "give invalidation priority over a simultaneous LR completion" in {
    simulate(new AtomicReservation(64)) { dut =>
      idle(dut)
      dut.io.lrComplete.poke(true.B)
      dut.io.lrAddress.poke("h80005000".U)
      dut.io.lrBytes.poke(8.U)
      dut.io.clear.poke(true.B)
      dut.clock.step()

      idle(dut)
      dut.io.valid.expect(false.B)
    }
  }
}
