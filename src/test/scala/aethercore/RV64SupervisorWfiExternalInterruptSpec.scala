package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

/** Focused RV64 proof for the Linux-idle architectural intersection:
  * S-mode WFI must wake on a delegated Supervisor external interrupt, retire
  * the interrupt with an XLEN-wide cause, and resume after SRET at WFI+4.
  *
  * This intentionally reuses the shared interrupt/WFI implementation rather
  * than introducing an RV64-specific platform path.
  */
class RV64SupervisorWfiExternalInterruptSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim {
  behavior of "RV64 Supervisor WFI + external interrupt"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x40
  private val resumePc = supervisorEntry + 4
  private val handler = base + 0x100
  private val wfi = BigInt("10500073", 16)
  private val sret = BigInt("10200073", 16)
  private val seipCause = BigInt("8000000000000009", 16)

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

  it should "wake S-mode WFI on SEIP and resume at the next instruction after SRET" in {
    val program = Map(
      // stvec = base + 0x100. AUIPC avoids RV64 LUI sign-extension traps.
      base -> auipc(0, 1),
      (base + 0x04) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 0x08) -> csr(0x105, 1, 1, 0),
      // Delegate and enable Supervisor external interrupts.
      (base + 0x0c) -> iType(0x200, 0, 0, 2, 0x13),
      (base + 0x10) -> csr(0x303, 2, 1, 0),
      (base + 0x14) -> csr(0x104, 2, 1, 0),
      // mepc = supervisorEntry = (base + 0x18) + 0x28.
      (base + 0x18) -> auipc(0, 3),
      (base + 0x1c) -> iType(0x28, 3, 0, 3, 0x13),
      (base + 0x20) -> csr(0x341, 3, 1, 0),
      // mstatus.MPP=S | sstatus.SIE, then enter S-mode.
      (base + 0x24) -> uType(0x1, 4),
      (base + 0x28) -> iType(-0x7fe, 4, 0, 4, 0x13),
      (base + 0x2c) -> csr(0x300, 4, 1, 0),
      (base + 0x30) -> BigInt("30200073", 16),
      // S-mode idle boundary and continuation.
      supervisorEntry -> wfi,
      resumePc -> iType(1, 5, 0, 5, 0x13),
      (resumePc + 4) -> BigInt("0000006f", 16),
      // S-mode handler captures architectural evidence and returns.
      handler -> csr(0x142, 0, 2, 6),
      (handler + 0x04) -> csr(0x141, 0, 2, 7),
      (handler + 0x08) -> sret
    )

    simulate(new AetherCore(
      CoreProfiles.rv64imsuSoftware,
      withSupervisorExternalInterrupt = true
    )) { dut =>
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)
      dut.io.supervisorExternalInterrupt.get.poke(false.B)

      var cycles = 0
      while (!dut.io.halted.peek().litToBoolean && cycles < 160) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)
        dut.clock.step()
        cycles += 1
      }

      dut.io.halted.expect(true.B)
      dut.io.commit.valid.expect(false.B)

      // One waiting edge discards speculative younger state and pins fetch to WFI+4.
      dut.clock.step()
      cycles += 1
      dut.io.halted.expect(true.B)
      dut.io.imem.addr.expect(resumePc.U)

      // Raw pending must wake WFI even before the next clock edge; because SEIP
      // is delegated/enabled and SIE=1, the same retirement boundary traps to S.
      dut.io.supervisorExternalInterrupt.get.poke(true.B)
      dut.io.halted.expect(false.B)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.pc.expect(supervisorEntry.U)
      dut.io.commit.inst.expect(wfi.U)
      dut.io.commit.exception.expect(false.B)
      dut.io.commit.interrupt.expect(true.B)
      dut.io.commit.interruptCause.expect(seipCause.U)
      dut.io.commit.interruptPc.expect(resumePc.U)
      dut.clock.step()
      cycles += 1
      dut.io.supervisorExternalInterrupt.get.poke(false.B)

      var wfiCommits = 1
      var sawCause = false
      var sawEpc = false
      var sawSret = false
      var sawResume = false

      while ((!sawCause || !sawEpc || !sawSret || !sawResume) && cycles < 320) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        if (dut.io.commit.valid.peek().litToBoolean) {
          val instruction = dut.io.commit.inst.peek().litValue
          if (instruction == wfi) wfiCommits += 1
          if (instruction == sret) sawSret = true

          if (dut.io.commit.rdWrite.peek().litToBoolean) {
            val rd = dut.io.commit.rd.peek().litValue
            val value = dut.io.commit.rdData.peek().litValue
            if (rd == 6 && value == seipCause) sawCause = true
            if (rd == 7 && value == resumePc) sawEpc = true
            if (rd == 5 && value == 1) sawResume = true
          }
        }

        dut.clock.step()
        cycles += 1
      }

      wfiCommits shouldBe 1
      sawCause shouldBe true
      sawEpc shouldBe true
      sawSret shouldBe true
      sawResume shouldBe true
    }
  }
}
