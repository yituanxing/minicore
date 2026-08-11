package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{MachineExceptionCode, PrivilegeMode}
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile, SupervisorCsrAddress}
import aethercore.sim.AetherCoreSimTop

class SupervisorModeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore Supervisor mode V1"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x80
  private val supervisorTrap = base + 0x100

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

  private def sretCsr(dut: MachineCsrFile): Unit = {
    dut.io.trapReturnSupervisor.poke(true.B)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
    dut.io.trapReturnSupervisor.poke(false.B)
  }

  it should "advertise S mode and expose only implemented delegation state" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)

      read(dut, MachineCsrAddress.Misa) shouldBe BigInt("40141100", 16)

      write(dut, MachineCsrAddress.Medeleg, BigInt("ffffffff", 16))
      read(dut, MachineCsrAddress.Medeleg) shouldBe BigInt("000003ff", 16)

      write(dut, MachineCsrAddress.Mideleg, BigInt("ffffffff", 16))
      read(dut, MachineCsrAddress.Mideleg) shouldBe 0
    }
  }

  it should "delegate instruction-address-misaligned metadata from U mode to S mode" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)
      val target = supervisorEntry + 2

      write(dut, SupervisorCsrAddress.Stvec, supervisorTrap)
      write(
        dut,
        MachineCsrAddress.Medeleg,
        BigInt(1) << MachineExceptionCode.InstructionAddressMisaligned
      )
      write(dut, MachineCsrAddress.Mepc, supervisorEntry)
      write(dut, MachineCsrAddress.Mstatus, BigInt("00000080", 16)) // MPP=U, MPIE=1
      mretCsr(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)

      dut.io.trapPc.poke(supervisorEntry.U)
      dut.io.trapCause.poke(MachineExceptionCode.InstructionAddressMisaligned.U)
      dut.io.trapValue.poke(target.U)
      dut.io.trapEnter.poke(true.B)
      dut.io.trapDelegatedToSupervisor.expect(true.B)
      dut.io.trapVector.expect(supervisorTrap.U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)

      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      read(dut, SupervisorCsrAddress.Sepc) shouldBe supervisorEntry
      read(dut, SupervisorCsrAddress.Scause) shouldBe MachineExceptionCode.InstructionAddressMisaligned
      read(dut, SupervisorCsrAddress.Stval) shouldBe target
    }
  }

  it should "enter S mode, delegate an S ECALL, and return with SRET semantics" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)

      write(dut, SupervisorCsrAddress.Stvec, supervisorTrap)
      write(dut, MachineCsrAddress.Medeleg, BigInt(1) << MachineExceptionCode.EnvironmentCallFromS)
      write(dut, MachineCsrAddress.Mepc, supervisorEntry)
      write(dut, MachineCsrAddress.Mstatus, BigInt("00000880", 16)) // MPP=S, MPIE=1

      dut.io.returnPc.expect(supervisorEntry.U)
      mretCsr(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00000088", 16)

      dut.io.trapPc.poke((supervisorEntry + 4).U)
      dut.io.trapCause.poke(MachineExceptionCode.EnvironmentCallFromS.U)
      dut.io.trapValue.poke(0.U)
      dut.io.trapEnter.poke(true.B)
      dut.io.trapDelegatedToSupervisor.expect(true.B)
      dut.io.trapVector.expect(supervisorTrap.U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)

      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      read(dut, SupervisorCsrAddress.Sepc) shouldBe supervisorEntry + 4
      read(dut, SupervisorCsrAddress.Scause) shouldBe MachineExceptionCode.EnvironmentCallFromS
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe BigInt("00000100", 16)
      dut.io.returnPc.expect((supervisorEntry + 4).U)

      sretCsr(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe BigInt("00000020", 16)
    }
  }

  it should "delegate a U ECALL to S mode and SRET back to U" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)

      write(dut, SupervisorCsrAddress.Stvec, supervisorTrap)
      write(dut, MachineCsrAddress.Medeleg, BigInt(1) << MachineExceptionCode.EnvironmentCallFromU)
      write(dut, MachineCsrAddress.Mepc, supervisorEntry)
      write(dut, MachineCsrAddress.Mstatus, BigInt("00000080", 16)) // MPP=U, MPIE=1
      mretCsr(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)

      dut.io.trapPc.poke(supervisorEntry.U)
      dut.io.trapCause.poke(MachineExceptionCode.EnvironmentCallFromU.U)
      dut.io.trapValue.poke(0.U)
      dut.io.trapEnter.poke(true.B)
      dut.io.trapDelegatedToSupervisor.expect(true.B)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)

      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe 0
      dut.io.returnPc.expect(supervisorEntry.U)

      sretCsr(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe BigInt("00000020", 16)
    }
  }

  it should "apply SRET state restoration when SRET is executed from M mode" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)

      write(dut, SupervisorCsrAddress.Sepc, supervisorEntry)
      write(dut, SupervisorCsrAddress.Sstatus, BigInt("00000120", 16)) // SPP=S, SPIE=1
      dut.io.trapReturnSupervisor.poke(true.B)
      dut.io.returnPc.expect(supervisorEntry.U)
      sretCsr(dut)

      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe BigInt("00000022", 16)
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

  private val ecall = BigInt("00000073", 16)
  private val ebreak = BigInt("00100073", 16)
  private val mret = BigInt("30200073", 16)
  private val sret = BigInt("10200073", 16)

  private def initializeCore(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
  }

  it should "close the M-to-S delegated ECALL and SRET loop in the full core" in {
    val sEcallPc = supervisorEntry + 4
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 4) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 8) -> csr(SupervisorCsrAddress.Stvec, 1, 1, 0),
      (base + 12) -> iType(0x200, 0, 0, 2, 0x13),
      (base + 16) -> csr(MachineCsrAddress.Medeleg, 2, 1, 0),
      (base + 20) -> uType(0x80000, 3),
      (base + 24) -> iType(0x80, 3, 0, 3, 0x13),
      (base + 28) -> csr(MachineCsrAddress.Mepc, 3, 1, 0),
      (base + 32) -> uType(0x1, 4),
      (base + 36) -> iType(-2048, 4, 0, 4, 0x13), // x4 = MPP=S (0x800)
      (base + 40) -> csr(MachineCsrAddress.Mstatus, 4, 1, 0),
      (base + 44) -> mret,

      supervisorEntry -> iType(41, 0, 0, 10, 0x13),
      sEcallPc -> ecall,
      (supervisorEntry + 8) -> iType(1, 10, 0, 11, 0x13),
      (supervisorEntry + 12) -> ebreak,

      supervisorTrap -> csr(SupervisorCsrAddress.Scause, 0, 2, 5),
      (supervisorTrap + 4) -> csr(SupervisorCsrAddress.Sepc, 0, 2, 6),
      (supervisorTrap + 8) -> iType(4, 6, 0, 6, 0x13),
      (supervisorTrap + 12) -> csr(SupervisorCsrAddress.Sepc, 6, 1, 0),
      (supervisorTrap + 16) -> iType(1, 10, 0, 10, 0x13),
      (supervisorTrap + 20) -> sret
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imsuSoftware, stopOnTrap = false)) { dut =>
      initializeCore(dut)
      var sawSupervisorEcall = false
      var sawScause = false
      var sawReturnedResult = false
      var cycles = 0

      while (!sawReturnedResult && cycles < 320) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (dut.io.commit.exception.peek().litToBoolean && pc == sEcallPc) {
            dut.io.commit.inst.expect(ecall.U)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.EnvironmentCallFromS.U)
            sawSupervisorEcall = true
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean && pc == supervisorTrap) {
            dut.io.commit.rd.expect(5.U)
            dut.io.commit.rdData.expect(MachineExceptionCode.EnvironmentCallFromS.U)
            sawScause = true
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean && pc == supervisorEntry + 8) {
            dut.io.commit.rd.expect(11.U)
            dut.io.commit.rdData.expect(43.U)
            sawReturnedResult = true
          }
        }
      }

      sawSupervisorEcall shouldBe true
      sawScause shouldBe true
      sawReturnedResult shouldBe true
    }
  }

  it should "execute SRET from M mode and retire in S mode in the full core" in {
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 4) -> iType(0x80, 1, 0, 1, 0x13),
      (base + 8) -> csr(SupervisorCsrAddress.Sepc, 1, 1, 0),
      (base + 12) -> iType(0x100, 0, 0, 2, 0x13), // SPP=S
      (base + 16) -> csr(SupervisorCsrAddress.Sstatus, 2, 1, 0),
      (base + 20) -> sret,
      supervisorEntry -> iType(77, 0, 0, 7, 0x13),
      (supervisorEntry + 4) -> ebreak
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imsuSoftware, stopOnTrap = false)) { dut =>
      initializeCore(dut)
      var sawSupervisorRetirement = false
      var cycles = 0

      while (!sawSupervisorRetirement && cycles < 200) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (
          dut.io.commit.valid.peek().litToBoolean &&
          dut.io.commit.rdWrite.peek().litToBoolean &&
          dut.io.commit.pc.peek().litValue == supervisorEntry
        ) {
          dut.io.commit.rd.expect(7.U)
          dut.io.commit.rdData.expect(77.U)
          sawSupervisorRetirement = true
        }
      }

      sawSupervisorRetirement shouldBe true
    }
  }
}
