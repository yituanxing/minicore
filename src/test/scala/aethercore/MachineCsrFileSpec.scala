package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}

class MachineCsrFileSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineCsrFile"

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

  it should "expose the RV32IM machine CSR map and WARL masks" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeAddr.poke(0.U)
      dut.io.writeData.poke(0.U)

      read(dut, MachineCsrAddress.Misa) shouldBe BigInt("40001100", 16)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)

      write(dut, MachineCsrAddress.Mscratch, BigInt("89abcdef", 16))
      read(dut, MachineCsrAddress.Mscratch) shouldBe BigInt("89abcdef", 16)

      write(dut, MachineCsrAddress.Mtvec, BigInt("80000103", 16))
      read(dut, MachineCsrAddress.Mtvec) shouldBe BigInt("80000100", 16)

      write(dut, MachineCsrAddress.Mepc, BigInt("80000203", 16))
      read(dut, MachineCsrAddress.Mepc) shouldBe BigInt("80000200", 16)

      write(dut, MachineCsrAddress.Mstatus, BigInt("ffffffff", 16))
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00001888", 16)

      dut.io.readAddr.poke("h7ff".U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(0.U)
    }
  }

  it should "construct an XLEN-wide RV64 misa value" in {
    simulate(new MachineCsrFile(CoreProfiles.rv64imCurrent.isa)) { dut =>
      dut.io.writeEnable.poke(false.B)
      dut.io.writeAddr.poke(0.U)
      dut.io.writeData.poke(0.U)

      read(dut, MachineCsrAddress.Misa) shouldBe BigInt("8000000000001100", 16)
      write(dut, MachineCsrAddress.Mscratch, BigInt("fedcba9876543210", 16))
      read(dut, MachineCsrAddress.Mscratch) shouldBe BigInt("fedcba9876543210", 16)
    }
  }
}
