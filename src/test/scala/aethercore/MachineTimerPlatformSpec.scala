package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineInterruptCode
import aethercore.sim.AetherCoreRV32IMTimerSimTop

class MachineTimerPlatformSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV32IM Machine timer platform"

  private val base = BigInt("80000000", 16)
  private val handler = base + 0x80
  private val timerCause = MachineInterruptCode.cause(32, MachineInterruptCode.Timer)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(opcode & 0x7f)

  private def sType(imm: Int, rs2: Int, rs1: Int, funct3: Int): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(imm & 0x1f) << 7) |
      BigInt(0x23)

  private def bType(imm: Int, rs2: Int, rs1: Int, funct3: Int): BigInt = {
    require((imm & 1) == 0)
    val encoded = imm & 0x1fff
    (BigInt((encoded >> 12) & 1) << 31) |
      (BigInt((encoded >> 5) & 0x3f) << 25) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt((encoded >> 1) & 0xf) << 8) |
      (BigInt((encoded >> 11) & 1) << 7) |
      BigInt(0x63)
  }

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

  it should "program mtimecmp, take MTIP, clear it, and return with MRET" in {
    val waitPc = base + 0x34
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 0x04) -> iType(0x80, 1, 0, 1, 0x13),
      (base + 0x08) -> csr(0x305, 1, 1, 0),          // csrw mtvec, x1
      (base + 0x0c) -> csr(0x340, 0, 1, 0),          // csrw mscratch, x0
      (base + 0x10) -> uType(0x02004, 2),            // mtimecmp base
      (base + 0x14) -> iType(-1, 0, 0, 3, 0x13),
      (base + 0x18) -> sType(0, 3, 2, 2),            // low <- -1
      (base + 0x1c) -> sType(4, 0, 2, 2),            // high <- 0
      (base + 0x20) -> iType(200, 0, 0, 3, 0x13),
      (base + 0x24) -> sType(0, 3, 2, 2),            // low <- 200
      (base + 0x28) -> iType(0x80, 0, 0, 3, 0x13),
      (base + 0x2c) -> csr(0x304, 3, 1, 0),          // csrw mie, x3
      (base + 0x30) -> iType(8, 0, 0, 3, 0x13),
      waitPc -> csr(0x300, 3, 1, 0),                 // csrw mstatus, x3
      (base + 0x38) -> csr(0x340, 0, 2, 6),          // csrr x6, mscratch
      (base + 0x3c) -> bType(-4, 0, 6, 0),           // beq x6, x0, wait read
      (base + 0x40) -> uType(0x10000, 9),
      (base + 0x44) -> sType(8, 0, 9, 2),            // exit 0

      handler -> csr(0x342, 0, 2, 5),                // csrr x5, mcause
      (handler + 0x04) -> csr(0x340, 0, 2, 6),       // csrr x6, mscratch
      (handler + 0x08) -> iType(1, 6, 0, 6, 0x13),
      (handler + 0x0c) -> csr(0x340, 6, 1, 0),       // csrw mscratch, x6
      (handler + 0x10) -> uType(0x02004, 2),
      (handler + 0x14) -> iType(-1, 0, 0, 3, 0x13),
      (handler + 0x18) -> sType(0, 3, 2, 2),
      (handler + 0x1c) -> sType(4, 3, 2, 2),         // mtimecmp <- all ones
      (handler + 0x20) -> BigInt("30200073", 16),   // mret
      (handler + 0x24) -> sType(0, 3, 0, 2)          // forbidden younger Store
    )

    simulate(new AetherCoreRV32IMTimerSimTop) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)
      dut.io.memFault.poke(false.B)

      var sawInterrupt = false
      var sawMret = false
      var causeRead: Option[BigInt] = None
      var externalStoreEscaped = false
      var exitSeen = false
      var cycles = 0

      while (!exitSeen && cycles < 800) {
        val fetchPc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.exception.peek().litToBoolean) {
            dut.io.commit.inst.expect(0.U)
            dut.io.commit.exceptionCause.expect(timerCause.U)
            dut.io.commit.exceptionValue.expect(0.U)
            sawInterrupt = true
          }
          if (dut.io.commit.inst.peek().litValue == BigInt("30200073", 16)) {
            dut.io.commit.exception.expect(false.B)
            sawMret = true
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean &&
              dut.io.commit.rd.peek().litValue == 5) {
            causeRead = Some(dut.io.commit.rdData.peek().litValue)
          }
        }

        if (dut.io.memValid.peek().litToBoolean && dut.io.memWrite.peek().litToBoolean) {
          externalStoreEscaped = true
        }
        if (dut.io.exitValid.peek().litToBoolean) exitSeen = true

        dut.clock.step()
        cycles += 1
      }

      exitSeen shouldBe true
      sawInterrupt shouldBe true
      sawMret shouldBe true
      causeRead shouldBe Some(timerCause)
      externalStoreEscaped shouldBe false
      dut.io.halted.expect(false.B)
    }
  }
}
