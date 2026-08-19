package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class RV64AtomicWordLrScSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV64A LR.W/SC.W path"

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

  private def amo(
      funct5: Int,
      rs2: Int,
      rs1: Int,
      rd: Int,
      width: Int,
      aq: Boolean = false,
      rl: Boolean = false
  ): BigInt =
    (BigInt(funct5 & 0x1f) << 27) |
      (if (aq) BigInt(1) << 26 else BigInt(0)) |
      (if (rl) BigInt(1) << 25 else BigInt(0)) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(width & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x2f)

  private val W = 2

  private val rv64ima = CoreProfiles.rv64imCurrent.copy(
    name = "rv64ima-word-lrsc-test",
    isa = CoreProfiles.rv64imCurrent.isa.copy(extensions = Set('I', 'M', 'A'))
  )

  it should "execute matching RV64 LR.W/SC.W and preserve the untouched upper word" in {
    val base = BigInt("80000000", 16)
    val wordAddress = BigInt("100", 16)
    val lrW = amo(0x02, rs2 = 0, rs1 = 1, rd = 3, width = W, aq = true)
    val firstScW = amo(0x03, rs2 = 2, rs1 = 1, rd = 4, width = W, rl = true)
    val secondScW = amo(0x03, rs2 = 2, rs1 = 1, rd = 5, width = W, rl = true)
    val finalLrW = amo(0x02, rs2 = 0, rs1 = 1, rd = 6, width = W, aq = true)

    val program = Seq(
      iType(0x100, 0, 0, 1, 0x13), // x1 = 0x100
      iType(7, 0, 0, 2, 0x13),     // x2 = 7
      lrW,                           // old low word is 0x80000000 -> sign-extended
      firstScW,                      // matching reservation succeeds
      secondScW,                     // reservation was consumed, so this fails
      finalLrW,                      // observe the low word written by SC.W
      uType(0x10000, 9),             // x9 = 0x10000000
      sType(8, 0, 9, 2),             // sw x0, 8(x9): exit 0
      BigInt("00100073", 16)
    ).zipWithIndex.map { case (inst, index) => base + index * 4 -> inst }.toMap

    simulate(new AetherCoreSimTop(rv64ima)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      val retired = mutable.Map.empty[Int, BigInt]
      val acceptedWriteMasks = mutable.ArrayBuffer.empty[BigInt]

      def write64(address: BigInt, value: BigInt): Unit =
        for (byte <- 0 until 8)
          memory(address + byte) = ((value >> (byte * 8)) & 0xff).toInt

      def read64(address: BigInt): BigInt =
        (0 until 8).foldLeft(BigInt(0)) { (value, byte) =>
          value | (BigInt(memory(address + byte) & 0xff) << (byte * 8))
        }

      write64(wordAddress, BigInt("1122334480000000", 16))

      var stalledRead = false
      var stalledWrite = false
      var sawExit = false
      var cycles = 0

      while (!sawExit && !dut.io.halted.peek().litToBoolean && cycles < 500) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        val memValid = dut.io.memValid.peek().litToBoolean
        val memWrite = dut.io.memWrite.peek().litToBoolean
        val memAddress = dut.io.memAddr.peek().litValue
        val stall =
          if (memValid && memAddress == wordAddress && !memWrite && !stalledRead) {
            stalledRead = true
            true
          } else if (memValid && memAddress == wordAddress && memWrite && !stalledWrite) {
            stalledWrite = true
            true
          } else false

        dut.io.memReady.poke((!stall).B)
        dut.io.memRdata.poke(read64(memAddress).U(64.W))

        if (memValid && memWrite && !stall) {
          val data = dut.io.memWdata.peek().litValue
          val mask = dut.io.memWmask.peek().litValue
          if (memAddress == wordAddress) acceptedWriteMasks += mask
          for (byte <- 0 until 8 if ((mask >> byte) & 1) == 1)
            memory(memAddress + byte) = ((data >> (byte * 8)) & 0xff).toInt
        }

        if (dut.io.exitValid.peek().litToBoolean) {
          sawExit = true
          dut.io.exitCode.peek().litValue shouldBe 0
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.rdWrite.peek().litToBoolean)
          retired(dut.io.commit.rd.peek().litValue.toInt) = dut.io.commit.rdData.peek().litValue
      }

      sawExit shouldBe true
      stalledRead shouldBe true
      stalledWrite shouldBe true
      retired(3) shouldBe BigInt("ffffffff80000000", 16)
      retired(4) shouldBe 0
      retired(5) shouldBe 1
      retired(6) shouldBe 7
      acceptedWriteMasks.toSeq shouldBe Seq(BigInt(0x0f))
      read64(wordAddress) shouldBe BigInt("1122334400000007", 16)
    }
  }
}
