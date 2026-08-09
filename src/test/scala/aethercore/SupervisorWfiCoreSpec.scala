package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class SupervisorWfiCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore WFI privilege handling"

  private val base = BigInt("80000000", 16)
  private val lowerEntry = base + 0x80
  private val wfi = BigInt("10500073", 16)
  private val ebreak = BigInt("00100073", 16)

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

  private def bootstrap(mppValue: Int): Map[BigInt, BigInt] = Map(
    base -> uType(0x80000, 1),
    (base + 4) -> iType(0x80, 1, 0, 1, 0x13),
    (base + 8) -> csr(0x341, 1, 1, 0), // csrw mepc, x1
    (base + 12) -> iType(mppValue, 0, 0, 2, 0x13),
    (base + 16) -> csr(0x300, 2, 1, 0), // csrw mstatus, x2
    (base + 20) -> mret,
    lowerEntry -> wfi,
    (lowerEntry + 4) -> ebreak
  )

  private def initialize(dut: AetherCore): Unit = {
    dut.io.imem.fault.poke(false.B)
    dut.io.dmem.ready.poke(true.B)
    dut.io.dmem.rdata.poke(0.U)
    dut.io.dmem.fault.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
  }

  it should "allow Supervisor-mode WFI to enter the architectural wait state" in {
    // mstatus.MPP=S is 01b at bits 12:11, i.e. 0x800.
    val program = bootstrap(0x800)

    simulate(new AetherCore(CoreProfiles.rv32imsuSoftware)) { dut =>
      initialize(dut)
      var sawIllegalWfi = false
      var cycles = 0

      while (!dut.io.halted.peek().litToBoolean && cycles < 160) {
        val pc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(pc, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.exception.peek().litToBoolean &&
            dut.io.commit.inst.peek().litValue == wfi) {
          sawIllegalWfi = true
        }
      }

      sawIllegalWfi shouldBe false
      dut.io.halted.expect(true.B)
    }
  }

  it should "keep User-mode WFI illegal when Supervisor mode is implemented" in {
    val program = bootstrap(0x000)

    simulate(new AetherCore(CoreProfiles.rv32imsuSoftware)) { dut =>
      initialize(dut)
      var sawIllegalWfi = false
      var cycles = 0

      while (!sawIllegalWfi && cycles < 160) {
        val pc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(pc, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.exception.peek().litToBoolean &&
            dut.io.commit.inst.peek().litValue == wfi) {
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
          dut.io.commit.exceptionValue.expect(wfi.U)
          sawIllegalWfi = true
        }
      }

      sawIllegalWfi shouldBe true
    }
  }
}
