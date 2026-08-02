package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class MachineTimerInterruptCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore Machine timer interrupts"

  private val base = BigInt("80000000", 16)
  private val handler = base + 0x80
  private val sentinel = base + 0x200
  private val storePc = base + 0x24

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

  it should "retire the older instruction, suppress a younger Store, and replay it after MRET" in {
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 0x04) -> iType(0x80, 1, 0, 1, 0x13),  // handler address
      (base + 0x08) -> csr(0x305, 1, 1, 0),          // csrw mtvec, x1
      (base + 0x0c) -> uType(0x80000, 5),
      (base + 0x10) -> iType(0x200, 5, 0, 5, 0x13), // sentinel address
      (base + 0x14) -> iType(0x80, 0, 0, 2, 0x13),
      (base + 0x18) -> csr(0x304, 2, 1, 0),          // csrw mie, x2
      (base + 0x1c) -> iType(8, 0, 0, 3, 0x13),
      (base + 0x20) -> csr(0x300, 3, 1, 0),          // csrw mstatus, x3
      storePc -> sType(0, 4, 5, 2),                 // sw x4, 0(x5)
      (base + 0x28) -> csr(0x300, 0, 2, 9),          // csrr x9, mstatus
      (base + 0x2c) -> iType(1, 4, 0, 4, 0x13),     // addi x4, x4, 1
      (base + 0x30) -> BigInt("0000006f", 16),      // jal x0, 0
      handler -> csr(0x342, 0, 2, 6),               // csrr x6, mcause
      (handler + 0x04) -> csr(0x341, 0, 2, 7),       // csrr x7, mepc
      (handler + 0x08) -> BigInt("30200073", 16)    // mret
    )

    simulate(new AetherCore(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      var injected = false
      var interruptCommits = 0
      var storeWrites = 0
      var sawMret = false
      var sawReturnedStatus = false
      var sawCause = false
      var sawEpc = false
      var cycles = 0

      while ((!sawReturnedStatus || storeWrites != 1) && cycles < 240) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        val candidate = !injected &&
          dut.io.commit.valid.peek().litToBoolean &&
          dut.io.dmem.valid.peek().litToBoolean &&
          dut.io.dmem.write.peek().litToBoolean &&
          dut.io.dmem.addr.peek().litValue == sentinel

        if (candidate) {
          dut.io.timerInterrupt.poke(true.B)
          dut.io.commit.interrupt.expect(true.B)
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.interruptCause.expect(BigInt("80000007", 16).U)
          dut.io.commit.interruptPc.expect(storePc.U)
          dut.io.dmem.valid.expect(false.B)
          injected = true
        }

        if (dut.io.dmem.valid.peek().litToBoolean &&
            dut.io.dmem.write.peek().litToBoolean &&
            dut.io.dmem.ready.peek().litToBoolean) {
          val address = dut.io.dmem.addr.peek().litValue
          val data = dut.io.dmem.wdata.peek().litValue
          val mask = dut.io.dmem.wmask.peek().litValue
          for (byte <- 0 until 4 if ((mask >> byte) & 1) == 1) {
            memory(address + byte) = ((data >> (byte * 8)) & 0xff).toInt
          }
          if (address == sentinel) storeWrites += 1
        }

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.interrupt.peek().litToBoolean) interruptCommits += 1
          val instruction = dut.io.commit.inst.peek().litValue
          if (instruction == BigInt("30200073", 16)) sawMret = true
          if (dut.io.commit.rdWrite.peek().litToBoolean) {
            val rd = dut.io.commit.rd.peek().litValue
            val value = dut.io.commit.rdData.peek().litValue
            if (rd == 6 && value == BigInt("80000007", 16)) sawCause = true
            if (rd == 7 && value == storePc) sawEpc = true
            if (rd == 9 && value == BigInt("1888", 16)) sawReturnedStatus = true
          }
        }

        dut.clock.step()
        cycles += 1
        if (injected) dut.io.timerInterrupt.poke(false.B)
      }

      injected shouldBe true
      interruptCommits shouldBe 1
      sawMret shouldBe true
      sawCause shouldBe true
      sawEpc shouldBe true
      sawReturnedStatus shouldBe true
      storeWrites shouldBe 1
      (0 until 4).map(byte => memory(sentinel + byte)) shouldBe Seq(0, 0, 0, 0)
    }
  }
}
