package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class RV64AtomicDwordOpsSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV64A doubleword AMO family"

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

  private def amoD(funct5: Int, rs2: Int, rs1: Int, rd: Int): BigInt =
    (BigInt(funct5 & 0x1f) << 27) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(3) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x2f)

  it should "execute every AMO.D operation with 64-bit signed and unsigned semantics" in {
    val base = BigInt("80000000", 16)
    val dataAddress = BigInt("100", 16)
    val initial = BigInt("8000000000000005", 16)
    val minusOne = BigInt("ffffffffffffffff", 16)

    val rv64ima = CoreProfiles.rv64imCurrent.copy(
      name = "rv64ima-dword-family-test",
      isa = CoreProfiles.rv64imCurrent.isa.copy(extensions = Set('I', 'M', 'A'))
    )

    val program = Seq(
      iType(0x100, 0, 0, 1, 0x13), // x1 = data address
      iType(3, 0, 0, 2, 0x13),     // x2 = 3
      iType(-1, 0, 0, 8, 0x13),    // x8 = -1
      amoD(0x01, 2, 1, 3),         // amoswap.d: initial -> 3
      amoD(0x00, 2, 1, 4),         // amoadd.d: 3 -> 6
      amoD(0x04, 2, 1, 5),         // amoxor.d: 6 -> 5
      amoD(0x0c, 2, 1, 6),         // amoand.d: 5 -> 1
      amoD(0x08, 2, 1, 7),         // amoor.d: 1 -> 3
      amoD(0x10, 8, 1, 9),         // amomin.d: 3 vs -1 -> -1
      amoD(0x14, 2, 1, 10),        // amomax.d: -1 vs 3 -> 3
      amoD(0x18, 8, 1, 11),        // amominu.d: 3 vs UINT64_MAX -> 3
      amoD(0x1c, 8, 1, 12),        // amomaxu.d: 3 vs UINT64_MAX -> UINT64_MAX
      uType(0x10000, 13),
      sType(8, 0, 13, 2),           // self-check exit 0
      BigInt("00100073", 16)
    ).zipWithIndex.map { case (inst, index) => base + index * 4 -> inst }.toMap

    simulate(new AetherCoreSimTop(rv64ima)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      val retired = mutable.Map.empty[Int, BigInt]
      val masks = mutable.ArrayBuffer.empty[BigInt]

      def write64(address: BigInt, value: BigInt): Unit =
        for (byte <- 0 until 8)
          memory(address + byte) = ((value >> (byte * 8)) & 0xff).toInt

      def read64(address: BigInt): BigInt =
        (0 until 8).foldLeft(BigInt(0)) { (value, byte) =>
          value | (BigInt(memory(address + byte) & 0xff) << (byte * 8))
        }

      write64(dataAddress, initial)

      var stalledRead = false
      var stalledWrite = false
      var sawExit = false
      var cycles = 0

      while (!sawExit && !dut.io.halted.peek().litToBoolean && cycles < 900) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        val valid = dut.io.memValid.peek().litToBoolean
        val write = dut.io.memWrite.peek().litToBoolean
        val address = dut.io.memAddr.peek().litValue
        val stall =
          if (valid && address == dataAddress && !write && !stalledRead) {
            stalledRead = true
            true
          } else if (valid && address == dataAddress && write && !stalledWrite) {
            stalledWrite = true
            true
          } else false

        dut.io.memReady.poke((!stall).B)
        dut.io.memRdata.poke(read64(address).U(64.W))

        if (valid && write && !stall) {
          val data = dut.io.memWdata.peek().litValue
          val mask = dut.io.memWmask.peek().litValue
          if (address == dataAddress) masks += mask
          for (byte <- 0 until 8 if ((mask >> byte) & 1) == 1)
            memory(address + byte) = ((data >> (byte * 8)) & 0xff).toInt
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

      retired(3) shouldBe initial
      retired(4) shouldBe 3
      retired(5) shouldBe 6
      retired(6) shouldBe 5
      retired(7) shouldBe 1
      retired(9) shouldBe 3
      retired(10) shouldBe minusOne
      retired(11) shouldBe 3
      retired(12) shouldBe 3

      masks.toSeq shouldBe Seq.fill(9)(BigInt(0xff))
      read64(dataAddress) shouldBe minusOne
    }
  }
}
