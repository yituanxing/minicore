package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class WaitForInterruptCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore WFI"

  private val base = BigInt("80000000", 16)
  private val handler = base + 0x80
  private val wfiPc = base + 0x20
  private val resumePc = wfiPc + 4
  private val wfi = BigInt("10500073", 16)

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

  it should "hold WFI at retirement and wake on an enabled Machine timer interrupt" in {
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 0x04) -> iType(0x80, 1, 0, 1, 0x13), // handler address
      (base + 0x08) -> csr(0x305, 1, 1, 0),         // csrw mtvec, x1
      (base + 0x0c) -> iType(0x80, 0, 0, 2, 0x13), // MTIE
      (base + 0x10) -> csr(0x304, 2, 1, 0),         // csrw mie, x2
      (base + 0x14) -> iType(8, 0, 0, 3, 0x13),    // MIE
      (base + 0x18) -> csr(0x300, 3, 1, 0),         // csrw mstatus, x3
      (base + 0x1c) -> iType(0, 0, 0, 4, 0x13),
      wfiPc -> wfi,
      resumePc -> iType(1, 4, 0, 4, 0x13),
      (base + 0x28) -> BigInt("0000006f", 16),     // jal x0, 0
      handler -> csr(0x342, 0, 2, 6),              // csrr x6, mcause
      (handler + 0x04) -> csr(0x341, 0, 2, 7),     // csrr x7, mepc
      (handler + 0x08) -> BigInt("30200073", 16)   // mret
    )

    simulate(new AetherCore(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)

      var cycles = 0
      while (!dut.io.halted.peek().litToBoolean && cycles < 80) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)
        dut.clock.step()
        cycles += 1
      }

      dut.io.halted.expect(true.B)
      dut.io.commit.valid.expect(false.B)
      dut.io.dmem.valid.expect(false.B)

      // halted becomes visible combinationally when WFI first reaches WB. The
      // first waiting edge then discards speculative younger fetch state and
      // pins the architectural fetch address to WFI+4.
      dut.clock.step()
      cycles += 1
      dut.io.halted.expect(true.B)
      dut.io.imem.addr.expect(resumePc.U)

      for (_ <- 0 until 8) {
        dut.io.commit.valid.expect(false.B)
        dut.io.commit.interrupt.expect(false.B)
        dut.io.dmem.valid.expect(false.B)
        dut.io.halted.expect(true.B)
        dut.io.imem.addr.expect(resumePc.U)
        dut.clock.step()
        cycles += 1
      }

      dut.io.timerInterrupt.poke(true.B)
      dut.io.halted.expect(false.B)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.pc.expect(wfiPc.U)
      dut.io.commit.inst.expect(wfi.U)
      dut.io.commit.exception.expect(false.B)
      dut.io.commit.interrupt.expect(true.B)
      dut.io.commit.interruptCause.expect(BigInt("80000007", 16).U)
      dut.io.commit.interruptPc.expect(resumePc.U)
      dut.io.dmem.valid.expect(false.B)
      dut.clock.step()
      cycles += 1
      dut.io.timerInterrupt.poke(false.B)

      var wfiCommits = 1
      var sawCause = false
      var sawEpc = false
      var sawMret = false
      var sawResume = false

      while ((!sawCause || !sawEpc || !sawMret || !sawResume) && cycles < 240) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        if (dut.io.commit.valid.peek().litToBoolean) {
          val instruction = dut.io.commit.inst.peek().litValue
          if (instruction == wfi) wfiCommits += 1
          if (instruction == BigInt("30200073", 16)) sawMret = true

          if (dut.io.commit.rdWrite.peek().litToBoolean) {
            val rd = dut.io.commit.rd.peek().litValue
            val value = dut.io.commit.rdData.peek().litValue
            if (rd == 6 && value == BigInt("80000007", 16)) sawCause = true
            if (rd == 7 && value == resumePc) sawEpc = true
            if (rd == 4 && value == 1) sawResume = true
          }
        }

        dut.clock.step()
        cycles += 1
      }

      wfiCommits shouldBe 1
      sawCause shouldBe true
      sawEpc shouldBe true
      sawMret shouldBe true
      sawResume shouldBe true
    }
  }
}
