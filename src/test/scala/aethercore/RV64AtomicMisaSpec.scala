package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}

class RV64AtomicMisaSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64A misa capability discovery"

  it should "advertise exactly RV64 IMA plus S and U for the bounded Sv39 profile" in {
    val isa = CoreProfiles.rv64imasuSv39PmpSoftware.isa

    simulate(new MachineCsrFile(isa, CoreProfiles.rv64imasuSv39PmpSoftware.platform.paddrBits, false, false)) { dut =>
      dut.io.readAddr.poke(MachineCsrAddress.Misa.U)
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

      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(BigInt("8000000000141101", 16).U(64.W))
    }
  }
}
