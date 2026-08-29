package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{PmpCsrAddress, PmpCsrFile}

/**
  * Focused RV64 PMP CSR-packing contract.
  * RV64 每个 pmpcfg CSR 打包 8 个配置字节，只实现偶数号 pmpcfg CSR。
  */
class RV64PmpCsrSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64 PMP CSR packing"

  private val config = CoreProfiles.rv64imsuPmpSoftware
  private val isa = config.isa

  private def initialize(dut: PmpCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
  }

  private def read(dut: PmpCsrFile, address: Int): BigInt = {
    dut.io.readAddr.poke(address.U)
    dut.io.readData.peek().litValue
  }

  private def write(dut: PmpCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  it should "describe a bounded bare RV64IM M/S/U PMP16 profile" in {
    config.name shouldBe "rv64imsu-pmp-software"
    isa.xlen shouldBe 64
    isa.hasM shouldBe true
    isa.hasS shouldBe true
    isa.hasU shouldBe true
    isa.hasPmp shouldBe true
    isa.pmpEntries shouldBe 16
    isa.hasA shouldBe false
    isa.hasC shouldBe false
    isa.hasSv32 shouldBe false
    isa.hasSstc shouldBe false
    isa.march shouldBe "rv64im_zicsr"
    config.platform.paddrBits shouldBe 56
    config.platform.busDataBits shouldBe 64
  }

  it should "pack entries 0 through 7 in pmpcfg0 and entries 8 through 15 in pmpcfg2" in {
    simulate(new PmpCsrFile(isa, paddrBits = config.platform.paddrBits)) { dut =>
      initialize(dut)

      val low = BigInt("1f1b19191f1b1919", 16)
      val high = BigInt("191f1b1f191f1b19", 16)
      write(dut, PmpCsrAddress.pmpcfg(64, 0), low)
      write(dut, PmpCsrAddress.pmpcfg(64, 1), high)

      read(dut, 0x3a0) shouldBe low
      read(dut, 0x3a2) shouldBe high
      for (entry <- 0 until 8) {
        dut.io.config(entry).expect(((low >> (entry * 8)) & 0xff).U)
      }
      for (entry <- 8 until 16) {
        dut.io.config(entry).expect(((high >> ((entry - 8) * 8)) & 0xff).U)
      }
    }
  }

  it should "leave odd RV64 pmpcfg CSR numbers unimplemented" in {
    simulate(new PmpCsrFile(isa, paddrBits = config.platform.paddrBits)) { dut =>
      initialize(dut)

      dut.io.readAddr.poke(0x3a1.U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(0.U)

      dut.io.readAddr.poke(0x3a3.U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(0.U)

      write(dut, 0x3a1, BigInt("ffffffffffffffff", 16))
      read(dut, 0x3a0) shouldBe 0
      read(dut, 0x3a2) shouldBe 0
    }
  }

  it should "canonicalize all eight config bytes in a 64-bit pmpcfg CSR" in {
    simulate(new PmpCsrFile(isa, paddrBits = config.platform.paddrBits)) { dut =>
      initialize(dut)

      write(dut, 0x3a0, BigInt("ff950a7fff950a7f", 16))
      read(dut, 0x3a0) shouldBe BigInt("9f95081f9f95081f", 16)
    }
  }

  it should "implement RV64 pmpaddr through architectural PA55:2" in {
    simulate(new PmpCsrFile(isa, paddrBits = config.platform.paddrBits)) { dut =>
      initialize(dut)

      write(dut, PmpCsrAddress.pmpaddr(0), BigInt("ffffffffffffffff", 16))
      read(dut, PmpCsrAddress.pmpaddr(0)) shouldBe BigInt("003fffffffffffff", 16)
      dut.io.pmpAddress(0).expect(BigInt("003fffffffffffff", 16).U)
    }
  }

  it should "lock entry 7 through a locked TOR entry 8 across RV64 config banks" in {
    simulate(new PmpCsrFile(isa, paddrBits = config.platform.paddrBits)) { dut =>
      initialize(dut)

      write(dut, PmpCsrAddress.pmpaddr(7), BigInt("0000000020001000", 16))
      write(dut, PmpCsrAddress.pmpaddr(8), BigInt("0000000020002000", 16))

      // Entry 8 is the low byte of RV64 pmpcfg2. A locked TOR entry owns
      // pmpaddr7 as its lower bound even though entry 7 lives in pmpcfg0.
      write(dut, 0x3a2, BigInt("88", 16))
      write(dut, PmpCsrAddress.pmpaddr(7), BigInt("0000000020001800", 16))
      write(dut, PmpCsrAddress.pmpaddr(8), BigInt("0000000020002800", 16))
      write(dut, 0x3a2, 0)

      read(dut, PmpCsrAddress.pmpaddr(7)) shouldBe BigInt("0000000020001000", 16)
      read(dut, PmpCsrAddress.pmpaddr(8)) shouldBe BigInt("0000000020002000", 16)
      read(dut, 0x3a2) shouldBe BigInt("88", 16)
    }
  }
}
