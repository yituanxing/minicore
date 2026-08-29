package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class PmpCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore PMP enforcement"

  private val base = BigInt("80000000", 16)
  private val userText = BigInt("80001000", 16)
  private val userData = BigInt("80002000", 16)
  private val userLimit = BigInt("80003000", 16)
  private val trapHandler = base + 0x200
  private val uart = BigInt("10000000", 16)

  private val mstatus = 0x300
  private val mtvec = 0x305
  private val mepc = 0x341
  private val pmpcfg0 = 0x3a0
  private val pmpcfg3 = 0x3a3
  private val pmpaddr0 = 0x3b0

  private val ebreak = BigInt("00100073", 16)
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

  private def csr(address: Int, source: Int, funct3: Int = 1, rd: Int = 0): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  private def li(rd: Int, value: BigInt): Seq[BigInt] = {
    val normalized = value & BigInt("ffffffff", 16)
    val high = ((normalized + 0x800) >> 12) & 0xfffff
    val low = normalized - (high << 12)
    val signedLow = if (low >= 2048) low - 4096 else low
    Seq(uType(high.toInt, rd), iType(signedLow.toInt, rd, 0, rd, 0x13))
  }

  private def place(start: BigInt, words: Seq[BigInt]): Map[BigInt, BigInt] =
    words.zipWithIndex.map { case (word, index) => start + index * 4 -> word }.toMap

  private def bootstrap(userWords: Seq[BigInt]): Map[BigInt, BigInt] = {
    val kernel = mutable.ArrayBuffer.empty[BigInt]
    def emit(words: Seq[BigInt]): Unit = kernel ++= words

    emit(li(5, trapHandler))
    kernel += csr(mtvec, 5)

    // Entry 0: [0, 0x80001000), no U permission.
    emit(li(5, userText >> 2))
    kernel += csr(pmpaddr0 + 0, 5)

    // Entry 1: user text upper bound.
    emit(li(5, userData >> 2))
    kernel += csr(pmpaddr0 + 1, 5)

    // Entry 2: user data/stack upper bound.
    emit(li(5, userLimit >> 2))
    kernel += csr(pmpaddr0 + 2, 5)

    // cfg0=TOR deny, cfg1=TOR R-X, cfg2=TOR RW-.
    emit(li(5, BigInt("000b0d08", 16)))
    kernel += csr(pmpcfg0, 5)

    emit(li(5, userText))
    kernel += csr(mepc, 5)
    kernel += csr(mstatus, 0)
    kernel += mret

    place(base, kernel.toSeq) ++
      place(userText, userWords) ++
      Map(trapHandler -> ebreak)
  }

  private def initialize(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
  }

  private def runUntil(
      dut: AetherCoreSimTop,
      program: Map[BigInt, BigInt],
      maximumCycles: Int
  )(observe: => Boolean): Unit = {
    var cycles = 0
    while (!observe && cycles < maximumCycles) {
      dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
      dut.clock.step()
      cycles += 1
    }
    cycles should be < maximumCycles
  }

  it should "forward canonicalized writes from the upper PMP config banks" in {
    val program = place(
      base,
      li(5, 2) ++ Seq(
        csr(pmpcfg3, 5),
        csr(pmpcfg3, 0, funct3 = 2, rd = 6),
        ebreak
      )
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuPmpSoftware, stopOnTrap = false)) { dut =>
      initialize(dut)
      var sawRead = false
      var cycles = 0

      while (!sawRead && cycles < 120) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.rdWrite.peek().litToBoolean &&
            dut.io.commit.rd.peek().litValue == 6) {
          // W=1 with R=0 is WARL-canonicalized to zero. The immediately
          // following CSRRS must observe that canonical value through the
          // pipeline forwarding path, before relying on persistent state.
          dut.io.commit.rdData.expect(0.U)
          sawRead = true
        }
      }

      sawRead shouldBe true
    }
  }

  it should "reject a direct U-mode UART Store without exposing an MMIO pulse" in {
    val storePc = userText + 12
    val program = bootstrap(
      li(5, uart) ++ Seq(
        iType('X', 0, 0, 6, 0x13),
        sType(0, 6, 5, 0),
        ebreak
      )
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuPmpSoftware, stopOnTrap = false)) { dut =>
      initialize(dut)
      var sawFault = false
      var sawUart = false
      var cycles = 0

      while (!sawFault && cycles < 300) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        sawUart ||= dut.io.uartValid.peek().litToBoolean
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(storePc.U)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.StoreAccessFault.U)
          dut.io.commit.exceptionValue.expect(uart.U)
          dut.io.commit.memValid.expect(false.B)
          sawFault = true
        }
      }

      sawFault shouldBe true
      sawUart shouldBe false
    }
  }

  it should "reject a U-mode read from the Machine kernel page" in {
    val loadPc = userText + 8
    val program = bootstrap(
      li(5, base) ++ Seq(
        iType(0, 5, 4, 6, 0x03),
        ebreak
      )
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuPmpSoftware, stopOnTrap = false)) { dut =>
      initialize(dut)
      var sawFault = false
      var sawExternalRead = false
      var cycles = 0

      while (!sawFault && cycles < 300) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        sawExternalRead ||= dut.io.memValid.peek().litToBoolean &&
          dut.io.memAddr.peek().litValue == base
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(loadPc.U)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.LoadAccessFault.U)
          dut.io.commit.exceptionValue.expect(base.U)
          sawFault = true
        }
      }

      sawFault shouldBe true
      sawExternalRead shouldBe false
    }
  }

  it should "raise an instruction access fault after a U-mode jump into the kernel" in {
    val program = bootstrap(
      li(5, base) ++ Seq(
        iType(0, 5, 0, 0, 0x67),
        ebreak
      )
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuPmpSoftware, stopOnTrap = false)) { dut =>
      initialize(dut)
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 320) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(base.U)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAccessFault.U)
          dut.io.commit.exceptionValue.expect(base.U)
          sawFault = true
        }
      }

      sawFault shouldBe true
    }
  }

  it should "permit U-mode data Stores and Loads inside the RW page" in {
    val loadPc = userText + 16
    val program = bootstrap(
      li(5, userData) ++ Seq(
        iType(0x5a, 0, 0, 6, 0x13),
        sType(0, 6, 5, 0),
        iType(0, 5, 4, 7, 0x03),
        ebreak
      )
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuPmpSoftware, stopOnTrap = false)) { dut =>
      initialize(dut)
      var storedByte = BigInt(0)
      var sawLoad = false
      var sawFault = false
      var cycles = 0

      while (!sawLoad && cycles < 320) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)

        if (dut.io.memValid.peek().litToBoolean) {
          dut.io.memAddr.expect(userData.U)
          if (dut.io.memWrite.peek().litToBoolean) {
            storedByte = dut.io.memWdata.peek().litValue & 0xff
          } else {
            dut.io.memRdata.poke(storedByte.U)
          }
        } else {
          dut.io.memRdata.poke(0.U)
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          sawFault ||= dut.io.commit.exception.peek().litToBoolean
          if (dut.io.commit.pc.peek().litValue == loadPc && dut.io.commit.rdWrite.peek().litToBoolean) {
            dut.io.commit.rd.expect(7.U)
            dut.io.commit.rdData.expect(0x5a.U)
            sawLoad = true
          }
        }
      }

      sawLoad shouldBe true
      sawFault shouldBe false
      storedByte shouldBe BigInt(0x5a)
    }
  }
}
