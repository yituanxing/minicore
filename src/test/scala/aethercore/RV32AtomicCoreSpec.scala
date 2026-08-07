package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class RV32AtomicCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV32A atomic memory path"

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
      aq: Boolean = false,
      rl: Boolean = false
  ): BigInt =
    (BigInt(funct5 & 0x1f) << 27) |
      (if (aq) BigInt(1) << 26 else BigInt(0)) |
      (if (rl) BigInt(1) << 25 else BigInt(0)) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(2) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x2f)

  it should "execute LR SC and every RV32A word AMO under read and write backpressure" in {
    val base = BigInt("80000000", 16)
    val dataAddress = BigInt("00000100", 16)
    val rv32ima = CoreProfiles.rv32imSoftware.copy(
      name = "rv32ima-atomic-test",
      isa = CoreProfiles.rv32imSoftware.isa.copy(extensions = Set('I', 'M', 'A'))
    )

    val firstSc = amo(0x03, rs2 = 2, rs1 = 1, rd = 4)
    val failedSc = amo(0x03, rs2 = 2, rs1 = 1, rd = 5)
    val invalidatedSc = amo(0x03, rs2 = 2, rs1 = 1, rd = 18)
    val program = Seq(
      iType(0x100, 0, 0, 1, 0x13),      // addi x1, x0, 0x100
      iType(3, 0, 0, 2, 0x13),          // addi x2, x0, 3
      amo(0x02, 0, 1, 3),               // lr.w      x3, (x1) -> 10
      firstSc,                            // sc.w      x4, x2, (x1) -> 0, mem=3
      failedSc,                           // sc.w      x5, x2, (x1) -> 1, mem=3
      amo(0x00, 2, 1, 6, aq = true),    // amoadd.w  x6, x2, (x1) -> 3, mem=6
      amo(0x01, 2, 1, 7, rl = true),    // amoswap.w x7, x2, (x1) -> 6, mem=3
      amo(0x04, 2, 1, 8),               // amoxor.w  x8, x2, (x1) -> 3, mem=0
      amo(0x08, 2, 1, 10),              // amoor.w   x10,x2, (x1) -> 0, mem=3
      amo(0x0c, 2, 1, 11),              // amoand.w  x11,x2, (x1) -> 3, mem=3
      iType(-1, 0, 0, 12, 0x13),        // addi x12, x0, -1
      amo(0x10, 12, 1, 13),             // amomin.w  x13,x12,(x1) -> 3, mem=-1
      amo(0x14, 2, 1, 14),              // amomax.w  x14,x2, (x1) -> -1, mem=3
      amo(0x18, 12, 1, 15),             // amominu.w x15,x12,(x1) -> 3, mem=3
      amo(0x1c, 12, 1, 16),             // amomaxu.w x16,x12,(x1) -> 3, mem=-1
      amo(0x02, 0, 1, 17),              // lr.w      x17,(x1) -> -1
      sType(4, 2, 1, 2),                // sw x2, 4(x1): conservatively clears reservation
      invalidatedSc,                     // sc.w x18,x2,(x1) -> 1, mem remains -1
      uType(0x10000, 9),                 // lui x9, 0x10000
      sType(8, 0, 9, 2),                // sw x0, 8(x9): exit 0
      BigInt("00100073", 16)
    ).zipWithIndex.map { case (inst, index) => base + index * 4 -> inst }.toMap

    simulate(new AetherCoreSimTop(rv32ima)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      memory(dataAddress) = 10
      val retired = mutable.Map.empty[Int, BigInt]
      var stalledRead = false
      var stalledWrite = false
      var firstScStored = false
      var failedScTouchedMemory = false
      var invalidatedScTouchedMemory = false
      var sawExit = false
      var cycles = 0

      def readWord(address: BigInt): BigInt =
        (0 until 4).foldLeft(BigInt(0)) { (word, byte) =>
          word | (BigInt(memory(address + byte) & 0xff) << (byte * 8))
        }

      while (!sawExit && !dut.io.halted.peek().litToBoolean && cycles < 600) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        val memValid = dut.io.memValid.peek().litToBoolean
        val memWrite = dut.io.memWrite.peek().litToBoolean
        val memAddress = dut.io.memAddr.peek().litValue
        val stall =
          if (memValid && memAddress == dataAddress && !memWrite && !stalledRead) {
            stalledRead = true
            true
          } else if (memValid && memAddress == dataAddress && memWrite && !stalledWrite) {
            stalledWrite = true
            true
          } else {
            false
          }
        dut.io.memReady.poke((!stall).B)
        dut.io.memRdata.poke(readWord(memAddress).U(32.W))

        if (memValid && memWrite && !stall) {
          val data = dut.io.memWdata.peek().litValue
          val mask = dut.io.memWmask.peek().litValue
          mask shouldBe BigInt(0xf)
          for (byte <- 0 until 4 if ((mask >> byte) & 1) == 1) {
            memory(memAddress + byte) = ((data >> (byte * 8)) & 0xff).toInt
          }
        }

        if (dut.io.exitValid.peek().litToBoolean) {
          sawExit = true
          dut.io.exitCode.peek().litValue shouldBe 0
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val inst = dut.io.commit.inst.peek().litValue
          if (dut.io.commit.rdWrite.peek().litToBoolean) {
            retired(dut.io.commit.rd.peek().litValue.toInt) = dut.io.commit.rdData.peek().litValue
          }
          if (inst == firstSc) {
            dut.io.commit.memValid.peek().litToBoolean shouldBe true
            dut.io.commit.memWrite.peek().litToBoolean shouldBe true
            firstScStored = true
          }
          if (inst == failedSc) {
            failedScTouchedMemory = dut.io.commit.memValid.peek().litToBoolean
          }
          if (inst == invalidatedSc) {
            invalidatedScTouchedMemory = dut.io.commit.memValid.peek().litToBoolean
          }
        }
      }

      sawExit shouldBe true
      stalledRead shouldBe true
      stalledWrite shouldBe true
      firstScStored shouldBe true
      failedScTouchedMemory shouldBe false
      invalidatedScTouchedMemory shouldBe false
      readWord(dataAddress) shouldBe BigInt("ffffffff", 16)
      readWord(dataAddress + 4) shouldBe 3

      retired(3) shouldBe 10
      retired(4) shouldBe 0
      retired(5) shouldBe 1
      retired(6) shouldBe 3
      retired(7) shouldBe 6
      retired(8) shouldBe 3
      retired(10) shouldBe 0
      retired(11) shouldBe 3
      retired(12) shouldBe BigInt("ffffffff", 16)
      retired(13) shouldBe 3
      retired(14) shouldBe BigInt("ffffffff", 16)
      retired(15) shouldBe 3
      retired(16) shouldBe 3
      retired(17) shouldBe BigInt("ffffffff", 16)
      retired(18) shouldBe 1
    }
  }
}
