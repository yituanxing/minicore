package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class MachineCsrCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore machine CSR path"

  private val base = BigInt("80000000", 16)

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

  private def csr(csr: Int, source: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(csr & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  it should "retire ordered register and immediate CSR operations under memory backpressure" in {
    val program = Seq(
      iType(0x55, 0, 0, 1, 0x13), // addi x1, x0, 0x55
      sType(0, 1, 0, 2),          // sw x1, 0(x0)
      iType(0, 0, 2, 10, 0x03),   // lw x10, 0(x0)
      csr(0x340, 10, 1, 2),       // csrrw x2, mscratch, x10
      csr(0x340, 0, 2, 3),        // csrrs x3, mscratch, x0
      csr(0x340, 2, 6, 4),        // csrrsi x4, mscratch, 2
      csr(0x340, 1, 7, 5),        // csrrci x5, mscratch, 1
      csr(0x340, 1, 1, 0),        // csrrw x0, mscratch, x1
      csr(0x340, 3, 5, 6),        // csrrwi x6, mscratch, 3
      csr(0x340, 0, 2, 7),        // csrrs x7, mscratch, x0
      csr(0x301, 0, 2, 8),        // csrrs x8, misa, x0
      uType(0x10000, 9),           // lui x9, 0x10000
      sType(8, 0, 9, 2),          // sw x0, 8(x9): exit 0
      BigInt("00100073", 16)
    ).zipWithIndex.map { case (inst, index) => base + index * 4 -> inst }.toMap

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      val retired = mutable.Map.empty[Int, BigInt]
      var stalledLoad = false
      var sawExit = false
      var cycles = 0

      def readWord(address: BigInt): BigInt =
        (0 until 4).foldLeft(BigInt(0)) { (word, byte) =>
          word | (BigInt(memory(address + byte) & 0xff) << (byte * 8))
        }

      while (!sawExit && cycles < 300) {
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
          for (byte <- 0 until 4 if ((mask >> byte) & 1) == 1) {
            memory(memAddress + byte) = ((data >> (byte * 8)) & 0xff).toInt
          }
        }

        if (dut.io.exitValid.peek().litToBoolean) sawExit = true

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.rdWrite.peek().litToBoolean) {
          retired(dut.io.commit.rd.peek().litValue.toInt) = dut.io.commit.rdData.peek().litValue
        }
      }

      sawExit shouldBe true
      stalledLoad shouldBe true
      retired(2) shouldBe 0
      retired(3) shouldBe BigInt("55", 16)
      retired(4) shouldBe BigInt("55", 16)
      retired(5) shouldBe BigInt("57", 16)
      retired(6) shouldBe BigInt("55", 16)
      retired(7) shouldBe 3
      retired(8) shouldBe BigInt("40001100", 16)
      retired(10) shouldBe BigInt("55", 16)
    }
  }

  it should "trap on a write to read-only misa without architectural side effects" in {
    val instruction = csr(0x301, 1, 1, 2)
    val faultPc = base + 4
    val program = Map(
      base -> iType(1, 0, 0, 1, 0x13),
      faultPc -> instruction,
      (base + 8) -> BigInt("00100073", 16)
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      var sawException = false
      var illegalWroteRd = false
      var cycles = 0
      while (!sawException && cycles < 80) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, BigInt("00100073", 16)).U)

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          sawException = true
          illegalWroteRd = dut.io.commit.rdWrite.peek().litToBoolean
          dut.io.commit.pc.expect(faultPc.U)
          dut.io.commit.inst.peek().litValue shouldBe instruction
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
          dut.io.commit.exceptionValue.expect(instruction.U)
        }
      }

      sawException shouldBe true
      illegalWroteRd shouldBe false
      dut.io.halted.expect(false.B)
    }
  }

  it should "trap on an unimplemented CSR address" in {
    val instruction = csr(0x7ff, 0, 2, 3)
    val program = Map(base -> instruction, (base + 4) -> BigInt("00100073", 16))

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)

      var sawException = false
      var cycles = 0
      while (!sawException && cycles < 80) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, BigInt("00100073", 16)).U)

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          sawException = true
          dut.io.commit.pc.expect(base.U)
          dut.io.commit.inst.peek().litValue shouldBe instruction
          dut.io.commit.rdWrite.expect(false.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
          dut.io.commit.exceptionValue.expect(instruction.U)
        }
      }

      sawException shouldBe true
      dut.io.halted.expect(false.B)
    }
  }
}
