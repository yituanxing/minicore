package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

/** RV64 userspace syscall boundary required by Linux:
  * M bootstraps S, S enters U through SRET, U ECALL is delegated back to S,
  * and the S handler advances sepc before returning to U.
  */
class RV64UserEcallSupervisorSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64 U-mode ECALL delegated to Supervisor"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x40
  private val userEntry = base + 0x80
  private val userEcallPc = userEntry + 4
  private val supervisorHandler = base + 0x100
  private val mret = BigInt("30200073", 16)
  private val sret = BigInt("10200073", 16)
  private val ecall = BigInt("00000073", 16)

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

  private def auipc(imm20: Int, rd: Int): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x17)

  private def csr(address: Int, source: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  it should "delegate a U-mode ECALL to S-mode and resume userspace after SRET" in {
    val program = Map(
      // stvec = base + 0x100.
      base -> auipc(0, 1),
      (base + 0x04) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 0x08) -> csr(0x105, 1, 1, 0),
      // Delegate EnvironmentCallFromU (cause 8) to Supervisor mode.
      (base + 0x0c) -> iType(0x100, 0, 0, 2, 0x13),
      (base + 0x10) -> csr(0x302, 2, 1, 0),
      // mepc = supervisorEntry = (base + 0x14) + 0x2c.
      (base + 0x14) -> auipc(0, 3),
      (base + 0x18) -> iType(0x2c, 3, 0, 3, 0x13),
      (base + 0x1c) -> csr(0x341, 3, 1, 0),
      // mstatus.MPP=S, then MRET into Supervisor mode.
      (base + 0x20) -> uType(0x1, 4),
      (base + 0x24) -> iType(-2048, 4, 0, 4, 0x13),
      (base + 0x28) -> csr(0x300, 4, 1, 0),
      (base + 0x2c) -> mret,

      // Supervisor enters userspace: sepc=userEntry, SPP=U, SRET.
      supervisorEntry -> auipc(0, 7),
      (supervisorEntry + 0x04) -> iType(0x40, 7, 0, 7, 0x13),
      (supervisorEntry + 0x08) -> csr(0x141, 7, 1, 0),
      (supervisorEntry + 0x0c) -> csr(0x100, 0, 1, 0),
      (supervisorEntry + 0x10) -> sret,

      // Minimal userspace syscall and post-return continuation.
      userEntry -> iType(41, 0, 0, 10, 0x13),
      userEcallPc -> ecall,
      (userEntry + 0x08) -> iType(1, 10, 0, 11, 0x13),
      (userEntry + 0x0c) -> BigInt("0000006f", 16),

      // Supervisor syscall handler records cause/epc, skips ECALL, mutates a0,
      // then returns to the original U-mode context.
      supervisorHandler -> csr(0x142, 0, 2, 5),
      (supervisorHandler + 0x04) -> csr(0x141, 0, 2, 6),
      (supervisorHandler + 0x08) -> iType(4, 6, 0, 6, 0x13),
      (supervisorHandler + 0x0c) -> csr(0x141, 6, 1, 0),
      (supervisorHandler + 0x10) -> iType(1, 10, 0, 10, 0x13),
      (supervisorHandler + 0x14) -> sret
    )

    simulate(new AetherCore(CoreProfiles.rv64imsuSoftware)) { dut =>
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)

      var sawUserEcall = false
      var sawSupervisorCause = false
      var sawSupervisorEpc = false
      var sawSret = false
      var sawUserResult = false
      var cycles = 0

      while (!sawUserResult && cycles < 400) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          val instruction = dut.io.commit.inst.peek().litValue

          if (dut.io.commit.exception.peek().litToBoolean && pc == userEcallPc) {
            dut.io.commit.inst.expect(ecall.U)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.EnvironmentCallFromU.U)
            dut.io.commit.exceptionValue.expect(0.U)
            sawUserEcall = true
          }

          if (instruction == sret && pc == supervisorHandler + 0x14)
            sawSret = true

          if (dut.io.commit.rdWrite.peek().litToBoolean) {
            val rd = dut.io.commit.rd.peek().litValue
            val value = dut.io.commit.rdData.peek().litValue
            if (pc == supervisorHandler && rd == 5 &&
                value == BigInt(MachineExceptionCode.EnvironmentCallFromU))
              sawSupervisorCause = true
            if (pc == supervisorHandler + 0x04 && rd == 6 && value == userEcallPc)
              sawSupervisorEpc = true
            if (pc == userEntry + 0x08 && rd == 11 && value == 43)
              sawUserResult = true
          }
        }

        dut.clock.step()
        cycles += 1
      }

      sawUserEcall shouldBe true
      sawSupervisorCause shouldBe true
      sawSupervisorEpc shouldBe true
      sawSret shouldBe true
      sawUserResult shouldBe true
    }
  }
}
