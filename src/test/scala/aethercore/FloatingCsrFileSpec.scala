package aethercore

import aethercore.config.IsaConfig
import aethercore.core.{FloatingCsrAddress, MachineCsrAddress, MachineCsrFile,
  MachineCsrBit, SupervisorCsrAddress}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Architectural FP status is updated by retirement, never by speculative FP completion. */
class FloatingCsrFileSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64GC machine floating-point CSR ownership"

  private val rv64gc = IsaConfig(
    xlen = 64,
    extensions = Set('I', 'M', 'A', 'F', 'D', 'C'),
    privilegeModes = Set('M', 'S', 'U'),
    zExtensions = Set("Zicsr", "Zifencei"),
    virtualMemoryModes = Set("Sv39"),
    pmpEntries = 16)

  private def initialize(d: MachineCsrFile): Unit = {
    d.io.readAddr.poke(0.U)
    d.io.writeEnable.poke(false.B)
    d.io.writeAddr.poke(0.U)
    d.io.writeData.poke(0.U)
    d.io.timerInterrupt.poke(false.B)
    d.io.trapEnter.poke(false.B)
    d.io.trapPc.poke(0.U)
    d.io.trapCause.poke(0.U)
    d.io.trapValue.poke(0.U)
    d.io.trapReturn.poke(false.B)
    d.io.trapReturnSupervisor.poke(false.B)
    d.io.fpCommit.get.valid.poke(false.B)
    d.io.fpCommit.get.bits.fflags.poke(0.U)
    d.io.fpCommit.get.bits.dirty.poke(false.B)
  }

  private def read(d: MachineCsrFile, addr: Int): BigInt = {
    d.io.readAddr.poke(addr.U)
    d.io.readData.peek().litValue
  }

  private def write(d: MachineCsrFile, addr: Int, data: BigInt): Unit = {
    d.io.writeAddr.poke(addr.U)
    d.io.writeData.poke(data.U)
    d.io.writeEnable.poke(true.B)
    d.clock.step()
    d.io.writeEnable.poke(false.B)
  }

  private def retireFp(d: MachineCsrFile, flags: Int, dirty: Boolean): Unit = {
    d.io.fpCommit.get.bits.fflags.poke(flags.U)
    d.io.fpCommit.get.bits.dirty.poke(dirty.B)
    d.io.fpCommit.get.valid.poke(true.B)
    d.clock.step()
    d.io.fpCommit.get.valid.poke(false.B)
  }

  it should "gate the FCSR view on FS and derive SD without persisting a writable SD bit" in {
    simulate(new MachineCsrFile(rv64gc, 32, false, false)) { d =>
      initialize(d)
      d.io.fpStateEnabled.expect(false.B)
      d.io.fpRoundingMode.expect(0.U)
      d.io.fpFlags.expect(0.U)
      read(d, FloatingCsrAddress.Fcsr) shouldBe 0
      d.io.readImplemented.expect(false.B)

      // CSR writes must not affect an FS=Off architectural context.
      write(d, FloatingCsrAddress.Fcsr, 0xff)
      read(d, MachineCsrAddress.Mstatus) & (BigInt(3) << 13) shouldBe 0

      write(d, MachineCsrAddress.Mstatus, BigInt(1) << MachineCsrBit.MstatusFsLow)
      d.io.fpStateEnabled.expect(true.B)
      read(d, FloatingCsrAddress.Fcsr) shouldBe 0
      d.io.readImplemented.expect(true.B)
      d.io.readWritable.expect(true.B)

      // FCSR[7:5] is frm; [4:0] is fflags. Reserved high bits canonicalize to zero.
      write(d, FloatingCsrAddress.Fcsr, BigInt("e5", 16) | (BigInt(1) << 60))
      read(d, FloatingCsrAddress.Fcsr) shouldBe BigInt("e5", 16)
      read(d, FloatingCsrAddress.Frm) shouldBe 7
      read(d, FloatingCsrAddress.Fflags) shouldBe 5
      d.io.fpRoundingMode.expect(7.U)
      d.io.fpFlags.expect(5.U)

      // Any legal FCSR modification makes FS Dirty; SD is read-only derived.
      val status = read(d, MachineCsrAddress.Mstatus)
      ((status >> MachineCsrBit.MstatusFsLow) & 3) shouldBe 3
      ((status >> 63) & 1) shouldBe 1
      val supervisor = read(d, SupervisorCsrAddress.Sstatus)
      ((supervisor >> MachineCsrBit.MstatusFsLow) & 3) shouldBe 3
      ((supervisor >> 63) & 1) shouldBe 1

      write(d, MachineCsrAddress.Mstatus,
        (BigInt(2) << MachineCsrBit.MstatusFsLow) | (BigInt(1) << 63))
      val clean = read(d, MachineCsrAddress.Mstatus)
      ((clean >> MachineCsrBit.MstatusFsLow) & 3) shouldBe 2
      ((clean >> 63) & 1) shouldBe 0 // software cannot set SD

      // S-mode can switch contexts by setting FS=Off; underlying FCSR is
      // retained but architecturally inaccessible until FS is enabled again.
      write(d, SupervisorCsrAddress.Sstatus, 0)
      d.io.fpStateEnabled.expect(false.B)
      read(d, FloatingCsrAddress.Fcsr) shouldBe 0
      d.io.readImplemented.expect(false.B)
      write(d, SupervisorCsrAddress.Sstatus, BigInt(1) << MachineCsrBit.MstatusFsLow)
      read(d, FloatingCsrAddress.Fcsr) shouldBe BigInt("e5", 16)
    }
  }

  it should "accumulate only retired FP flags and atomically mark FS Dirty on trap entry" in {
    simulate(new MachineCsrFile(rv64gc, 32, false, false)) { d =>
      initialize(d)
      write(d, MachineCsrAddress.Mstatus, BigInt(2) << MachineCsrBit.MstatusFsLow)
      d.io.fpStateEnabled.expect(true.B)
      read(d, FloatingCsrAddress.Fflags) shouldBe 0

      // No FU completion drives FCSR. Only this retiring pulse changes flags.
      retireFp(d, flags = 0x12, dirty = true)
      read(d, FloatingCsrAddress.Fflags) shouldBe 0x12
      read(d, MachineCsrAddress.Mstatus) & (BigInt(3) << 13) shouldBe (BigInt(3) << 13)
      retireFp(d, flags = 0x05, dirty = true)
      read(d, FloatingCsrAddress.Fflags) shouldBe 0x17

      // A CSR write replaces the flags, while a subsequent FP retires with OR.
      write(d, FloatingCsrAddress.Fflags, 0x04)
      retireFp(d, flags = 0x02, dirty = false)
      read(d, FloatingCsrAddress.Fflags) shouldBe 0x06

      write(d, SupervisorCsrAddress.Sstatus, BigInt(1) << MachineCsrBit.MstatusFsLow)
      read(d, FloatingCsrAddress.Fflags) shouldBe 0x06
      read(d, MachineCsrAddress.Mstatus) & (BigInt(3) << 13) shouldBe (BigInt(1) << 13)

      d.io.fpCommit.get.valid.poke(true.B)
      d.io.fpCommit.get.bits.dirty.poke(true.B)
      d.io.fpCommit.get.bits.fflags.poke(0x08.U)
      d.io.trapEnter.poke(true.B)
      d.io.trapPc.poke(BigInt("80004000", 16).U)
      d.io.trapCause.poke(5.U)
      d.clock.step()
      d.io.fpCommit.get.valid.poke(false.B)
      d.io.trapEnter.poke(false.B)
      read(d, FloatingCsrAddress.Fflags) shouldBe 0x0e
      read(d, MachineCsrAddress.Mstatus) & (BigInt(3) << 13) shouldBe (BigInt(3) << 13)
      d.io.fpStateEnabled.expect(true.B)
    }
  }

  it should "keep clean FP state clean for a flag-free read and retain saved FCSR across FS Off" in {
    simulate(new MachineCsrFile(rv64gc, 32, false, false)) { d =>
      initialize(d)
      write(d, MachineCsrAddress.Mstatus, BigInt(2) << MachineCsrBit.MstatusFsLow)
      retireFp(d, flags = 0, dirty = false)
      ((read(d, MachineCsrAddress.Mstatus) >> 13) & 3) shouldBe 2
      write(d, FloatingCsrAddress.Fcsr, BigInt("a1", 16))
      read(d, FloatingCsrAddress.Fcsr) shouldBe BigInt("a1", 16)

      // Linux may mark FP state Off between context switches. A direct CSR
      // write while Off must never mutate the saved FCSR state, even if its
      // caller bypasses the instruction-level illegal-access evaluator.
      write(d, SupervisorCsrAddress.Sstatus, 0)
      d.io.fpStateEnabled.expect(false.B)
      write(d, FloatingCsrAddress.Fcsr, BigInt("e0", 16))
      d.io.readAddr.poke(FloatingCsrAddress.Fcsr.U)
      d.io.readImplemented.expect(false.B)
      write(d, SupervisorCsrAddress.Sstatus, BigInt(2) << MachineCsrBit.MstatusFsLow)
      d.io.fpStateEnabled.expect(true.B)
      read(d, FloatingCsrAddress.Fcsr) shouldBe BigInt("a1", 16)
      ((read(d, MachineCsrAddress.Mstatus) >> 63) & 1) shouldBe 0
    }
  }

  it should "derive RV32 SD from FS Dirty in bit 31 without aliasing FCSR" in {
    val rv32f = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M', 'A', 'F'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr"),
      virtualMemoryModes = Set("Sv32"),
      pmpEntries = 16)
    simulate(new MachineCsrFile(rv32f, 32, false, false)) { d =>
      initialize(d)
      write(d, MachineCsrAddress.Mstatus, BigInt(1) << MachineCsrBit.MstatusFsLow)
      write(d, FloatingCsrAddress.Frm, 6)
      read(d, FloatingCsrAddress.Frm) shouldBe 6 // reserved frm is CSR-readable
      ((read(d, MachineCsrAddress.Mstatus) >> 31) & 1) shouldBe 1
      ((read(d, SupervisorCsrAddress.Sstatus) >> 31) & 1) shouldBe 1
      write(d, SupervisorCsrAddress.Sstatus, BigInt(2) << MachineCsrBit.MstatusFsLow)
      ((read(d, MachineCsrAddress.Mstatus) >> 31) & 1) shouldBe 0
      read(d, FloatingCsrAddress.Frm) shouldBe 6
    }
  }
}
