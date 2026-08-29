package aethercore

import aethercore.config.PageTableGeometry
import aethercore.core.{SatpRegister, Sv32Satp, Sv32SatpRegister}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class Sv32SatpRegisterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Sv32SatpRegister"

  private def write(dut: Sv32SatpRegister, value: BigInt): Unit = {
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  it should "reset to canonical Bare mode" in {
    simulate(new Sv32SatpRegister) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeData.poke(0.U)

      dut.io.readData.expect(0.U)
      dut.io.translationEnabled.expect(false.B)
      dut.io.rootPpn.expect(0.U)
      dut.io.asid.expect(0.U)
    }
  }

  it should "retain the full Sv32 root PPN while implementing ASIDLEN zero" in {
    simulate(new Sv32SatpRegister) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeData.poke(0.U)

      val rootPpn = BigInt("3abcde", 16) & ((BigInt(1) << Sv32Satp.PpnBits) - 1)
      val requestedAsid = BigInt("1ff", 16)
      val request =
        (BigInt(1) << Sv32Satp.ModeBit) |
          (requestedAsid << Sv32Satp.AsidLow) |
          rootPpn

      write(dut, request)

      val expected = (BigInt(1) << Sv32Satp.ModeBit) | rootPpn
      dut.io.readData.expect(expected.U)
      dut.io.translationEnabled.expect(true.B)
      dut.io.rootPpn.expect(rootPpn.U)
      dut.io.asid.expect(0.U)
    }
  }

  it should "support the highest architectural Sv32 root PPN" in {
    simulate(new Sv32SatpRegister) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeData.poke(0.U)

      val rootPpn = (BigInt(1) << Sv32Satp.PpnBits) - 1
      write(dut, (BigInt(1) << Sv32Satp.ModeBit) | rootPpn)

      dut.io.translationEnabled.expect(true.B)
      dut.io.rootPpn.expect(rootPpn.U)
      dut.io.readData.expect(((BigInt(1) << Sv32Satp.ModeBit) | rootPpn).U)
    }
  }

  it should "canonicalize Bare to zero even when payload bits are requested" in {
    simulate(new Sv32SatpRegister) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeData.poke(0.U)

      write(dut, (BigInt(1) << Sv32Satp.ModeBit) | BigInt("12345", 16))
      dut.io.translationEnabled.expect(true.B)

      val nonCanonicalBare =
        (BigInt("155", 16) << Sv32Satp.AsidLow) | BigInt("2abcde", 16)
      write(dut, nonCanonicalBare)

      dut.io.readData.expect(0.U)
      dut.io.translationEnabled.expect(false.B)
      dut.io.rootPpn.expect(0.U)
      dut.io.asid.expect(0.U)
    }
  }

  it should "hold state while writes are disabled" in {
    simulate(new Sv32SatpRegister) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeData.poke(0.U)

      val rootPpn = BigInt("23456", 16)
      val expected = (BigInt(1) << Sv32Satp.ModeBit) | rootPpn
      write(dut, expected)

      dut.io.writeData.poke(0.U)
      dut.clock.step(3)

      dut.io.readData.expect(expected.U)
      dut.io.translationEnabled.expect(true.B)
      dut.io.rootPpn.expect(rootPpn.U)
    }
  }

  it should "freeze the shared Sv32 Sv39 Sv48 geometry in the focused VM gate" in {
    PageTableGeometry.Sv32.levels shouldBe 2
    PageTableGeometry.Sv32.vpnBitsPerLevel shouldBe 10
    PageTableGeometry.Sv32.pteBytes shouldBe 4
    PageTableGeometry.Sv32.architecturalPhysicalAddressBits shouldBe 34

    PageTableGeometry.Sv39.satpMode shouldBe 8
    PageTableGeometry.Sv39.vaBits shouldBe 39
    PageTableGeometry.Sv39.levels shouldBe 3
    PageTableGeometry.Sv39.vpnBitsPerLevel shouldBe 9
    PageTableGeometry.Sv39.pteBytes shouldBe 8
    PageTableGeometry.Sv39.ppnBits shouldBe 44
    PageTableGeometry.Sv39.architecturalPhysicalAddressBits shouldBe 56

    PageTableGeometry.Sv48.satpMode shouldBe 9
    PageTableGeometry.Sv48.vaBits shouldBe 48
    PageTableGeometry.Sv48.levels shouldBe 4
    PageTableGeometry.Sv48.vpnBitsPerLevel shouldBe 9
    PageTableGeometry.Sv48.pteBytes shouldBe 8
    PageTableGeometry.Sv48.ppnBits shouldBe 44
    PageTableGeometry.Sv48.architecturalPhysicalAddressBits shouldBe 56

    PageTableGeometry.validateArchitecturalModes(
      64,
      Set('M', 'S', 'U'),
      Set("Sv39", "Sv48")
    ) shouldBe Set(PageTableGeometry.Sv39, PageTableGeometry.Sv48)

    an[IllegalArgumentException] should be thrownBy
      PageTableGeometry.validateArchitecturalModes(64, Set('M', 'S', 'U'), Set("Sv48"))
  }

  it should "exercise the shared RV64 satp surface for Sv39 Sv48 and unsupported MODE" in {
    simulate(new SatpRegister(Seq(PageTableGeometry.Sv39, PageTableGeometry.Sv48))) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeData.poke(0.U)
      dut.io.readData.expect(0.U)

      val sv39 = (BigInt(8) << 60) | BigInt("123456789ab", 16)
      dut.io.writeData.poke(sv39.U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.mode.expect(8.U)
      dut.io.rootPpn.expect(BigInt("123456789ab", 16).U)
      dut.io.asid.expect(0.U)
      dut.io.readData.expect(sv39.U)

      val sv48 = (BigInt(9) << 60) | BigInt("23456789abc", 16)
      dut.io.writeData.poke(sv48.U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.mode.expect(9.U)
      dut.io.rootPpn.expect(BigInt("23456789abc", 16).U)
      dut.io.readData.expect(sv48.U)

      dut.io.writeData.poke(((BigInt(10) << 60) | BigInt("deadbeef", 16)).U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.readData.expect(sv48.U)
    }
  }
}
