package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.MachineInterruptCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class MachineTimerInterruptCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore Machine timer interrupt"

  private val base = BigInt("80000000", 16)
  private val handler = base + 0x40
  private val triggerPc = base + 0x1c
  private val resumePc = base + 0x20
  private val exitAddress = BigInt("10000008", 16)
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

  it should "retire the older instruction, suppress the younger Store, and resume it after MRET" in {
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 0x04) -> iType(0x40, 1, 0, 1, 0x13),
      (base + 0x08) -> csr(0x305, 1, 1, 0),
      (base + 0x0c) -> iType(0x80, 0, 0, 1, 0x13),
      (base + 0x10) -> csr(0x304, 1, 1, 0),
      (base + 0x14) -> iType(8, 0, 0, 1, 0x13),
      (base + 0x18) -> csr(0x300, 1, 1, 0),
      triggerPc -> iType(0x55, 0, 0, 2, 0x13),
      resumePc -> sType(0, 2, 0, 2),
      (base + 0x24) -> uType(0x10000, 9),
      (base + 0x28) -> sType(8, 0, 9, 2),
      handler -> csr(0x342, 0, 2, 3),
      (handler + 0x04) -> BigInt("30200073", 16),
      (handler + 0x08) -> sType(4, 2, 0, 2)
    )

    simulate(new AetherCore(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.machineTimerInterrupt.poke(false.B)

      val writes = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
      var timerRaised = false
      var interruptSeen = false
      var mretSeen = false
      var triggerRetired = false
      var causeRead: Option[BigInt] = None
      var exitSeen = false
      var cycles = 0

      while (!exitSeen && cycles < 240) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        if (!timerRaised && dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == triggerPc) {
          dut.io.machineTimerInterrupt.poke(true.B)
          timerRaised = true
          triggerRetired = true
          dut.io.dmem.valid.expect(false.B)
        }

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(resumePc.U)
          dut.io.commit.inst.expect(0.U)
          dut.io.commit.exceptionCause.expect(timerCause.U)
          dut.io.commit.exceptionValue.expect(0.U)
          interruptSeen = true
          dut.io.machineTimerInterrupt.poke(false.B)
        }

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.inst.peek().litValue == BigInt("30200073", 16)) {
          dut.io.commit.exception.expect(false.B)
          mretSeen = true
        }

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.rdWrite.peek().litToBoolean &&
            dut.io.commit.rd.peek().litValue == 3) {
          causeRead = Some(dut.io.commit.rdData.peek().litValue)
        }

        if (dut.io.dmem.valid.peek().litToBoolean &&
            dut.io.dmem.write.peek().litToBoolean &&
            dut.io.dmem.ready.peek().litToBoolean) {
          val address = dut.io.dmem.addr.peek().litValue
          val data = dut.io.dmem.wdata.peek().litValue
          writes += address -> data
          if (address == exitAddress) exitSeen = true
        }

        dut.clock.step()
        cycles += 1
      }

      exitSeen shouldBe true
      timerRaised shouldBe true
      triggerRetired shouldBe true
      interruptSeen shouldBe true
      mretSeen shouldBe true
      causeRead shouldBe Some(timerCause)
      writes.count(_._1 == 0) shouldBe 1
      writes.exists(_._1 == 4) shouldBe false
      writes.find(_._1 == 0).map(_._2 & BigInt("ffffffff", 16)) shouldBe Some(BigInt("55", 16))
      dut.io.halted.expect(false.B)
    }
  }
}
