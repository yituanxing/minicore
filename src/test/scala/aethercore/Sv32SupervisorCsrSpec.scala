package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreProfiles, IsaConfig}
import aethercore.core.{MachineCsrAddress, MachineCsrBit, MachineCsrFile, SupervisorCsrAddress, Sv32Satp}

class Sv32SupervisorCsrSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineCsrFile Sv32 extension"

  private val sv32Isa = IsaConfig(
    xlen = 32,
    extensions = Set('I', 'M'),
    privilegeModes = Set('M', 'S', 'U'),
    zExtensions = Set("Zicsr"),
    virtualMemoryModes = Set("Sv32")
  )

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

  private def read(dut: MachineCsrFile, address: Int): BigInt = {
    dut.io.readAddr.poke(address.U)
    dut.io.readData.peek().litValue
  }

  private def write(dut: MachineCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  it should "expose satp only for an Sv32-capable Supervisor profile" in {
    simulate(new MachineCsrFile(sv32Isa)) { dut =>
      initialize(dut)

      read(dut, SupervisorCsrAddress.Satp) shouldBe 0
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)
      dut.io.satpTranslationEnabled.expect(false.B)
      dut.io.satpRootPpn.expect(0.U)
      dut.io.satpAsid.expect(0.U)

      write(dut, SupervisorCsrAddress.Satp, BigInt("ffffffff", 16))
      val expected = (BigInt(1) << Sv32Satp.ModeBit) | ((BigInt(1) << Sv32Satp.PpnBits) - 1)
      read(dut, SupervisorCsrAddress.Satp) shouldBe expected
      dut.io.satpTranslationEnabled.expect(true.B)
      dut.io.satpRootPpn.expect(((BigInt(1) << Sv32Satp.PpnBits) - 1).U)
      dut.io.satpAsid.expect(0.U)

      write(dut, SupervisorCsrAddress.Satp, BigInt("00345678", 16))
      read(dut, SupervisorCsrAddress.Satp) shouldBe 0
      dut.io.satpTranslationEnabled.expect(false.B)
    }

    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initialize(dut)
      dut.io.readAddr.poke(SupervisorCsrAddress.Satp.U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(0.U)
      dut.io.satpTranslationEnabled.expect(false.B)
      dut.io.satpRootPpn.expect(0.U)
      dut.io.satpAsid.expect(0.U)
    }
  }

  it should "expose SUM and MXR only at the Sv32 Supervisor boundary" in {
    val vmMask =
      (BigInt(1) << MachineCsrBit.SstatusSie) |
        (BigInt(1) << MachineCsrBit.SstatusSpie) |
        (BigInt(1) << MachineCsrBit.SstatusSpp) |
        (BigInt(1) << MachineCsrBit.SstatusSum) |
        (BigInt(1) << MachineCsrBit.SstatusMxr)

    simulate(new MachineCsrFile(sv32Isa)) { dut =>
      initialize(dut)
      write(dut, SupervisorCsrAddress.Sstatus, BigInt("ffffffff", 16))

      read(dut, SupervisorCsrAddress.Sstatus) shouldBe vmMask
      read(dut, MachineCsrAddress.Mstatus) shouldBe vmMask
      dut.io.supervisorSum.expect(true.B)
      dut.io.supervisorMxr.expect(true.B)

      write(dut, SupervisorCsrAddress.Sstatus, 0)
      dut.io.supervisorSum.expect(false.B)
      dut.io.supervisorMxr.expect(false.B)
    }

    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initialize(dut)
      write(dut, SupervisorCsrAddress.Sstatus, BigInt("ffffffff", 16))

      val v1Mask =
        (BigInt(1) << MachineCsrBit.SstatusSie) |
          (BigInt(1) << MachineCsrBit.SstatusSpie) |
          (BigInt(1) << MachineCsrBit.SstatusSpp)
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe v1Mask
      dut.io.supervisorSum.expect(false.B)
      dut.io.supervisorMxr.expect(false.B)
    }
  }

  it should "retire an satp write even when the same boundary enters a trap" in {
    simulate(new MachineCsrFile(sv32Isa)) { dut =>
      initialize(dut)

      val rootPpn = BigInt("23456", 16)
      val satpValue = (BigInt(1) << Sv32Satp.ModeBit) | rootPpn
      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(SupervisorCsrAddress.Satp.U)
      dut.io.writeData.poke(satpValue.U)
      dut.io.trapEnter.poke(true.B)
      dut.io.trapPc.poke(BigInt("80000100", 16).U)
      dut.io.trapCause.poke(2.U)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.trapEnter.poke(false.B)

      read(dut, SupervisorCsrAddress.Satp) shouldBe satpValue
      dut.io.satpRootPpn.expect(rootPpn.U)
    }
  }
}
