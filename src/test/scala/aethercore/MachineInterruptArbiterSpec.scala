package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.MachineInterruptArbiter

class MachineInterruptArbiterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineInterruptArbiter"

  private def initialize(dut: MachineInterruptArbiter): Unit = {
    dut.io.rawTimerPending.poke(false.B)
    dut.io.rawExternalPending.poke(false.B)
    dut.io.mie.poke(0.U)
    dut.io.mstatusMie.poke(false.B)
    dut.io.currentPrivilege.poke(3.U)
  }

  it should "wake WFI from raw pending state without taking a masked trap" in {
    simulate(new MachineInterruptArbiter(32)) { dut =>
      initialize(dut)

      dut.io.rawExternalPending.poke(true.B)
      dut.io.wakeRequest.expect(true.B)
      dut.io.takeInterrupt.expect(false.B)
      dut.io.mip.expect(BigInt("00000800", 16).U)

      dut.io.rawExternalPending.poke(false.B)
      dut.io.rawTimerPending.poke(true.B)
      dut.io.wakeRequest.expect(true.B)
      dut.io.takeInterrupt.expect(false.B)
      dut.io.mip.expect(BigInt("00000080", 16).U)
    }
  }

  it should "require the matching mie bit and global enable in M mode" in {
    simulate(new MachineInterruptArbiter(32)) { dut =>
      initialize(dut)
      dut.io.rawExternalPending.poke(true.B)
      dut.io.mie.poke(BigInt("00000800", 16).U)
      dut.io.takeInterrupt.expect(false.B)

      dut.io.mstatusMie.poke(true.B)
      dut.io.takeInterrupt.expect(true.B)
      dut.io.externalQualified.expect(true.B)
      dut.io.cause.expect(BigInt("8000000b", 16).U)
    }
  }

  it should "treat lower privilege as globally enabled" in {
    simulate(new MachineInterruptArbiter(32)) { dut =>
      initialize(dut)
      dut.io.currentPrivilege.poke(0.U)
      dut.io.rawTimerPending.poke(true.B)
      dut.io.mie.poke(BigInt("00000080", 16).U)
      dut.io.takeInterrupt.expect(true.B)
      dut.io.timerQualified.expect(true.B)
      dut.io.cause.expect(BigInt("80000007", 16).U)
    }
  }

  it should "prioritize machine external interrupt over machine timer interrupt" in {
    simulate(new MachineInterruptArbiter(64)) { dut =>
      initialize(dut)
      dut.io.rawTimerPending.poke(true.B)
      dut.io.rawExternalPending.poke(true.B)
      dut.io.mie.poke(BigInt("0000000000000880", 16).U)
      dut.io.mstatusMie.poke(true.B)

      dut.io.takeInterrupt.expect(true.B)
      dut.io.externalQualified.expect(true.B)
      dut.io.timerQualified.expect(true.B)
      dut.io.cause.expect(BigInt("800000000000000b", 16).U)
      dut.io.mip.expect(BigInt("0000000000000880", 16).U)
    }
  }
}
