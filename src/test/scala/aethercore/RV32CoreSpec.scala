package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class RV32CoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV32I profile"

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

  it should "execute RV32I loads and stores under memory backpressure" in {
    val base = BigInt("80000000", 16)
    val program = Seq(
      iType(-1, 0, 0, 1, 0x13),      // addi x1, x0, -1
      sType(0, 1, 0, 2),             // sw   x1, 0(x0)
      iType(0, 0, 0, 2, 0x03),       // lb   x2, 0(x0)
      iType(0, 0, 4, 3, 0x03),       // lbu  x3, 0(x0)
      iType(0, 0, 1, 4, 0x03),       // lh   x4, 0(x0)
      iType(0, 0, 5, 6, 0x03),       // lhu  x6, 0(x0)
      iType(0, 0, 2, 7, 0x03),       // lw   x7, 0(x0)
      iType(0x5a, 0, 0, 8, 0x13),    // addi x8, x0, 0x5a
      sType(1, 8, 0, 0),             // sb   x8, 1(x0)
      iType(1, 0, 4, 9, 0x03),       // lbu  x9, 1(x0)
      uType(0x10000, 5),              // lui  x5, 0x10000
      sType(8, 0, 5, 2),             // sw   x0, 8(x5): exit 0
      BigInt("00100073", 16)         // ebreak if exit is not observed
    ).zipWithIndex.map { case (inst, index) => base + index * 4 -> inst }.toMap

    simulate(new AetherCoreSimTop(CoreProfiles.rv32iMinimal)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      val retired = mutable.Map.empty[Int, BigInt]
      var stalledLoad = false
      var sawWordMask = false
      var sawByteMask = false
      var sawExit = false
      var exitCode = BigInt(-1)
      var cycles = 0

      def readWord(address: BigInt): BigInt =
        (0 until 4).foldLeft(BigInt(0)) { (word, byte) =>
          word | (BigInt(memory(address + byte) & 0xff) << (byte * 8))
        }

      while (!sawExit && !dut.io.halted.peek().litToBoolean && cycles < 300) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        val memValid = dut.io.memValid.peek().litToBoolean
        val memWrite = dut.io.memWrite.peek().litToBoolean
        val memAddress = dut.io.memAddr.peek().litValue
        val injectStall = memValid && !memWrite && !stalledLoad
        dut.io.memReady.poke((!injectStall).B)
        if (injectStall) stalledLoad = true

        dut.io.memRdata.poke(readWord(memAddress).U(32.W))

        if (memValid && memWrite && !injectStall) {
          val data = dut.io.memWdata.peek().litValue
          val mask = dut.io.memWmask.peek().litValue
          if (mask == 0xf) sawWordMask = true
          if (mask == 0x1) sawByteMask = true
          for (byte <- 0 until 4 if ((mask >> byte) & 1) == 1) {
            memory(memAddress + byte) = ((data >> (byte * 8)) & 0xff).toInt
          }
        }

        if (dut.io.exitValid.peek().litToBoolean) {
          sawExit = true
          exitCode = dut.io.exitCode.peek().litValue
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.rdWrite.peek().litToBoolean) {
          retired(dut.io.commit.rd.peek().litValue.toInt) = dut.io.commit.rdData.peek().litValue
        }
      }

      sawExit shouldBe true
      exitCode shouldBe 0
      stalledLoad shouldBe true
      sawWordMask shouldBe true
      sawByteMask shouldBe true
      memory(0) shouldBe 0xff
      memory(1) shouldBe 0x5a
      memory(2) shouldBe 0xff
      memory(3) shouldBe 0xff
      retired(1) shouldBe BigInt("ffffffff", 16)
      retired(2) shouldBe BigInt("ffffffff", 16)
      retired(3) shouldBe BigInt("000000ff", 16)
      retired(4) shouldBe BigInt("ffffffff", 16)
      retired(6) shouldBe BigInt("0000ffff", 16)
      retired(7) shouldBe BigInt("ffffffff", 16)
      retired(8) shouldBe BigInt("0000005a", 16)
      retired(9) shouldBe BigInt("0000005a", 16)
      retired(5) shouldBe BigInt("10000000", 16)
    }
  }
}
