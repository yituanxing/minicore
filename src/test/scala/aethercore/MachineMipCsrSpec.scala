package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}

class MachineMipCsrSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineCsrFile mip semantics"

  it should "accept software writes while preserving hardware-driven pending bits" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeAddr.poke(0.U)
      dut.io.writeData.poke(0.U)
      dut.io.timerInterrupt.poke(false.B)
      dut.io.trapEnter.poke(false.B)
      dut.io.trapPc.poke(0.U)
      dut.io.trapCause.poke(0.U)
      dut.io.trapValue.poke(0.U)
      dut.io.trapReturn.poke(false.B)

      dut.io.readAddr.poke(MachineCsrAddress.Mip.U)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)
      dut.io.readData.expect(0.U)

      dut.io.writeAddr.poke(MachineCsrAddress.Mip.U)
      dut.io.writeData.poke("hffffffff".U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.readData.expect(0.U)

      dut.io.timerInterrupt.poke(true.B)
      dut.io.readData.expect("h00000080".U)

      dut.io.writeData.poke(0.U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.readData.expect("h00000080".U)
    }
  }
}
