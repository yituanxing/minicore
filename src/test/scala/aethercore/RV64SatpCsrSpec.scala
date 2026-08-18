package aethercore

import aethercore.config.IsaConfig
import aethercore.core.{MachineCsrAddress, MachineCsrBit, MachineCsrFile, SupervisorCsrAddress}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RV64SatpCsrSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64 satp CSR integration"

  private def rv64VmIsa(modes: Set[String]): IsaConfig = IsaConfig(
    xlen = 64,
    extensions = Set('I', 'M'),
    privilegeModes = Set('M', 'S', 'U'),
    zExtensions = Set("Zicsr"),
    virtualMemoryModes = modes,
    pmpEntries = 16
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

  private def write(dut: MachineCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  private def read(dut: MachineCsrFile, address: Int): BigInt = {
    dut.io.readAddr.poke(address.U)
    dut.io.readImplemented.expect(true.B)
    dut.io.readData.peek().litValue
  }

  it should "expose Sv39 satp with a full 44-bit root PPN and ASIDLEN zero" in {
    simulate(new MachineCsrFile(rv64VmIsa(Set("Sv39")), 56, false, false)) { dut =>
      initialize(dut)
      val root = BigInt("abcde123456", 16) & ((BigInt(1) << 44) - 1)
      val requested = (BigInt(8) << 60) | (BigInt("ffff", 16) << 44) | root
      val canonical = (BigInt(8) << 60) | root

      read(dut, SupervisorCsrAddress.Satp) shouldBe 0
      dut.io.satpTranslationEnabled.expect(false.B)

      write(dut, SupervisorCsrAddress.Satp, requested)
      read(dut, SupervisorCsrAddress.Satp) shouldBe canonical
      dut.io.satpTranslationEnabled.expect(true.B)
      dut.io.satpMode.expect(8.U)
      dut.io.satpRootPpn.expect(root.U)
      dut.io.satpAsid.expect(0.U)

      // Sv48 is not implemented by this bounded CSR surface. The privileged
      // architecture requires an unsupported MODE write to preserve all state.
      write(dut, SupervisorCsrAddress.Satp, (BigInt(9) << 60) | BigInt("12345", 16))
      read(dut, SupervisorCsrAddress.Satp) shouldBe canonical
      dut.io.satpMode.expect(8.U)
    }
  }

  it should "share SUM/MXR and page-fault delegation with RV64 paged modes" in {
    simulate(new MachineCsrFile(rv64VmIsa(Set("Sv39")), 56, false, false)) { dut =>
      initialize(dut)
      val sumMxr = (BigInt(1) << MachineCsrBit.SstatusSum) |
        (BigInt(1) << MachineCsrBit.SstatusMxr)
      write(dut, SupervisorCsrAddress.Sstatus, sumMxr)
      (read(dut, SupervisorCsrAddress.Sstatus) & sumMxr) shouldBe sumMxr
      dut.io.supervisorSum.expect(true.B)
      dut.io.supervisorMxr.expect(true.B)

      val pageFaults = (BigInt(1) << 12) | (BigInt(1) << 13) | (BigInt(1) << 15)
      write(dut, MachineCsrAddress.Medeleg, pageFaults)
      (read(dut, MachineCsrAddress.Medeleg) & pageFaults) shouldBe pageFaults
    }
  }

  it should "support the architectural Sv39 plus Sv48 satp mode set with one CSR file" in {
    simulate(new MachineCsrFile(rv64VmIsa(Set("Sv39", "Sv48")), 56, false, false)) { dut =>
      initialize(dut)
      val root = BigInt("123456789ab", 16)
      write(dut, SupervisorCsrAddress.Satp, (BigInt(9) << 60) | root)
      dut.io.satpMode.expect(9.U)
      dut.io.satpRootPpn.expect(root.U)
      dut.io.satpTranslationEnabled.expect(true.B)
      read(dut, SupervisorCsrAddress.Satp) shouldBe ((BigInt(9) << 60) | root)
    }
  }
}
