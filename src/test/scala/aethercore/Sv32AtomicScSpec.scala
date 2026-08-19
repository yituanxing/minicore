package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class Sv32AtomicScSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore failed SC through Sv32"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x80
  private val rootPpn = BigInt("20000", 16)
  private val nextPpn = BigInt("21000", 16)
  private val dataVa = BigInt("40403024", 16)
  private val dataLeafPpn = BigInt("30001", 16)
  private val translatedDataPa = (dataLeafPpn << 12) | (dataVa & 0xfff)

  private val codeVpn1 = (supervisorEntry >> 22) & 0x3ff
  private val dataVpn1 = (dataVa >> 22) & 0x3ff
  private val dataVpn0 = (dataVa >> 12) & 0x3ff
  private val codeRootPteAddress = (rootPpn << 12) + (codeVpn1 << 2)
  private val dataRootPteAddress = (rootPpn << 12) + (dataVpn1 << 2)
  private val dataLeafPteAddress = (nextPpn << 12) + (dataVpn0 << 2)
  private val identityCodePpn = base >> 12

  private val satp = 0x180
  private val mstatus = 0x300
  private val mepc = 0x341
  private val mret = BigInt("30200073", 16)
  private val sfenceVma = BigInt("12000073", 16)
  private val nop = BigInt("00000013", 16)

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

  private def csr(address: Int, source: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(1) << 12) |
      BigInt(0x73)

  private def amoW(funct5: Int, rs2: Int, rs1: Int, rd: Int): BigInt =
    (BigInt(funct5 & 0x1f) << 27) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(2) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x2f)

  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      accessed: Boolean = false,
      dirty: Boolean = false
  ): BigInt =
    (ppn << 10) | BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (write) BigInt(1) << 2 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0)) |
      (if (dirty) BigInt(1) << 7 else BigInt(0))

  it should "recheck translation and protection but suppress the final data request when SC has no reservation" in {
    val lr = amoW(0x02, rs2 = 0, rs1 = 1, rd = 3)
    val sc = amoW(0x03, rs2 = 5, rs1 = 1, rd = 4)

    val machineProgram = Seq(
      uType(0x80020, 1),
      csr(satp, 1),
      uType(0x80000, 2),
      iType(0x80, 2, 0, 2, 0x13),
      csr(mepc, 2),
      uType(0x1, 3),
      iType(-2048, 3, 0, 3, 0x13),
      csr(mstatus, 3),
      mret
    ).zipWithIndex.map { case (inst, index) => base + index * 4 -> inst }.toMap

    val supervisorProgram = Seq(
      uType(0x40403, 1),
      iType(0x24, 1, 0, 1, 0x13),
      iType(7, 0, 0, 2, 0x13),
      lr,
      sType(0, 2, 1, 2),
      sfenceVma,
      iType(9, 0, 0, 5, 0x13),
      sc,
      nop
    ).zipWithIndex.map { case (inst, index) => supervisorEntry + index * 4 -> inst }.toMap

    val program = machineProgram ++ supervisorProgram
    val codeLeaf = pte(identityCodePpn, read = true, execute = true, accessed = true)
    val dataRoot = pte(nextPpn)
    val dataLeaf = pte(dataLeafPpn, read = true, write = true, accessed = true, dirty = true)

    val profile = CoreProfiles.rv32imsuSv32Software.copy(
      name = "rv32ima-sv32-failed-sc-test",
      isa = CoreProfiles.rv32imsuSv32Software.isa.copy(extensions = Set('I', 'M', 'A'))
    )

    simulate(new AetherCore(profile)) { dut =>
      dut.io.imem.inst.poke(nop.U)
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.ptw.get.ready.poke(false.B)
      dut.io.ptw.get.rdata.poke(0.U)
      dut.io.ptw.get.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)

      var dataValue = BigInt("12345678", 16)
      val acceptedDataRequests = mutable.ArrayBuffer.empty[Boolean]
      var dataWalkReads = 0
      var sawSc = false
      var cycles = 0

      while (!sawSc && cycles < 700) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, nop).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          if (address == codeRootPteAddress) {
            dut.io.ptw.get.rdata.poke(codeLeaf.U)
          } else if (address == dataRootPteAddress) {
            dut.io.ptw.get.rdata.poke(dataRoot.U)
            dataWalkReads += 1
          } else if (address == dataLeafPteAddress) {
            dut.io.ptw.get.rdata.poke(dataLeaf.U)
            dataWalkReads += 1
          } else {
            fail(f"unexpected PTW address 0x$address%x")
          }
          dut.io.ptw.get.ready.poke(true.B)
        }

        val dataValid = dut.io.dmem.valid.peek().litToBoolean
        val dataWrite = dut.io.dmem.write.peek().litToBoolean
        val dataAddress = dut.io.dmem.addr.peek().litValue
        if (dataValid && dataAddress == translatedDataPa) {
          acceptedDataRequests += dataWrite
          if (dataWrite) {
            dut.io.dmem.wmask.expect(BigInt(0xf).U)
            dataValue = dut.io.dmem.wdata.peek().litValue & BigInt("ffffffff", 16)
          } else {
            dut.io.dmem.rdata.poke(dataValue.U)
          }
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry + 0x1c) {
          dut.io.commit.inst.expect(sc.U)
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(4.U)
          dut.io.commit.rdData.expect(1.U)
          dut.io.commit.memValid.expect(false.B)
          sawSc = true
        }
      }

      sawSc shouldBe true
      dataWalkReads should be >= 4
      acceptedDataRequests.toSeq shouldBe Seq(false, true)
      dataValue shouldBe 7
    }
  }
}
