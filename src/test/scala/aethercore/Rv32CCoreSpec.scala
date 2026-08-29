package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.MachineExceptionCode
import aethercore.config.{CoreConfig, CoreProfiles, IsaConfig}
import aethercore.core.{AetherCore, PmpCsrAddress}

class Rv32CCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV32C frontend"

  private val base = BigInt("80000000", 16)
  private val ebreak32 = BigInt("00100073", 16)
  private val cEbreak = BigInt("9002", 16)

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

  private def csr(address: Int, source: Int, funct3: Int = 1, rd: Int = 0): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  private def splitWord(address: BigInt, word: BigInt): Map[BigInt, BigInt] = Map(
    address -> (word & 0xffff),
    (address + 2) -> ((word >> 16) & 0xffff)
  )

  private def splitWords(words: Map[BigInt, BigInt]): Map[BigInt, BigInt] =
    words.toSeq.flatMap { case (address, word) => splitWord(address, word) }.toMap

  private def li(rd: Int, value: BigInt): Seq[BigInt] = {
    val normalized = value & BigInt("ffffffff", 16)
    val high = ((normalized + 0x800) >> 12) & 0xfffff
    val low = normalized - (high << 12)
    val signedLow = if (low >= 2048) low - 4096 else low
    Seq(uType(high.toInt, rd), iType(signedLow.toInt, rd, 0, rd, 0x13))
  }

  private def initialize(dut: AetherCore): Unit = {
    dut.io.imem.inst.poke(0.U)
    dut.io.imem.fault.poke(false.B)
    dut.io.dmem.ready.poke(true.B)
    dut.io.dmem.rdata.poke(0.U)
    dut.io.dmem.fault.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
  }

  it should "retire a mixed 16/32-bit stream at halfword-aligned PCs" in {
    val addiX6X5Ten = BigInt("00a28313", 16)
    val parcels = Map[BigInt, BigInt](
      base -> BigInt("12e5", 16), // C.ADDI x5,-7
      (base + 2) -> (addiX6X5Ten & 0xffff),
      (base + 4) -> ((addiX6X5Ten >> 16) & 0xffff),
      (base + 6) -> cEbreak
    )

    simulate(new AetherCore(CoreProfiles.rv32imcSoftware)) { dut =>
      initialize(dut)
      var sawCompressed = false
      var sawBaseInstruction = false
      var sawBreakpoint = false
      var cycles = 0

      while (!sawBreakpoint && cycles < 120) {
        dut.io.imem.bytes.expect(2.U)
        val address = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(parcels.getOrElse(address, cEbreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val commitPc = dut.io.commit.pc.peek().litValue
          if (commitPc == base) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.inst.expect("hff928293".U)
            dut.io.commit.rawInst.expect("h000012e5".U)
            dut.io.commit.instBytes.expect(2.U)
            dut.io.commit.rd.expect(5.U)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rdData.expect("hfffffff9".U)
            sawCompressed = true
          } else if (commitPc == base + 2) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.inst.expect(addiX6X5Ten.U)
            dut.io.commit.rawInst.expect(addiX6X5Ten.U)
            dut.io.commit.instBytes.expect(4.U)
            dut.io.commit.rd.expect(6.U)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rdData.expect(3.U)
            sawBaseInstruction = true
          } else if (commitPc == base + 6) {
            dut.io.commit.exception.expect(true.B)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.Breakpoint.U)
            dut.io.commit.inst.expect(ebreak32.U)
            dut.io.commit.rawInst.expect(cEbreak.U)
            dut.io.commit.instBytes.expect(2.U)
            sawBreakpoint = true
          }
        }
      }

      sawCompressed shouldBe true
      sawBaseInstruction shouldBe true
      sawBreakpoint shouldBe true
    }
  }

  it should "write PC+2 as the link for compressed JAL" in {
    val cJalPlus4 = BigInt("2011", 16)
    val parcels = Map[BigInt, BigInt](
      base -> cJalPlus4,
      (base + 2) -> cEbreak, // fall-through must be flushed by the jump
      (base + 4) -> cEbreak
    )

    simulate(new AetherCore(CoreProfiles.rv32imcSoftware)) { dut =>
      initialize(dut)
      var sawJal = false
      var sawTarget = false
      var cycles = 0

      while (!sawTarget && cycles < 100) {
        dut.io.imem.bytes.expect(2.U)
        val address = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(parcels.getOrElse(address, cEbreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val commitPc = dut.io.commit.pc.peek().litValue
          commitPc should not be (base + 2)
          if (commitPc == base) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.inst.expect("h004000ef".U)
            dut.io.commit.rawInst.expect(cJalPlus4.U)
            dut.io.commit.instBytes.expect(2.U)
            dut.io.commit.rd.expect(1.U)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rdData.expect((base + 2).U)
            sawJal = true
          } else if (commitPc == base + 4) {
            dut.io.commit.exception.expect(true.B)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.Breakpoint.U)
            sawTarget = true
          }
        }
      }

      sawJal shouldBe true
      sawTarget shouldBe true
    }
  }

  it should "preserve raw reserved compressed bits in an illegal-instruction trap" in {
    val reservedLwsp = BigInt("4002", 16) // C.LWSP with rd=x0 is reserved

    simulate(new AetherCore(CoreProfiles.rv32imcSoftware)) { dut =>
      initialize(dut)
      var sawIllegal = false
      var cycles = 0
      while (!sawIllegal && cycles < 80) {
        dut.io.imem.inst.poke(reservedLwsp.U)
        dut.clock.step()
        cycles += 1
        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.pc.peek().litValue == base) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
          dut.io.commit.exceptionValue.expect(reservedLwsp.U)
          dut.io.commit.rawInst.expect(reservedLwsp.U)
          dut.io.commit.instBytes.expect(2.U)
          sawIllegal = true
        }
      }
      sawIllegal shouldBe true
    }
  }

  it should "suppress a second parcel that crosses a PMP TOR execute boundary" in {
    val pmpaddr0 = PmpCsrAddress.pmpaddr(0)
    val pmpcfg0 = 0x3a0
    val mtvec = 0x305
    val mepc = 0x341
    val mstatus = 0x300
    val userEnd = BigInt("80002000", 16)
    val userStart = userEnd - 2
    val trapHandler = base + 0x200

    val boot = mutable.ArrayBuffer.empty[BigInt]
    def emit(words: Seq[BigInt]): Unit = boot ++= words
    emit(li(5, trapHandler))
    boot += csr(mtvec, 5)
    emit(li(5, userEnd >> 2))
    boot += csr(pmpaddr0, 5)
    emit(li(5, 0x0d)) // TOR + R + X
    boot += csr(pmpcfg0, 5)
    emit(li(5, userStart))
    boot += csr(mepc, 5)
    boot += csr(mstatus, 0)
    boot += BigInt("30200073", 16) // MRET to U

    val bootWords = boot.zipWithIndex.map { case (word, index) => base + index * 4 -> word }.toMap
    val parcels = splitWords(bootWords) ++ Map(
      userStart -> BigInt("8313", 16), // low half of a 32-bit ADDI
      trapHandler -> (ebreak32 & 0xffff),
      (trapHandler + 2) -> ((ebreak32 >> 16) & 0xffff)
    )

    val pmpConfig = CoreConfig(
      name = "rv32imcu-pmp-parcel-test",
      isa = IsaConfig(
        xlen = 32,
        extensions = Set('I', 'M', 'C'),
        privilegeModes = Set('M', 'U'),
        zExtensions = Set("Zicsr"),
        pmpEntries = 16
      ),
      platform = CoreProfiles.rv32iMinimal.platform
    )

    simulate(new AetherCore(pmpConfig)) { dut =>
      initialize(dut)
      var sawFirstParcel = false
      var sawDeniedAddress = false
      var sawDeniedValid = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 260) {
        dut.io.imem.bytes.expect(2.U)
        val address = dut.io.imem.addr.peek().litValue
        val valid = dut.io.imem.valid.peek().litToBoolean
        if (address == userStart && valid) sawFirstParcel = true
        if (address == userEnd) {
          sawDeniedAddress = true
          sawDeniedValid ||= valid
        }
        dut.io.imem.inst.poke(parcels.getOrElse(address, cEbreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == userStart) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAccessFault.U)
          dut.io.commit.exceptionValue.expect(userEnd.U)
          dut.io.commit.instBytes.expect(4.U)
          sawFault = true
        }
      }

      sawFirstParcel shouldBe true
      sawDeniedAddress shouldBe true
      sawDeniedValid shouldBe false
      sawFault shouldBe true
    }
  }
}
