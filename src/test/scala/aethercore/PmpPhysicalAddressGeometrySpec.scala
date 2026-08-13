package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrFile, PmpCsrAddress}
import aethercore.sim.AetherCoreSimTop

class PmpPhysicalAddressGeometrySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "PMP physical-address geometry propagation"

  private val base = BigInt("80000000", 16)
  private val pmpaddr15 = PmpCsrAddress.pmpaddr(15)
  private val allRv32PmpAddressBits = BigInt("ffffffff", 16)
  private val ebreak = BigInt("00100073", 16)
  private val nop = BigInt("00000013", 16)

  private def initializeCsr(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
    dut.io.trapReturnSupervisor.poke(false.B)
  }

  private def writeCsr(dut: MachineCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

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

  private def li(rd: Int, value: BigInt): Seq[BigInt] = {
    val normalized = value & BigInt("ffffffff", 16)
    val high = ((normalized + 0x800) >> 12) & 0xfffff
    val low = normalized - (high << 12)
    val signedLow = if (low >= 2048) low - 4096 else low
    Seq(uType(high.toInt, rd), iType(signedLow.toInt, rd, 0, rd, 0x13))
  }

  private def place(start: BigInt, words: Seq[BigInt]): Map[BigInt, BigInt] =
    words.zipWithIndex.map { case (word, index) => start + index * 4 -> word }.toMap

  private def initializeCore(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
  }

  it should "preserve all RV32 PMP address bits through MachineCsrFile on PA34" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imuPmpSoftware.isa, 34, false, false)) { dut =>
      initializeCsr(dut)

      writeCsr(dut, pmpaddr15, allRv32PmpAddressBits)
      dut.io.readAddr.poke(pmpaddr15.U)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)
      dut.io.readData.expect(allRv32PmpAddressBits.U)
      dut.io.pmpAddress(15).expect(allRv32PmpAddressBits.U)
    }
  }

  it should "use the same PA34 geometry for whole-core PMP forwarding and persistent state" in {
    val pa34Pmp = CoreProfiles.rv32imuPmpSoftware.copy(
      name = "rv32imu-pmp-pa34-geometry-test",
      platform = CoreProfiles.rv32imuPmpSoftware.platform.copy(paddrBits = 34)
    )
    pa34Pmp.isa.hasSv32 shouldBe false
    pa34Pmp.platform.paddrBits shouldBe 34

    val program = place(
      base,
      li(5, allRv32PmpAddressBits) ++ Seq(
        csr(pmpaddr15, 5),
        csr(pmpaddr15, 0, funct3 = 2, rd = 6),
        nop,
        nop,
        nop,
        csr(pmpaddr15, 0, funct3 = 2, rd = 7),
        ebreak
      )
    )

    simulate(new AetherCoreSimTop(pa34Pmp, stopOnTrap = false)) { dut =>
      initializeCore(dut)
      var sawForwardedRead = false
      var sawPersistentRead = false
      var cycles = 0

      while (!(sawForwardedRead && sawPersistentRead) && cycles < 180) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.rdWrite.peek().litToBoolean) {
          val rd = dut.io.commit.rd.peek().litValue
          if (rd == 6) {
            dut.io.commit.rdData.expect(allRv32PmpAddressBits.U)
            sawForwardedRead = true
          } else if (rd == 7) {
            dut.io.commit.rdData.expect(allRv32PmpAddressBits.U)
            sawPersistentRead = true
          }
        }
      }

      sawForwardedRead shouldBe true
      sawPersistentRead shouldBe true
    }
  }
}
