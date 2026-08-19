package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class RV64AtomicCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV64A atomic memory path"

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
  private val D = 3

  private val rv64ima = CoreProfiles.rv64imCurrent.copy(
    name = "rv64ima-atomic-test",
    isa = CoreProfiles.rv64imCurrent.isa.copy(extensions = Set('I', 'M', 'A'))
  )

  it should "execute full-width atomics, RV64 word semantics and width-qualified reservations under backpressure" in {
    val base = BigInt("80000000", 16)
    val dwordAddress = BigInt("100", 16)
    val operandAddress = BigInt("110", 16)
    val wordAddress = BigInt("120", 16)

    val firstScD = amo(0x03, rs2 = 2, rs1 = 1, rd = 4, width = D)
    val secondScD = amo(0x03, rs2 = 2, rs1 = 1, rd = 5, width = D)
    val scDMismatched = amo(0x03, rs2 = 2, rs1 = 1, rd = 16, width = D)
    val scWMismatched = amo(0x03, rs2 = 2, rs1 = 1, rd = 18, width = W)
    val finalScD = amo(0x03, rs2 = 2, rs1 = 1, rd = 20, width = D)

    val program = Seq(
      iType(0x100, 0, 0, 1, 0x13),                     // x1 = 0x100
      iType(0x10, 1, 3, 2, 0x03),                      // ld x2, 0x10(x1)
      amo(0x02, 0, 1, 3, D),                           // lr.d x3,(x1)
      firstScD,                                         // sc.d x4,x2,(x1)
      secondScD,                                        // sc.d x5,x2,(x1), fail
      amo(0x00, 2, 1, 6, D, aq = true, rl = true),    // amoadd.d
      amo(0x01, 2, 1, 7, D),                           // amoswap.d
      iType(0x20, 1, 0, 1, 0x13),                      // x1 = 0x120
      iType(1, 0, 0, 2, 0x13),                         // x2 = 1
      amo(0x14, 2, 1, 10, W),                          // amomax.w: 0x80000000 -> 1
      iType(-1, 0, 0, 11, 0x13),                       // x11 = -1
      amo(0x18, 11, 1, 12, W),                         // amominu.w: 1 vs 0xffffffff -> 1
      amo(0x1c, 11, 1, 13, W),                         // amomaxu.w: 1 -> 0xffffffff
      amo(0x10, 2, 1, 14, W),                          // amomin.w: -1 vs 1 -> -1
      amo(0x02, 0, 1, 15, W),                          // lr.w, reserve W
      scDMismatched,                                    // sc.d same address must fail
      amo(0x02, 0, 1, 17, D),                          // lr.d, reserve D
      scWMismatched,                                    // sc.w same address must fail
      amo(0x02, 0, 1, 19, D),                          // lr.d
      finalScD,                                         // matching sc.d succeeds
      uType(0x10000, 9),                                // x9 = 0x10000000
      sType(8, 0, 9, 2),                                // sw x0, 8(x9): exit 0
      BigInt("00100073", 16)
    ).zipWithIndex.map { case (inst, index) => base + index * 4 -> inst }.toMap

    simulate(new AetherCoreSimTop(rv64ima)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      val retired = mutable.Map.empty[Int, BigInt]
      val wordWriteMasks = mutable.ArrayBuffer.empty[BigInt]

      def write64(address: BigInt, value: BigInt): Unit =
        for (byte <- 0 until 8)
          memory(address + byte) = ((value >> (byte * 8)) & 0xff).toInt

      def read64(address: BigInt): BigInt =
        (0 until 8).foldLeft(BigInt(0)) { (value, byte) =>
          value | (BigInt(memory(address + byte) & 0xff) << (byte * 8))
        }

      write64(dwordAddress, BigInt("0000000100000005", 16))
      write64(operandAddress, BigInt("0000000100000002", 16))
      write64(wordAddress, BigInt("1122334480000000", 16))

      var stalledRead = false
      var stalledWrite = false
      var sawExit = false
      var sawWidthMismatchPreserve = false
      var cycles = 0

      while (!sawExit && !dut.io.halted.peek().litToBoolean && cycles < 1200) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        val memValid = dut.io.memValid.peek().litToBoolean
        val memWrite = dut.io.memWrite.peek().litToBoolean
        val memAddress = dut.io.memAddr.peek().litValue
        val stall =
          if (memValid && memAddress == dwordAddress && !memWrite && !stalledRead) {
            stalledRead = true
            true
          } else if (memValid && memAddress == dwordAddress && memWrite && !stalledWrite) {
            stalledWrite = true
            true
          } else false

        dut.io.memReady.poke((!stall).B)
        dut.io.memRdata.poke(read64(memAddress).U(64.W))

        if (memValid && memWrite && !stall) {
          val data = dut.io.memWdata.peek().litValue
          val mask = dut.io.memWmask.peek().litValue
          if (memAddress == wordAddress) wordWriteMasks += mask
          for (byte <- 0 until 8 if ((mask >> byte) & 1) == 1)
            memory(memAddress + byte) = ((data >> (byte * 8)) & 0xff).toInt
        }

        if (dut.io.exitValid.peek().litToBoolean) {
          sawExit = true
          dut.io.exitCode.peek().litValue shouldBe 0
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.rdWrite.peek().litToBoolean)
            retired(dut.io.commit.rd.peek().litValue.toInt) = dut.io.commit.rdData.peek().litValue

          val inst = dut.io.commit.inst.peek().litValue
          if (inst == scWMismatched) {
            // All preceding W atomics must preserve the untouched upper word,
            // and both cross-width SC attempts must leave memory unchanged.
            read64(wordAddress) shouldBe BigInt("11223344ffffffff", 16)
            sawWidthMismatchPreserve = true
          }
        }
      }

      sawExit shouldBe true
      stalledRead shouldBe true
      stalledWrite shouldBe true
      sawWidthMismatchPreserve shouldBe true

      retired(3) shouldBe BigInt("0000000100000005", 16)
      retired(4) shouldBe 0
      retired(5) shouldBe 1
      retired(6) shouldBe BigInt("0000000100000002", 16)
      retired(7) shouldBe BigInt("0000000200000004", 16)

      // Every RV64 AMO.W/LR.W result is sign-extended from bit 31.
      retired(10) shouldBe BigInt("ffffffff80000000", 16)
      retired(12) shouldBe 1
      retired(13) shouldBe 1
      retired(14) shouldBe BigInt("ffffffffffffffff", 16)
      retired(15) shouldBe BigInt("ffffffffffffffff", 16)

      // A reservation matches both address and architectural access width.
      retired(16) shouldBe 1
      retired(17) shouldBe BigInt("11223344ffffffff", 16)
      retired(18) shouldBe 1
      retired(19) shouldBe BigInt("11223344ffffffff", 16)
      retired(20) shouldBe 0

      wordWriteMasks.toSeq shouldBe Seq(BigInt(0xf), BigInt(0xf), BigInt(0xf), BigInt(0xff))
      read64(dwordAddress) shouldBe BigInt("0000000100000002", 16)
      read64(wordAddress) shouldBe 1
    }
  }

  it should "trap misaligned RV64A W and D operations before bus or reservation effects" in {
    val base = BigInt("80000000", 16)

    def runFault(inst: BigInt, address: Int, expectedCause: Int, expectedRd: Int): Unit = {
      val program = Map(
        base -> iType(address, 0, 0, 1, 0x13),
        (base + 4) -> iType(3, 0, 0, 2, 0x13),
        (base + 8) -> inst,
        (base + 12) -> BigInt("00100073", 16)
      )

      simulate(new AetherCoreSimTop(rv64ima)) { dut =>
        dut.io.imemFault.poke(false.B)
        dut.io.memFault.poke(false.B)
        dut.io.memReady.poke(true.B)
        dut.io.memRdata.poke(BigInt("1234567887654321", 16).U)

        var sawTrap = false
        var sawDataRequest = false
        var cycles = 0

        while (!sawTrap && cycles < 160) {
          val pc = dut.io.imemAddr.peek().litValue
          dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)
          if (dut.io.memValid.peek().litToBoolean) sawDataRequest = true
          dut.clock.step()
          cycles += 1

          if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
            dut.io.commit.pc.expect((base + 8).U(64.W))
            dut.io.commit.inst.expect(inst.U)
            dut.io.commit.exceptionCause.expect(expectedCause.U)
            dut.io.commit.exceptionValue.expect(BigInt(address).U(64.W))
            dut.io.commit.rd.expect(expectedRd.U)
            dut.io.commit.rdWrite.expect(false.B)
            dut.io.commit.memValid.expect(false.B)
            sawTrap = true
          }
        }

        sawTrap shouldBe true
        sawDataRequest shouldBe false
      }
    }

    runFault(amo(0x02, 0, 1, 3, W), 0x102, MachineExceptionCode.LoadAddressMisaligned, 3)
    runFault(amo(0x03, 2, 1, 4, W), 0x102, MachineExceptionCode.StoreAddressMisaligned, 4)
    runFault(amo(0x00, 2, 1, 5, W), 0x102, MachineExceptionCode.StoreAddressMisaligned, 5)
    runFault(amo(0x02, 0, 1, 6, D), 0x104, MachineExceptionCode.LoadAddressMisaligned, 6)
    runFault(amo(0x03, 2, 1, 7, D), 0x104, MachineExceptionCode.StoreAddressMisaligned, 7)
    runFault(amo(0x00, 2, 1, 8, D), 0x104, MachineExceptionCode.StoreAddressMisaligned, 8)
  }
}
