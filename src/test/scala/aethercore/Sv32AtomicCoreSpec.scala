package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class Sv32AtomicCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore translated RV32A path"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x40
  private val dataVa = BigInt("40403024", 16)
  private val rootPpn = BigInt("20000", 16)
  private val nextPpn = BigInt("21000", 16)
  private val oldLeafPpn = BigInt("100001", 16)
  private val newLeafPpn = BigInt("100101", 16)
  private val oldPa = (oldLeafPpn << 12) | (dataVa & 0xfff)
  private val newPa = (newLeafPpn << 12) | (dataVa & 0xfff)
  private val codePpn = base >> 12
  private val codeRootPteAddress = (rootPpn << 12) + (((base >> 22) & 0x3ff) << 2)
  private val dataRootPteAddress = (rootPpn << 12) + (((dataVa >> 22) & 0x3ff) << 2)
  private val dataLeafPteAddress = (nextPpn << 12) + (((dataVa >> 12) & 0x3ff) << 2)
  private val sfenceVma = BigInt("12000073", 16)

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

  private def csr(address: Int, source: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(1) << 12) |
      BigInt(0x73)

  private def amo(funct5: Int, rs2: Int, rs1: Int, rd: Int): BigInt =
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

  private def machineSetup(body: Map[BigInt, BigInt]): Map[BigInt, BigInt] = {
    Map(
      base -> uType(0x80020, 1),
      (base + 0x04) -> csr(0x180, 1),
      (base + 0x08) -> uType(0x80000, 2),
      (base + 0x0c) -> iType(0x40, 2, 0, 2, 0x13),
      (base + 0x10) -> csr(0x341, 2),
      (base + 0x14) -> uType(0x1, 3),
      (base + 0x18) -> iType(-2048, 3, 0, 3, 0x13),
      (base + 0x1c) -> csr(0x300, 3),
      (base + 0x20) -> BigInt("30200073", 16)
    ) ++ body
  }

  private def initialize(dut: AetherCore): Unit = {
    dut.io.imem.inst.poke(BigInt("00000013", 16).U)
    dut.io.imem.fault.poke(false.B)
    dut.io.dmem.ready.poke(true.B)
    dut.io.dmem.rdata.poke(0.U)
    dut.io.dmem.fault.poke(false.B)
    dut.io.ptw.get.ready.poke(false.B)
    dut.io.ptw.get.rdata.poke(0.U)
    dut.io.ptw.get.fault.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
  }

  it should "keep LR reservations in PA34 and reject SC after a fenced VA remap" in {
    val lr1 = amo(0x02, rs2 = 0, rs1 = 5, rd = 6)
    val sc1 = amo(0x03, rs2 = 7, rs1 = 5, rd = 8)
    val lr2 = amo(0x02, rs2 = 0, rs1 = 5, rd = 9)
    val scAfterRemap = amo(0x03, rs2 = 7, rs1 = 5, rd = 10)
    val amoAdd = amo(0x00, rs2 = 7, rs1 = 5, rd = 11)

    val program = machineSetup(Map(
      supervisorEntry -> uType(0x40403, 5),
      (supervisorEntry + 0x04) -> iType(0x24, 5, 0, 5, 0x13),
      (supervisorEntry + 0x08) -> lr1,
      (supervisorEntry + 0x0c) -> iType(0x55, 0, 0, 7, 0x13),
      (supervisorEntry + 0x10) -> sc1,
      (supervisorEntry + 0x14) -> lr2,
      (supervisorEntry + 0x18) -> sfenceVma,
      (supervisorEntry + 0x1c) -> scAfterRemap,
      (supervisorEntry + 0x20) -> amoAdd,
      (supervisorEntry + 0x24) -> BigInt("00100073", 16)
    ))

    val codeLeaf = pte(codePpn, execute = true, accessed = true)
    val pointer = pte(nextPpn)
    var oldWord = BigInt("00000011", 16)
    var newWord = BigInt("00000022", 16)

    simulate(new AetherCore(CoreProfiles.rv32imasuSv32Software)) { dut =>
      initialize(dut)
      var cycles = 0
      var newMapping = false
      var oldWrites = 0
      var newWrites = 0
      var failedScCommitted = false
      var amoCommitted = false
      var sawInitialSc = false
      var sawSecondLr = false

      while (!amoCommitted && cycles < 900) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, BigInt("00000013", 16)).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          if (address == codeRootPteAddress) {
            dut.io.ptw.get.rdata.poke(codeLeaf.U)
          } else if (address == dataRootPteAddress) {
            dut.io.ptw.get.rdata.poke(pointer.U)
          } else if (address == dataLeafPteAddress) {
            val leaf = if (newMapping) newLeafPpn else oldLeafPpn
            dut.io.ptw.get.rdata.poke(
              pte(leaf, read = true, write = true, accessed = true, dirty = true).U
            )
          } else {
            fail(f"unexpected PTW address 0x$address%x")
          }
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.io.dmem.ready.poke(true.B)
        dut.io.dmem.fault.poke(false.B)
        dut.io.dmem.rdata.poke(0.U)
        if (dut.io.dmem.valid.peek().litToBoolean) {
          val address = dut.io.dmem.addr.peek().litValue
          val write = dut.io.dmem.write.peek().litToBoolean
          if (write) {
            val data = dut.io.dmem.wdata.peek().litValue & BigInt("ffffffff", 16)
            dut.io.dmem.wmask.peek().litValue shouldBe BigInt(0xf)
            if (address == oldPa) {
              oldWord = data
              oldWrites += 1
            } else if (address == newPa) {
              newWord = data
              newWrites += 1
            } else {
              fail(f"unexpected atomic write PA 0x$address%x")
            }
          } else if (address == oldPa) {
            dut.io.dmem.rdata.poke(oldWord.U(32.W))
          } else if (address == newPa) {
            dut.io.dmem.rdata.poke(newWord.U(32.W))
          } else {
            fail(f"unexpected atomic read PA 0x$address%x")
          }
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          dut.io.commit.exception.expect(false.B)
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == supervisorEntry + 0x08) {
            dut.io.commit.inst.expect(lr1.U)
            dut.io.commit.rdData.expect(BigInt("11", 16).U)
            dut.io.commit.memAddr.expect(oldPa.U)
          } else if (pc == supervisorEntry + 0x10) {
            dut.io.commit.inst.expect(sc1.U)
            dut.io.commit.rdData.expect(0.U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(true.B)
            dut.io.commit.memAddr.expect(oldPa.U)
            sawInitialSc = true
          } else if (pc == supervisorEntry + 0x14) {
            dut.io.commit.inst.expect(lr2.U)
            dut.io.commit.rdData.expect(BigInt("55", 16).U)
            dut.io.commit.memAddr.expect(oldPa.U)
            sawSecondLr = true
            newMapping = true
          } else if (pc == supervisorEntry + 0x1c) {
            dut.io.commit.inst.expect(scAfterRemap.U)
            dut.io.commit.rdData.expect(1.U)
            dut.io.commit.memValid.expect(false.B)
            newWrites shouldBe 0
            failedScCommitted = true
          } else if (pc == supervisorEntry + 0x20) {
            dut.io.commit.inst.expect(amoAdd.U)
            dut.io.commit.rdData.expect(BigInt("22", 16).U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(true.B)
            dut.io.commit.memAddr.expect(newPa.U)
            amoCommitted = true
          }
        }
      }

      sawInitialSc shouldBe true
      sawSecondLr shouldBe true
      failedScCommitted shouldBe true
      amoCommitted shouldBe true
      oldWrites shouldBe 1
      newWrites shouldBe 1
      oldWord shouldBe BigInt("55", 16)
      newWord shouldBe BigInt("77", 16)
      oldPa should be > BigInt("ffffffff", 16)
      newPa should be > BigInt("ffffffff", 16)
    }
  }
}
