package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrBit, MachineCsrFile, SupervisorCsrAddress}
import aethercore.sim.AetherCoreSimTop

/** Supervisor timer delivery without Sstc.
  *
  * The base privileged architecture requires M-mode software to be able to
  * set/clear mip.STIP when stimecmp is absent. OpenSBI uses exactly this path
  * to turn a machine timer event into a delegated supervisor timer interrupt.
  *
  * 无 Sstc 时，OpenSBI 依靠 M 态软件写 mip.STIP，把 MTIP 转交给 S 态。
  */
class LegacySupervisorTimerSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "legacy supervisor timer delivery"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x80
  private val supervisorTrap = base + 0x100
  private val stipMask = BigInt(1) << MachineCsrBit.SupervisorTimerInterrupt
  private val rv64SupervisorTimerCause =
    (BigInt(1) << 63) | BigInt(MachineCsrBit.SupervisorTimerInterrupt)

  private def initializeCsr(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
    dut.io.trapReturnSupervisor.poke(false.B)
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

  private def mretCsr(dut: MachineCsrFile): Unit = {
    dut.io.trapReturnSupervisor.poke(false.B)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
  }

  it should "make STIP software-writable in mip but read-only in sip when S-mode has no stimecmp" in {
    simulate(new MachineCsrFile(CoreProfiles.rv64imsuSoftware.isa)) { dut =>
      initializeCsr(dut)

      write(dut, MachineCsrAddress.Mideleg, stipMask)
      read(dut, MachineCsrAddress.Mideleg) shouldBe stipMask

      write(dut, MachineCsrAddress.Mie, stipMask)
      read(dut, MachineCsrAddress.Mie) shouldBe stipMask

      write(dut, MachineCsrAddress.Mip, stipMask)
      read(dut, MachineCsrAddress.Mip) shouldBe stipMask
      read(dut, SupervisorCsrAddress.Sip) shouldBe stipMask

      // M-mode can clear the execution-environment pending bit again.
      write(dut, MachineCsrAddress.Mip, 0)
      read(dut, MachineCsrAddress.Mip) shouldBe 0

      write(dut, MachineCsrAddress.Mip, stipMask)
      write(dut, MachineCsrAddress.Mstatus, BigInt("802", 16)) // MPP=S, SIE=1
      write(dut, MachineCsrAddress.Mepc, supervisorEntry)
      mretCsr(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)

      dut.io.supervisorTimerPending.get.expect(true.B)
      dut.io.supervisorTimerInterrupt.get.expect(true.B)

      // sip.STIP is a read-only view; S-mode cannot clear the environment event.
      write(dut, SupervisorCsrAddress.Sip, 0)
      read(dut, SupervisorCsrAddress.Sip) shouldBe stipMask
      dut.io.supervisorTimerPending.get.expect(true.B)
    }
  }

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(opcode & 0x7f)

  private def uType(imm20: Int, rd: Int): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x37)

  private def csr(address: Int, source: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  private val mret = BigInt("30200073", 16)
  private val ebreak = BigInt("00100073", 16)

  private def initializeCore(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
  }

  it should "retire a delegated RV64 supervisor timer interrupt from software STIP" in {
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 4) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 8) -> csr(SupervisorCsrAddress.Stvec, 1, 1, 0),
      (base + 12) -> iType(0x20, 0, 0, 2, 0x13),
      (base + 16) -> csr(MachineCsrAddress.Mideleg, 2, 1, 0),
      (base + 20) -> csr(MachineCsrAddress.Mie, 2, 1, 0),
      (base + 24) -> csr(MachineCsrAddress.Mip, 2, 1, 0),
      (base + 28) -> uType(0x80000, 3),
      (base + 32) -> iType(0x80, 3, 0, 3, 0x13),
      (base + 36) -> csr(MachineCsrAddress.Mepc, 3, 1, 0),
      (base + 40) -> uType(0x1, 4),
      (base + 44) -> iType(-2046, 4, 0, 4, 0x13), // x4 = MPP=S | SIE = 0x802
      (base + 48) -> csr(MachineCsrAddress.Mstatus, 4, 1, 0),
      (base + 52) -> mret,
      supervisorEntry -> iType(1, 0, 0, 10, 0x13),
      (supervisorEntry + 4) -> iType(1, 10, 0, 10, 0x13),
      supervisorTrap -> ebreak
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv64imsuSoftware, stopOnTrap = false)) { dut =>
      initializeCore(dut)
      var sawSupervisorTimer = false
      var cycles = 0

      while (!sawSupervisorTimer && cycles < 320) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.interrupt.peek().litToBoolean) {
          if (dut.io.commit.interruptCause.peek().litValue == rv64SupervisorTimerCause) {
            sawSupervisorTimer = true
          }
        }
      }

      sawSupervisorTimer shouldBe true
    }
  }
}
