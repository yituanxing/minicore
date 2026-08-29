package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, Rv32SstcCsrAddress, SupervisorCsrAddress}
import aethercore.sim.AetherCoreSimTop

class Rv32SstcCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV32 Sstc path"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x80
  private val supervisorTrap = base + 0x100
  private val supervisorTimerCause = BigInt("80000005", 16)

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

  private def csr(address: Int, source: Int, funct3: Int = 1, rd: Int = 0): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  private val mret = BigInt("30200073", 16)
  private val loop = BigInt("0000006f", 16)
  private val ebreak = BigInt("00100073", 16)

  private def initialize(dut: AetherCoreSimTop): Unit = {
    dut.io.imemInst.poke(BigInt("00000013", 16).U)
    dut.io.imemFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
    dut.io.memFault.poke(false.B)
    dut.io.ptwReady.get.poke(true.B)
    dut.io.ptwRdata.get.poke(0.U)
    dut.io.ptwFault.get.poke(false.B)
  }

  it should "retire Sstc CSRs in S mode and deliver a delegated supervisor timer interrupt" in {
    val program = Map(
      // Firmware-owned Sstc gates before MRET.
      base -> iType(0x20, 0, 0, 1, 0x13),
      (base + 0x04) -> csr(MachineCsrAddress.Mideleg, 1),
      (base + 0x08) -> iType(0x2, 0, 0, 1, 0x13),
      (base + 0x0c) -> csr(MachineCsrAddress.Mcounteren, 1),
      (base + 0x10) -> uType(0x80000, 1),
      (base + 0x14) -> csr(MachineCsrAddress.Menvcfgh, 1),
      (base + 0x18) -> uType(0x80000, 2),
      (base + 0x1c) -> iType(0x100, 2, 0, 2, 0x13),
      (base + 0x20) -> csr(SupervisorCsrAddress.Stvec, 2),
      (base + 0x24) -> uType(0x80000, 3),
      (base + 0x28) -> iType(0x80, 3, 0, 3, 0x13),
      (base + 0x2c) -> csr(MachineCsrAddress.Mepc, 3),
      (base + 0x30) -> uType(0x1, 4),
      (base + 0x34) -> iType(-2048, 4, 0, 4, 0x13),
      (base + 0x38) -> csr(MachineCsrAddress.Mstatus, 4),
      (base + 0x3c) -> mret,

      // S-mode path mirrors the NuttX timer initialization contract.
      supervisorEntry -> csr(Rv32SstcCsrAddress.Time, 0, funct3 = 2, rd = 6),
      (supervisorEntry + 0x04) -> iType(-1, 0, 0, 5, 0x13),
      (supervisorEntry + 0x08) -> csr(SupervisorCsrAddress.Stimecmp, 5),
      (supervisorEntry + 0x0c) -> csr(SupervisorCsrAddress.Stimecmph, 0),
      (supervisorEntry + 0x10) -> iType(500, 0, 0, 5, 0x13),
      (supervisorEntry + 0x14) -> csr(SupervisorCsrAddress.Stimecmp, 5),
      (supervisorEntry + 0x18) -> iType(0x20, 0, 0, 7, 0x13),
      (supervisorEntry + 0x1c) -> csr(SupervisorCsrAddress.Sie, 7),
      (supervisorEntry + 0x20) -> iType(0x2, 0, 0, 7, 0x13),
      (supervisorEntry + 0x24) -> csr(SupervisorCsrAddress.Sstatus, 7, funct3 = 2),
      (supervisorEntry + 0x28) -> loop,

      supervisorTrap -> ebreak
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imasuSv32Software, stopOnTrap = false, stopOnWfi = false)) { dut =>
      initialize(dut)
      var cycles = 0
      var sawTimeRead = false
      var sawStimecmpWrite = false
      var sawSupervisorTimer = false

      while (!sawSupervisorTimer && cycles < 1200) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == supervisorEntry) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(6.U)
            sawTimeRead = true
          }
          if (pc == supervisorEntry + 0x14) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.inst.expect(csr(SupervisorCsrAddress.Stimecmp, 5).U)
            sawStimecmpWrite = true
          }
          if (dut.io.commit.interrupt.peek().litToBoolean) {
            dut.io.commit.interruptCause.expect(supervisorTimerCause.U)
            sawSupervisorTimer = true
          }
        }
      }

      sawTimeRead shouldBe true
      sawStimecmpWrite shouldBe true
      sawSupervisorTimer shouldBe true
      cycles should be < 1200
    }
  }
}
