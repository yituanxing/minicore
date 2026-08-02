package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class UserModeCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore User mode"

  private val base = BigInt("80000000", 16)
  private val userEntry = base + 0x80
  private val trapHandler = base + 0x100

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

  private def bootstrap(userInstruction: BigInt): Map[BigInt, BigInt] = Map(
    base -> uType(0x80000, 1),
    (base + 4) -> iType(0x100, 1, 0, 1, 0x13),
    (base + 8) -> csr(0x305, 1, 1, 0),       // csrw mtvec, x1
    (base + 12) -> uType(0x80000, 2),
    (base + 16) -> iType(0x80, 2, 0, 2, 0x13),
    (base + 20) -> csr(0x341, 2, 1, 0),      // csrw mepc, x2
    (base + 24) -> csr(0x300, 0, 1, 0),      // csrw mstatus, x0: MPP=U
    (base + 28) -> mret,
    userEntry -> userInstruction,
    (userEntry + 4) -> ebreak
  )

  private def initialize(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
  }

  it should "enter U-mode, handle ECALL cause 8, and return a syscall value" in {
    val userEcallPc = userEntry + 8
    val program = bootstrap(iType(41, 0, 0, 10, 0x13)) ++ Map(
      (userEntry + 4) -> iType(1, 0, 0, 17, 0x13), // a7 = SYS_TEST
      userEcallPc -> ecall,
      (userEntry + 12) -> iType(1, 10, 0, 11, 0x13),
      (userEntry + 16) -> ebreak,
      trapHandler -> csr(0x342, 0, 2, 5),           // csrr x5, mcause
      (trapHandler + 4) -> csr(0x341, 0, 2, 6),     // csrr x6, mepc
      (trapHandler + 8) -> iType(4, 6, 0, 6, 0x13),
      (trapHandler + 12) -> csr(0x341, 6, 1, 0),    // csrw mepc, x6
      (trapHandler + 16) -> iType(1, 10, 0, 10, 0x13),
      (trapHandler + 20) -> mret
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuSoftware, stopOnTrap = false)) { dut =>
      initialize(dut)
      var sawUserEcall = false
      var sawCauseRead = false
      var sawUserResult = false
      var cycles = 0

      while (!sawUserResult && cycles < 300) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (dut.io.commit.exception.peek().litToBoolean && pc == userEcallPc) {
            dut.io.commit.inst.expect(ecall.U)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.EnvironmentCallFromU.U)
            dut.io.commit.exceptionValue.expect(0.U)
            sawUserEcall = true
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean && pc == trapHandler) {
            dut.io.commit.rd.expect(5.U)
            dut.io.commit.rdData.expect(MachineExceptionCode.EnvironmentCallFromU.U)
            sawCauseRead = true
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean && pc == userEntry + 12) {
            dut.io.commit.rd.expect(11.U)
            dut.io.commit.rdData.expect(43.U)
            sawUserResult = true
          }
        }
      }

      sawUserEcall shouldBe true
      sawCauseRead shouldBe true
      sawUserResult shouldBe true
    }
  }

  it should "reject a Machine CSR read executed in U-mode" in {
    val instruction = csr(0x300, 0, 2, 3) // csrr x3, mstatus
    val program = bootstrap(instruction)

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuSoftware, stopOnTrap = false)) { dut =>
      initialize(dut)
      var sawIllegal = false
      var cycles = 0

      while (!sawIllegal && cycles < 160) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(userEntry.U)
          dut.io.commit.inst.expect(instruction.U)
          dut.io.commit.rdWrite.expect(false.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
          dut.io.commit.exceptionValue.expect(instruction.U)
          sawIllegal = true
        }
      }

      sawIllegal shouldBe true
    }
  }

  it should "reject MRET executed in U-mode" in {
    val program = bootstrap(mret)

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuSoftware, stopOnTrap = false)) { dut =>
      initialize(dut)
      var sawIllegal = false
      var cycles = 0

      while (!sawIllegal && cycles < 160) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(userEntry.U)
          dut.io.commit.inst.expect(mret.U)
          dut.io.commit.rdWrite.expect(false.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
          dut.io.commit.exceptionValue.expect(mret.U)
          sawIllegal = true
        }
      }

      sawIllegal shouldBe true
    }
  }
}
