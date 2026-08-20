package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile, Rv32SstcBit, Rv32SstcCsrAddress, SupervisorCsrAddress}

/** Architectural time CSR without Sstc.
  *
  * Linux RISC-V reads CSR time (0xc01) independently of the Sstc extension.
  * Sstc adds stimecmp; it does not own the platform time counter itself.
  *
  * Linux 的 time CSR 与 Sstc 解耦：Sstc 只增加 stimecmp，不拥有 mtime/time。
  */
class SupervisorTimeCounterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "supervisor architectural time counter"

  private val timeMask = BigInt(1) << Rv32SstcBit.McounterenTime
  private val sampleTime = BigInt("1122334455667788", 16)
  private val timeIsa = CoreProfiles.rv64imsuSoftware.isa.copy(timeCounter = true)

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.time.get.poke(sampleTime.U)
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

  private def select(dut: MachineCsrFile, address: Int): Unit =
    dut.io.readAddr.poke(address.U)

  private def mretToSupervisor(dut: MachineCsrFile): Unit = {
    write(dut, MachineCsrAddress.Mstatus, BigInt("800", 16)) // MPP=S
    write(dut, MachineCsrAddress.Mepc, BigInt("80000000", 16))
    dut.io.trapReturnSupervisor.poke(false.B)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
    dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
  }

  it should "read the full RV64 time CSR without exposing Sstc" in {
    timeIsa.hasTimeCounter shouldBe true
    timeIsa.hasSstc shouldBe false

    simulate(new MachineCsrFile(timeIsa)) { dut =>
      initialize(dut)

      select(dut, Rv32SstcCsrAddress.Time)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(sampleTime.U)

      // timeh exists only on RV32; RV64 reads the complete 64-bit time CSR.
      select(dut, Rv32SstcCsrAddress.Timeh)
      dut.io.readImplemented.expect(false.B)

      // The counter capability must not accidentally expose Sstc comparators.
      select(dut, SupervisorCsrAddress.Stimecmp)
      dut.io.readImplemented.expect(false.B)
      select(dut, SupervisorCsrAddress.Stimecmph)
      dut.io.readImplemented.expect(false.B)
    }
  }

  it should "gate supervisor time reads with mcounteren.TM independently of Sstc" in {
    simulate(new MachineCsrFile(timeIsa)) { dut =>
      initialize(dut)

      write(dut, MachineCsrAddress.Mcounteren, BigInt(-1))
      select(dut, MachineCsrAddress.Mcounteren)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)
      dut.io.readData.expect(timeMask.U)

      mretToSupervisor(dut)
      select(dut, Rv32SstcCsrAddress.Time)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)
      dut.io.readData.expect(sampleTime.U)

      write(dut, SupervisorCsrAddress.Scounteren, BigInt(-1))
      select(dut, SupervisorCsrAddress.Scounteren)
      dut.io.readData.expect(timeMask.U)
    }
  }

  it should "trap supervisor time reads when M-mode has not delegated TM" in {
    simulate(new MachineCsrFile(timeIsa)) { dut =>
      initialize(dut)
      mretToSupervisor(dut)

      select(dut, Rv32SstcCsrAddress.Time)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)
    }
  }
}
