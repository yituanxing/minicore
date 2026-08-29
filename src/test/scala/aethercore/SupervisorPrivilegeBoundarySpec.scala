package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{MachineExceptionCode, PrivilegeMode}
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile, SupervisorCsrAddress}
import aethercore.sim.AetherCoreSimTop

class SupervisorPrivilegeBoundarySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore Supervisor-mode V1 privilege boundaries"

  private val base = BigInt("80000000", 16)

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

  private def write(dut: MachineCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  private def mret(dut: MachineCsrFile): Unit = {
    dut.io.trapReturnSupervisor.poke(false.B)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
  }

  it should "never delegate a Machine-origin exception even when its cause is delegable" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)
      write(dut, MachineCsrAddress.Mtvec, base + 0x100)
      write(dut, SupervisorCsrAddress.Stvec, base + 0x200)
      write(dut, MachineCsrAddress.Medeleg, BigInt(1) << MachineExceptionCode.IllegalInstruction)

      dut.io.trapPc.poke((base + 0x40).U)
      dut.io.trapCause.poke(MachineExceptionCode.IllegalInstruction.U)
      dut.io.trapValue.poke(BigInt("deadbeef", 16).U)
      dut.io.trapEnter.poke(true.B)
      dut.io.trapDelegatedToSupervisor.expect(false.B)
      dut.io.trapVector.expect((base + 0x100).U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)

      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)
    }
  }

  it should "route an undelegated Supervisor exception back to Machine mode" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)
      write(dut, MachineCsrAddress.Mtvec, base + 0x100)
      write(dut, SupervisorCsrAddress.Stvec, base + 0x200)
      write(dut, MachineCsrAddress.Medeleg, 0)
      write(dut, MachineCsrAddress.Mepc, base + 0x40)
      write(dut, MachineCsrAddress.Mstatus, BigInt("800", 16)) // MPP=S
      mret(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)

      dut.io.trapPc.poke((base + 0x44).U)
      dut.io.trapCause.poke(MachineExceptionCode.EnvironmentCallFromS.U)
      dut.io.trapValue.poke(0.U)
      dut.io.trapEnter.poke(true.B)
      dut.io.trapDelegatedToSupervisor.expect(false.B)
      dut.io.trapVector.expect((base + 0x100).U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)

      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)
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

  private val mretInst = BigInt("30200073", 16)
  private val sretInst = BigInt("10200073", 16)
  private val ebreak = BigInt("00100073", 16)

  private def initializeCore(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
  }

  it should "trap a Supervisor access to a Machine CSR as illegal instruction in M mode" in {
    val supervisorEntry = base + 0x80
    val machineTrap = base + 0x100
    val illegalPc = supervisorEntry
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 4) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 8) -> csr(MachineCsrAddress.Mtvec, 1, 1, 0),
      (base + 12) -> uType(0x80000, 2),
      (base + 16) -> iType(0x80, 2, 0, 2, 0x13),
      (base + 20) -> csr(MachineCsrAddress.Mepc, 2, 1, 0),
      (base + 24) -> uType(0x1, 3),
      (base + 28) -> iType(-2048, 3, 0, 3, 0x13), // MPP=S
      (base + 32) -> csr(MachineCsrAddress.Mstatus, 3, 1, 0),
      (base + 36) -> mretInst,
      illegalPc -> csr(MachineCsrAddress.Mstatus, 0, 2, 5), // csrr x5,mstatus from S
      (illegalPc + 4) -> ebreak,
      machineTrap -> csr(MachineCsrAddress.Mcause, 0, 2, 6),
      (machineTrap + 4) -> csr(MachineCsrAddress.Mepc, 0, 2, 7),
      (machineTrap + 8) -> iType(55, 0, 0, 8, 0x13),
      (machineTrap + 12) -> ebreak
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imsuSoftware, stopOnTrap = false)) { dut =>
      initializeCore(dut)
      var sawIllegal = false
      var sawMachineHandler = false
      var cycles = 0

      while (!sawMachineHandler && cycles < 240) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (dut.io.commit.exception.peek().litToBoolean && pc == illegalPc) {
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
            dut.io.commit.exceptionValue.expect(csr(MachineCsrAddress.Mstatus, 0, 2, 5).U)
            sawIllegal = true
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean && pc == machineTrap + 8) {
            dut.io.commit.rd.expect(8.U)
            dut.io.commit.rdData.expect(55.U)
            sawMachineHandler = true
          }
        }
      }

      sawIllegal shouldBe true
      sawMachineHandler shouldBe true
    }
  }

  it should "trap U-mode SRET as illegal instruction and delegate that fault to S mode" in {
    val userEntry = base + 0x80
    val supervisorTrap = base + 0x100
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 4) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 8) -> csr(SupervisorCsrAddress.Stvec, 1, 1, 0),
      (base + 12) -> iType(1 << MachineExceptionCode.IllegalInstruction, 0, 0, 2, 0x13),
      (base + 16) -> csr(MachineCsrAddress.Medeleg, 2, 1, 0),
      (base + 20) -> uType(0x80000, 3),
      (base + 24) -> iType(0x80, 3, 0, 3, 0x13),
      (base + 28) -> csr(MachineCsrAddress.Mepc, 3, 1, 0),
      (base + 32) -> csr(MachineCsrAddress.Mstatus, 0, 1, 0), // MPP=U
      (base + 36) -> mretInst,
      userEntry -> sretInst,
      (userEntry + 4) -> ebreak,
      supervisorTrap -> csr(SupervisorCsrAddress.Scause, 0, 2, 6),
      (supervisorTrap + 4) -> csr(SupervisorCsrAddress.Sepc, 0, 2, 7),
      (supervisorTrap + 8) -> iType(66, 0, 0, 9, 0x13),
      (supervisorTrap + 12) -> ebreak
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imsuSoftware, stopOnTrap = false)) { dut =>
      initializeCore(dut)
      var sawIllegalSret = false
      var sawSupervisorHandler = false
      var cycles = 0

      while (!sawSupervisorHandler && cycles < 240) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (dut.io.commit.exception.peek().litToBoolean && pc == userEntry) {
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
            dut.io.commit.exceptionValue.expect(sretInst.U)
            sawIllegalSret = true
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean && pc == supervisorTrap + 8) {
            dut.io.commit.rd.expect(9.U)
            dut.io.commit.rdData.expect(66.U)
            sawSupervisorHandler = true
          }
        }
      }

      sawIllegalSret shouldBe true
      sawSupervisorHandler shouldBe true
    }
  }
}
