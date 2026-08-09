package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}

class MachineIdentitySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "machine identity CSRs"

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.time.foreach(_.poke(0.U))
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
    dut.io.trapReturnSupervisor.poke(false.B)
  }

  private def expectReadOnlyZero(dut: MachineCsrFile, address: Int): Unit = {
    dut.io.readAddr.poke(address.U)
    dut.io.readData.expect(0.U)
    dut.io.readImplemented.expect(true.B)
    dut.io.readWritable.expect(false.B)
  }

  it should "expose the mandatory machine identity registers as read-only zero IDs" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imasuSv32Software.isa)) { dut =>
      initialize(dut)

      expectReadOnlyZero(dut, MachineCsrAddress.Mvendorid)
      expectReadOnlyZero(dut, MachineCsrAddress.Marchid)
      expectReadOnlyZero(dut, MachineCsrAddress.Mimpid)
      expectReadOnlyZero(dut, MachineCsrAddress.Mhartid)
    }
  }

  it should "retain the same machine identity read contract at RV64" in {
    simulate(new MachineCsrFile(CoreProfiles.rv64imCurrent.isa)) { dut =>
      initialize(dut)

      expectReadOnlyZero(dut, MachineCsrAddress.Mvendorid)
      expectReadOnlyZero(dut, MachineCsrAddress.Marchid)
      expectReadOnlyZero(dut, MachineCsrAddress.Mimpid)
      expectReadOnlyZero(dut, MachineCsrAddress.Mhartid)
    }
  }
}
