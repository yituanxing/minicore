package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class AetherCoreInterruptPlatformSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore interrupt-capable simulation platform"

  private val base = BigInt("80000000", 16)
  private val handler = base + 0x100
  private val resumePc = base + 0x48

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

  it should "map UART RX and PLIC MMIO and deliver a precise Machine external interrupt" in {
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 0x04) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 0x08) -> csr(0x305, 1, 1, 0),
      (base + 0x0c) -> uType(0x10000, 10),
      (base + 0x10) -> iType(1, 0, 0, 5, 0x13),
      // UART RX control is intentionally placed at 0x10000108 so the legacy
      // self-check exit register remains exclusively mapped at 0x10000008.
      (base + 0x14) -> sType(0x108, 5, 10, 2),
      (base + 0x18) -> uType(0x0c000, 11),
      (base + 0x1c) -> sType(4, 5, 11, 2),
      (base + 0x20) -> uType(0x0c002, 12),
      (base + 0x24) -> sType(0, 5, 12, 2),
      (base + 0x28) -> uType(0x0c200, 13),
      (base + 0x2c) -> sType(0, 0, 13, 2),
      (base + 0x30) -> uType(0x1, 2),
      (base + 0x34) -> iType(-0x800, 2, 0, 2, 0x13),
      (base + 0x38) -> csr(0x304, 2, 1, 0),
      (base + 0x3c) -> iType(8, 0, 0, 3, 0x13),
      (base + 0x40) -> csr(0x300, 3, 1, 0),
      (base + 0x44) -> BigInt("10500073", 16),
      resumePc -> BigInt("0000006f", 16),
      handler -> csr(0x342, 0, 2, 6),
      (handler + 0x04) -> csr(0x341, 0, 2, 7),
      (handler + 0x08) -> BigInt("0000006f", 16)
    )

    simulate(new AetherCoreSimTop(
      CoreProfiles.rv32imSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = true
    )) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)
      dut.io.memFault.poke(false.B)
      dut.io.rxValid.get.poke(false.B)
      dut.io.rxByte.get.poke(0.U)

      var cycles = 0
      var sawWfi = false
      var injected = false
      var sawExternalLevel = false
      var sawInterrupt = false
      var sawCause = false
      var sawEpc = false
      var externalMemoryRequests = 0
      var uartTxWrites = 0
      var exitWrites = 0

      while ((!sawCause || !sawEpc) && cycles < 500) {
        val fetchPc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        if (dut.io.memValid.peek().litToBoolean) externalMemoryRequests += 1
        if (dut.io.uartValid.peek().litToBoolean) uartTxWrites += 1
        if (dut.io.exitValid.peek().litToBoolean) exitWrites += 1

        if (!injected && dut.io.halted.peek().litToBoolean) {
          sawWfi = true
          dut.io.rxReady.get.expect(true.B)
          dut.io.rxByte.get.poke(BigInt("5a", 16).U)
          dut.io.rxValid.get.poke(true.B)
          injected = true
        }

        if (dut.io.externalInterrupt.get.peek().litToBoolean) {
          sawExternalLevel = true
        }

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.interrupt.peek().litToBoolean) {
            dut.io.commit.interruptCause.expect(BigInt("8000000b", 16).U)
            dut.io.commit.interruptPc.expect(resumePc.U)
            sawInterrupt = true
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean) {
            val rd = dut.io.commit.rd.peek().litValue
            val value = dut.io.commit.rdData.peek().litValue
            if (rd == 6 && value == BigInt("8000000b", 16)) sawCause = true
            if (rd == 7 && value == resumePc) sawEpc = true
          }
        }

        dut.clock.step()
        cycles += 1
        if (injected) dut.io.rxValid.get.poke(false.B)
      }

      sawWfi shouldBe true
      injected shouldBe true
      sawExternalLevel shouldBe true
      sawInterrupt shouldBe true
      sawCause shouldBe true
      sawEpc shouldBe true
      externalMemoryRequests shouldBe 0
      uartTxWrites shouldBe 0
      exitWrites shouldBe 0
    }
  }
}
