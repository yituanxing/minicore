package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class MachineMretCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore Machine trap return"

  private val base = BigInt("80000000", 16)
  private val mret = BigInt("30200073", 16)

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

  it should "trap, update mepc, return, and suppress the younger MRET Store" in {
    val handler = base + 0x40
    val resume = base + 0x24
    val sentinel = base + 0x100
    val program = Map(
      base -> uType(0x80000, 7),                    // lui x7, 0x80000
      (base + 0x04) -> iType(0x100, 7, 0, 7, 0x13), // addi x7, x7, 0x100
      (base + 0x08) -> iType(0x5a, 0, 0, 8, 0x13),  // addi x8, x0, 0x5a
      (base + 0x0c) -> uType(0x80000, 1),
      (base + 0x10) -> iType(0x40, 1, 0, 1, 0x13),
      (base + 0x14) -> csr(0x305, 1, 1, 0),          // csrw mtvec, x1
      (base + 0x18) -> iType(8, 0, 0, 1, 0x13),
      (base + 0x1c) -> csr(0x300, 1, 1, 0),          // csrw mstatus, x1
      (base + 0x20) -> BigInt("00000073", 16),     // ecall
      resume -> csr(0x300, 0, 2, 5),                // csrr x5, mstatus
      (base + 0x28) -> uType(0x10000, 9),
      (base + 0x2c) -> sType(8, 0, 9, 2),           // exit 0
      handler -> csr(0x341, 0, 2, 2),               // csrr x2, mepc
      (handler + 0x04) -> iType(4, 2, 0, 2, 0x13),
      (handler + 0x08) -> csr(0x341, 2, 1, 0),       // csrw mepc, x2
      (handler + 0x0c) -> mret,
      (handler + 0x10) -> sType(0, 8, 7, 2)          // forbidden younger Store
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imSoftware, stopOnTrap = false)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      var sawTrap = false
      var sawMret = false
      var mretWasException = false
      var returnedStatus: Option[BigInt] = None
      var sawExit = false
      var cycles = 0

      while (!sawExit && cycles < 200) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00000013", 16)).U)

        if (dut.io.memValid.peek().litToBoolean && dut.io.memWrite.peek().litToBoolean) {
          val address = dut.io.memAddr.peek().litValue
          val data = dut.io.memWdata.peek().litValue
          val mask = dut.io.memWmask.peek().litValue
          for (byte <- 0 until 4 if ((mask >> byte) & 1) == 1) {
            memory(address + byte) = ((data >> (byte * 8)) & 0xff).toInt
          }
        }
        if (dut.io.exitValid.peek().litToBoolean) sawExit = true

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val instruction = dut.io.commit.inst.peek().litValue
          if (dut.io.commit.exception.peek().litToBoolean) sawTrap = true
          if (instruction == mret) {
            sawMret = true
            mretWasException = dut.io.commit.exception.peek().litToBoolean
          }
          if (dut.io.commit.rdWrite.peek().litToBoolean &&
              dut.io.commit.rd.peek().litValue == 5) {
            returnedStatus = Some(dut.io.commit.rdData.peek().litValue)
          }
        }
      }

      sawExit shouldBe true
      sawTrap shouldBe true
      sawMret shouldBe true
      mretWasException shouldBe false
      returnedStatus shouldBe Some(BigInt("1888", 16))
      (0 until 4).map(byte => memory(sentinel + byte)) shouldBe Seq(0, 0, 0, 0)
    }
  }
}
