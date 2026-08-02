package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}

class MachineCsrFileSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineCsrFile"

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
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

  it should "expose the RV32IM machine CSR map and WARL masks" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initialize(dut)

      read(dut, MachineCsrAddress.Misa) shouldBe BigInt("40001100", 16)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)

      write(dut, MachineCsrAddress.Mscratch, BigInt("89abcdef", 16))
      read(dut, MachineCsrAddress.Mscratch) shouldBe BigInt("89abcdef", 16)

      write(dut, MachineCsrAddress.Mtvec, BigInt("80000103", 16))
      read(dut, MachineCsrAddress.Mtvec) shouldBe BigInt("80000100", 16)

      write(dut, MachineCsrAddress.Mepc, BigInt("80000203", 16))
      read(dut, MachineCsrAddress.Mepc) shouldBe BigInt("80000200", 16)
      dut.io.returnPc.expect(BigInt("80000200", 16).U)

      write(dut, MachineCsrAddress.Mstatus, BigInt("ffffffff", 16))
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00001888", 16)

      write(dut, MachineCsrAddress.Mie, BigInt("ffffffff", 16))
      read(dut, MachineCsrAddress.Mie) shouldBe BigInt("00000080", 16)

      read(dut, MachineCsrAddress.Mip) shouldBe 0
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)
      dut.io.timerInterrupt.poke(true.B)
      read(dut, MachineCsrAddress.Mip) shouldBe BigInt("00000080", 16)

      dut.io.readAddr.poke("h7ff".U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(0.U)
    }
  }

  it should "gate a machine timer interrupt with both mstatus.MIE and mie.MTIE" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initialize(dut)
      dut.io.timerInterrupt.poke(true.B)
      dut.io.machineTimerInterrupt.expect(false.B)

      write(dut, MachineCsrAddress.Mie, BigInt("80", 16))
      dut.io.machineTimerInterrupt.expect(false.B)

      write(dut, MachineCsrAddress.Mstatus, BigInt("8", 16))
      dut.io.machineTimerInterrupt.expect(true.B)

      write(dut, MachineCsrAddress.Mie, 0)
      dut.io.machineTimerInterrupt.expect(false.B)
    }
  }

  it should "enter an M-mode trap atomically with WARL mepc and status stacking" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initialize(dut)

      write(dut, MachineCsrAddress.Mtvec, BigInt("80000103", 16))
      dut.io.trapVector.expect(BigInt("80000100", 16).U)
      write(dut, MachineCsrAddress.Mstatus, BigInt("00000008", 16))

      dut.io.trapEnter.poke(true.B)
      dut.io.trapPc.poke(BigInt("80000203", 16).U)
      dut.io.trapCause.poke(5.U)
      dut.io.trapValue.poke(BigInt("90000004", 16).U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)

      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00001880", 16)
      read(dut, MachineCsrAddress.Mepc) shouldBe BigInt("80000200", 16)
      read(dut, MachineCsrAddress.Mcause) shouldBe 5
      read(dut, MachineCsrAddress.Mtval) shouldBe BigInt("90000004", 16)
      read(dut, MachineCsrAddress.Mscratch) shouldBe 0
    }
  }

  it should "retire a CSR write before taking a same-boundary timer interrupt" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initialize(dut)
      write(dut, MachineCsrAddress.Mie, BigInt("80", 16))
      dut.io.timerInterrupt.poke(true.B)

      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(MachineCsrAddress.Mstatus.U)
      dut.io.writeData.poke(BigInt("8", 16).U)
      dut.io.machineTimerInterrupt.expect(true.B)
      dut.io.trapEnter.poke(true.B)
      dut.io.trapPc.poke(BigInt("80000403", 16).U)
      dut.io.trapCause.poke(BigInt("80000007", 16).U)
      dut.io.trapValue.poke(0.U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)
      dut.io.writeEnable.poke(false.B)

      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00001880", 16)
      read(dut, MachineCsrAddress.Mie) shouldBe BigInt("00000080", 16)
      read(dut, MachineCsrAddress.Mepc) shouldBe BigInt("80000400", 16)
      read(dut, MachineCsrAddress.Mcause) shouldBe BigInt("80000007", 16)
    }
  }

  it should "use and preserve a retiring mtvec write for same-boundary interrupt entry" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initialize(dut)
      write(dut, MachineCsrAddress.Mstatus, BigInt("8", 16))
      write(dut, MachineCsrAddress.Mie, BigInt("80", 16))
      dut.io.timerInterrupt.poke(true.B)

      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(MachineCsrAddress.Mtvec.U)
      dut.io.writeData.poke(BigInt("80000803", 16).U)
      dut.io.trapVector.expect(BigInt("80000800", 16).U)
      dut.io.trapEnter.poke(true.B)
      dut.io.trapPc.poke(BigInt("80000600", 16).U)
      dut.io.trapCause.poke(BigInt("80000007", 16).U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)
      dut.io.writeEnable.poke(false.B)

      read(dut, MachineCsrAddress.Mtvec) shouldBe BigInt("80000800", 16)
      dut.io.trapVector.expect(BigInt("80000800", 16).U)
    }
  }

  it should "return from an M-mode trap with status restoration and write priority" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initialize(dut)

      write(dut, MachineCsrAddress.Mstatus, BigInt("00000008", 16))
      dut.io.trapEnter.poke(true.B)
      dut.io.trapPc.poke(BigInt("80000403", 16).U)
      dut.io.trapCause.poke(11.U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)

      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00001880", 16)
      dut.io.returnPc.expect(BigInt("80000400", 16).U)

      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(MachineCsrAddress.Mscratch.U)
      dut.io.writeData.poke(BigInt("deadbeef", 16).U)
      dut.io.trapReturn.poke(true.B)
      dut.clock.step()
      dut.io.trapReturn.poke(false.B)
      dut.io.writeEnable.poke(false.B)

      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00001888", 16)
      read(dut, MachineCsrAddress.Mscratch) shouldBe 0
      dut.io.returnPc.expect(BigInt("80000400", 16).U)
    }
  }

  it should "construct an XLEN-wide RV64 misa value" in {
    simulate(new MachineCsrFile(CoreProfiles.rv64imCurrent.isa)) { dut =>
      initialize(dut)

      read(dut, MachineCsrAddress.Misa) shouldBe BigInt("8000000000001100", 16)
      write(dut, MachineCsrAddress.Mscratch, BigInt("fedcba9876543210", 16))
      read(dut, MachineCsrAddress.Mscratch) shouldBe BigInt("fedcba9876543210", 16)
    }
  }
}
