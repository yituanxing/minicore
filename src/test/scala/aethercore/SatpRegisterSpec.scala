package aethercore

import aethercore.config.PageTableGeometry
import aethercore.core.SatpRegister
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SatpRegisterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "SatpRegister"

  it should "preserve the bounded ASIDLEN=0 Sv32 satp policy" in {
    simulate(new SatpRegister(Seq(PageTableGeometry.Sv32))) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.readData.expect(0.U)
      dut.io.translationEnabled.expect(false.B)

      dut.io.writeData.poke(BigInt("ffffffff", 16).U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)

      dut.io.mode.expect(1.U)
      dut.io.rootPpn.expect(BigInt("3fffff", 16).U)
      dut.io.asid.expect(0.U)
      dut.io.readData.expect(BigInt("803fffff", 16).U)
      dut.io.translationEnabled.expect(true.B)

      dut.io.writeData.poke(BigInt("7fffffff", 16).U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.readData.expect(0.U)
      dut.io.translationEnabled.expect(false.B)
    }
  }

  it should "switch between supported Sv39 and Sv48 modes with one RV64 layout" in {
    simulate(new SatpRegister(Seq(PageTableGeometry.Sv39, PageTableGeometry.Sv48))) { dut =>
      val rootPpn = BigInt("00000abcdefff", 16)
      val ppnMask = (BigInt(1) << 44) - 1

      dut.io.writeData.poke(((BigInt(8) << 60) | (BigInt("ffff", 16) << 44) | rootPpn).U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)

      dut.io.mode.expect(8.U)
      dut.io.rootPpn.expect((rootPpn & ppnMask).U)
      dut.io.asid.expect(0.U)
      dut.io.readData.expect(((BigInt(8) << 60) | (rootPpn & ppnMask)).U)
      dut.io.translationEnabled.expect(true.B)

      dut.io.writeData.poke(((BigInt(9) << 60) | BigInt("123456789ab", 16)).U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.mode.expect(9.U)
      dut.io.rootPpn.expect(BigInt("123456789ab", 16).U)
    }
  }

  it should "leave the entire RV64 satp unchanged on an unsupported MODE write" in {
    simulate(new SatpRegister(Seq(PageTableGeometry.Sv39, PageTableGeometry.Sv48))) { dut =>
      val accepted = (BigInt(8) << 60) | BigInt("12345", 16)
      dut.io.writeData.poke(accepted.U)
      dut.io.writeEnable.poke(true.B)
      dut.clock.step()

      dut.io.writeData.poke(((BigInt(10) << 60) | BigInt("deadbeef", 16)).U)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)

      dut.io.readData.expect(accepted.U)
      dut.io.mode.expect(8.U)
      dut.io.rootPpn.expect(BigInt("12345", 16).U)
    }
  }

  it should "reject an Sv48-only satp surface" in {
    an[IllegalArgumentException] should be thrownBy
      simulate(new SatpRegister(Seq(PageTableGeometry.Sv48))) { _ => () }
  }
}
