package aethercore

import aethercore.common.{MemSize, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.{DataPathAdapter, InstructionFetchAdapter, PtwArbiter}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RV64VirtualMemoryAdapterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64 geometry-driven VM adapters"

  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      accessed: Boolean = false,
      dirty: Boolean = false
  ): BigInt =
    (ppn << 10) | BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (write) BigInt(1) << 2 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0)) |
      (if (dirty) BigInt(1) << 7 else BigInt(0))

  it should "translate Sv39 instruction fetches with 64-bit PTE traffic" in {
    simulate(new InstructionFetchAdapter(PageTableGeometry.Sv39, paddrBits = 56, tlbEntries = 4)) { dut =>
      dut.io.requestValid.poke(false.B)
      dut.io.kill.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.virtualAddress.poke(0.U)
      dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
      dut.io.satpTranslationEnabled.poke(true.B)
      dut.io.satpRootPpn.poke(0.U)
      dut.io.mxr.poke(false.B)
      dut.io.pteReady.poke(false.B)
      dut.io.pteData.poke(0.U)
      dut.io.pteFault.poke(false.B)
      dut.io.responseReady.poke(false.B)

      // Positive canonical Sv39 address: bit 38 is zero.
      val va = BigInt("0000002040302020", 16)
      val root = BigInt("20000", 16)
      val level1 = BigInt("21000", 16)
      val level0 = BigInt("22000", 16)
      val leaf = BigInt("12345", 16)
      val vpn2 = (va >> 30) & 0x1ff
      val vpn1 = (va >> 21) & 0x1ff
      val vpn0 = (va >> 12) & 0x1ff

      dut.io.virtualAddress.poke(va.U)
      dut.io.satpRootPpn.poke(root.U)
      dut.io.requestValid.poke(true.B)
      dut.io.requestReady.expect(true.B)
      dut.clock.step()
      dut.io.requestValid.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(((root << 12) + (vpn2 << 3)).U)
      dut.io.pteData.poke(pte(level1).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(((level1 << 12) + (vpn1 << 3)).U)
      dut.io.pteData.poke(pte(level0).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(((level0 << 12) + (vpn0 << 3)).U)
      dut.io.pteData.poke(pte(leaf, execute = true, accessed = true).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.responseValid.expect(true.B)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)
      dut.io.physicalAddress.expect(((leaf << 12) | (va & 0xfff)).U)
    }
  }

  it should "translate an Sv39 data load and preserve a 64-bit physical transaction" in {
    simulate(new DataPathAdapter(PageTableGeometry.Sv39, paddrBits = 56, tlbEntries = 4)) { dut =>
      dut.io.requestValid.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.virtualAddress.poke(0.U)
      dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
      dut.io.translateWrite.poke(false.B)
      dut.io.write.poke(false.B)
      dut.io.wdata.poke(0.U)
      dut.io.wmask.poke(0.U)
      dut.io.size.poke(MemSize.DWord)
      dut.io.satpTranslationEnabled.poke(true.B)
      dut.io.satpRootPpn.poke(0.U)
      dut.io.sum.poke(false.B)
      dut.io.mxr.poke(false.B)
      dut.io.pteReady.poke(false.B)
      dut.io.pteData.poke(0.U)
      dut.io.pteFault.poke(false.B)
      dut.io.dataReady.poke(false.B)
      dut.io.dataRdata.poke(0.U)
      dut.io.dataFault.poke(false.B)

      val va = BigInt("0000002040302040", 16)
      val root = BigInt("20000", 16)
      val level1 = BigInt("21000", 16)
      val level0 = BigInt("22000", 16)
      val leaf = BigInt("34567", 16)
      val vpn2 = (va >> 30) & 0x1ff
      val vpn1 = (va >> 21) & 0x1ff
      val vpn0 = (va >> 12) & 0x1ff
      val pa = (leaf << 12) | (va & 0xfff)
      val data = BigInt("fedcba9876543210", 16)

      dut.io.virtualAddress.poke(va.U)
      dut.io.satpRootPpn.poke(root.U)
      dut.io.requestValid.poke(true.B)
      dut.clock.step()

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(((root << 12) + (vpn2 << 3)).U)
      dut.io.pteData.poke(pte(level1).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(((level1 << 12) + (vpn1 << 3)).U)
      dut.io.pteData.poke(pte(level0).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(((level0 << 12) + (vpn0 << 3)).U)
      dut.io.pteData.poke(pte(leaf, read = true, accessed = true).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.dataValid.expect(true.B)
      dut.io.dataWrite.expect(false.B)
      dut.io.dataAddress.expect(pa.U)
      dut.io.dataSize.expect(MemSize.DWord)
      dut.io.dataRdata.poke(data.U)
      dut.io.dataReady.poke(true.B)
      dut.io.requestComplete.expect(true.B)
      dut.io.readData.expect(data.U)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)
      dut.clock.step()
      dut.io.requestValid.poke(false.B)
    }
  }

  it should "arbitrate full 64-bit PTE responses with data walk priority" in {
    simulate(new PtwArbiter(PageTableGeometry.Sv39, paddrBits = 56)) { dut =>
      val dataAddress = BigInt("00000123456000", 16)
      val fetchAddress = BigInt("00000234567000", 16)
      val pteData = BigInt("00fedcba98765403", 16)

      dut.io.dataValid.poke(true.B)
      dut.io.dataAddress.poke(dataAddress.U)
      dut.io.fetchValid.poke(true.B)
      dut.io.fetchAddress.poke(fetchAddress.U)
      dut.io.memoryReady.poke(true.B)
      dut.io.memoryRdata.poke(pteData.U)
      dut.io.memoryFault.poke(false.B)

      dut.io.memoryValid.expect(true.B)
      dut.io.memoryAddress.expect(dataAddress.U)
      dut.io.dataReady.expect(true.B)
      dut.io.fetchReady.expect(false.B)
      dut.io.dataRdata.expect(pteData.U)

      dut.io.dataValid.poke(false.B)
      dut.io.memoryAddress.expect(fetchAddress.U)
      dut.io.fetchReady.expect(true.B)
      dut.io.fetchRdata.expect(pteData.U)
    }
  }
}
