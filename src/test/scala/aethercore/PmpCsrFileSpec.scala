package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{PmpCsrAddress, PmpCsrFile}

class PmpCsrFileSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "PmpCsrFile"

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

  it should "canonicalize pmpcfg0 and physical address bits" in {
    simulate(new PmpCsrFile(CoreProfiles.rv32imuPmpSoftware.isa)) { dut =>
      initialize(dut)

      // Reserved config bits are zeroed and W is cleared when R is zero.
      write(dut, PmpCsrAddress.Pmpcfg0, BigInt("ff950a7f", 16))
      read(dut, PmpCsrAddress.Pmpcfg0) shouldBe BigInt("9f95081f", 16)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)

      write(dut, PmpCsrAddress.pmpaddr(0), BigInt("ffffffff", 16))
      read(dut, PmpCsrAddress.pmpaddr(0)) shouldBe BigInt("3fffffff", 16)
    }
  }

  it should "implement all RV32 pmpaddr CSR bits for a 34-bit physical domain" in {
    simulate(new PmpCsrFile(CoreProfiles.rv32imuPmpSoftware.isa, paddrBits = 34)) { dut =>
      initialize(dut)

      write(dut, PmpCsrAddress.pmpaddr(0), BigInt("ffffffff", 16))
      read(dut, PmpCsrAddress.pmpaddr(0)) shouldBe BigInt("ffffffff", 16)
      dut.io.pmpAddress(0).expect(BigInt("ffffffff", 16).U)
    }
  }

  it should "implement all four RV32 PMP config banks through entry 15" in {
    simulate(new PmpCsrFile(CoreProfiles.rv32imuPmpSoftware.isa)) { dut =>
      initialize(dut)

      for (bank <- 0 until 4) {
        val address = PmpCsrAddress.pmpcfg(bank)
        write(dut, address, BigInt("1f1b1919", 16))
        read(dut, address) shouldBe BigInt("1f1b1919", 16)
      }

      write(dut, PmpCsrAddress.pmpaddr(15), BigInt("92345678", 16))
      read(dut, PmpCsrAddress.pmpaddr(15)) shouldBe BigInt("12345678", 16)
      dut.io.pmpAddress(15).expect(BigInt("12345678", 16).U)
    }
  }

  it should "lock an entry and the lower bound of a locked TOR entry" in {
    simulate(new PmpCsrFile(CoreProfiles.rv32imuPmpSoftware.isa)) { dut =>
      initialize(dut)

      write(dut, PmpCsrAddress.pmpaddr(0), BigInt("20000400", 16))

      // Entry 1 is a locked TOR region. Its lower bound is pmpaddr0, so both
      // pmpcfg1 and pmpaddr0 become immutable until reset.
      write(dut, PmpCsrAddress.Pmpcfg0, BigInt("00008800", 16))
      write(dut, PmpCsrAddress.pmpaddr(0), BigInt("20000800", 16))
      write(dut, PmpCsrAddress.Pmpcfg0, 0)

      read(dut, PmpCsrAddress.pmpaddr(0)) shouldBe BigInt("20000400", 16)
      read(dut, PmpCsrAddress.Pmpcfg0) shouldBe BigInt("00008800", 16)
    }
  }

  it should "lock entry 3 through a locked TOR entry 4 across pmpcfg banks" in {
    simulate(new PmpCsrFile(CoreProfiles.rv32imuPmpSoftware.isa)) { dut =>
      initialize(dut)

      write(dut, PmpCsrAddress.pmpaddr(3), BigInt("20001000", 16))
      write(dut, PmpCsrAddress.pmpaddr(4), BigInt("20002000", 16))

      // Entry 4 is the low byte of pmpcfg1. Locked TOR makes pmpaddr3 its
      // immutable lower bound even though entry 3 lives in pmpcfg0.
      write(dut, PmpCsrAddress.pmpcfg(1), BigInt("00000088", 16))
      write(dut, PmpCsrAddress.pmpaddr(3), BigInt("20001800", 16))
      write(dut, PmpCsrAddress.pmpaddr(4), BigInt("20002800", 16))
      write(dut, PmpCsrAddress.pmpcfg(1), 0)

      read(dut, PmpCsrAddress.pmpaddr(3)) shouldBe BigInt("20001000", 16)
      read(dut, PmpCsrAddress.pmpaddr(4)) shouldBe BigInt("20002000", 16)
      read(dut, PmpCsrAddress.pmpcfg(1)) shouldBe BigInt("00000088", 16)
    }
  }

  it should "leave PMP CSRs unimplemented in the original syscall profile" in {
    simulate(new PmpCsrFile(CoreProfiles.rv32imuSoftware.isa)) { dut =>
      initialize(dut)
      dut.io.readAddr.poke(PmpCsrAddress.Pmpcfg0.U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(0.U)
    }
  }
}
