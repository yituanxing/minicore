package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.{Sv32Satp, Sv32SatpRegister}

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
}
