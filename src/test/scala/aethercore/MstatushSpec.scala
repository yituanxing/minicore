package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}

class MstatushSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV32 mstatush"

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
    dut.io.trapReturnSupervisor.poke(false.B)
  }

  it should "implement mandatory RV32 mstatush as writable WARL-zero" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initialize(dut)

      dut.io.readAddr.poke(MachineCsrAddress.Mstatush.U)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)
      dut.io.readData.expect(0.U)

      dut.io.writeAddr.poke(MachineCsrAddress.Mstatush.U)
      dut.io.writeData.poke("hffffffff".U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)

      dut.io.readAddr.poke(MachineCsrAddress.Mstatush.U)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)
      dut.io.readData.expect(0.U)
    }
  }

  it should "leave mstatush unimplemented for RV64" in {
    simulate(new MachineCsrFile(CoreProfiles.rv64imCurrent.isa)) { dut =>
      initialize(dut)
      dut.io.readAddr.poke(MachineCsrAddress.Mstatush.U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(0.U)
    }
  }
}
