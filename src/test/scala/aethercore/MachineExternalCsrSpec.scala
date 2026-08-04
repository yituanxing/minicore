package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}

class MachineExternalCsrSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineCsrFile machine external interrupt state"

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.externalInterrupt.get.poke(false.B)
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
  }

  private def write(dut: MachineCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  private def read(dut: MachineCsrFile, address: Int): BigInt = {
    dut.io.readAddr.poke(address.U)
    dut.io.readData.peek().litValue
  }

  it should "expose MEIP in mip and qualify MEIE through mstatus.MIE" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa, withMachineExternalInterrupt = true)) { dut =>
      initialize(dut)

      dut.io.externalInterrupt.get.poke(true.B)
      read(dut, MachineCsrAddress.Mip) shouldBe BigInt("00000800", 16)
      dut.io.machineExternalInterrupt.get.expect(false.B)

      write(dut, MachineCsrAddress.Mie, BigInt("00000880", 16))
      read(dut, MachineCsrAddress.Mie) shouldBe BigInt("00000880", 16)
      dut.io.machineExternalInterrupt.get.expect(false.B)

      write(dut, MachineCsrAddress.Mstatus, BigInt("00000008", 16))
      dut.io.machineExternalInterrupt.get.expect(true.B)
      dut.io.machineTimerInterrupt.expect(false.B)

      dut.io.timerInterrupt.poke(true.B)
      read(dut, MachineCsrAddress.Mip) shouldBe BigInt("00000880", 16)
      dut.io.machineExternalInterrupt.get.expect(true.B)
      dut.io.machineTimerInterrupt.expect(true.B)

      write(dut, MachineCsrAddress.Mie, BigInt("00000080", 16))
      dut.io.machineExternalInterrupt.get.expect(false.B)
      dut.io.machineTimerInterrupt.expect(true.B)
    }
  }

  it should "apply a same-boundary MEIE write before external interrupt qualification" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa, withMachineExternalInterrupt = true)) { dut =>
      initialize(dut)
      write(dut, MachineCsrAddress.Mstatus, BigInt("00000008", 16))
      dut.io.externalInterrupt.get.poke(true.B)

      dut.io.writeAddr.poke(MachineCsrAddress.Mie.U)
      dut.io.writeData.poke(BigInt("00000800", 16).U)
      dut.io.writeEnable.poke(true.B)
      dut.io.machineExternalInterrupt.get.expect(true.B)

      dut.io.trapEnter.poke(true.B)
      dut.io.trapPc.poke(BigInt("80000103", 16).U)
      dut.io.trapCause.poke(BigInt("8000000b", 16).U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)
      dut.io.writeEnable.poke(false.B)

      read(dut, MachineCsrAddress.Mie) shouldBe BigInt("00000800", 16)
      read(dut, MachineCsrAddress.Mcause) shouldBe BigInt("8000000b", 16)
      read(dut, MachineCsrAddress.Mepc) shouldBe BigInt("80000100", 16)
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00001880", 16)
    }
  }
}
