package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class RV64Sv39CoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV64 Sv39 production datapath"

  private val resetBase = BigInt("80000000", 16)

  // Keep both executable and data VAs above 4 GiB so an old (31, 0) slice
  // cannot accidentally pass this core-level qualification.
  private val supervisorEntry = BigInt("100000080", 16)
  private val supervisorPageBase = supervisorEntry & ~BigInt("fff", 16)
  private val dataVa = BigInt("140403024", 16)

  private val rootPpn = BigInt("20000", 16)
  private val codeLevel1Ppn = BigInt("21000", 16)
  private val codeLevel0Ppn = BigInt("22000", 16)
  private val dataLevel1Ppn = BigInt("23000", 16)
  private val dataLevel0Ppn = BigInt("24000", 16)

  // Place the final leaves well above 4 GiB. Their PPNs also force leaf PTE
  // payload bits above bit 31, proving that the production PTW moves 64-bit
  // Sv39 PTEs rather than a widened 32-bit Sv32 side channel.
  private val codeLeafPpn = BigInt("1000000", 16)
  private val dataLeafPpn = BigInt("1000010", 16)
  private val translatedCodeBase = codeLeafPpn << 12
  private val translatedSupervisorEntry = translatedCodeBase | (supervisorEntry & 0xfff)
  private val translatedDataPa = (dataLeafPpn << 12) | (dataVa & 0xfff)

  private def vpn2(va: BigInt): BigInt = (va >> 30) & 0x1ff
  private def vpn1(va: BigInt): BigInt = (va >> 21) & 0x1ff
  private def vpn0(va: BigInt): BigInt = (va >> 12) & 0x1ff

  private val codeRootPteAddress = (rootPpn << 12) + (vpn2(supervisorEntry) << 3)
  private val codeLevel1PteAddress = (codeLevel1Ppn << 12) + (vpn1(supervisorEntry) << 3)
  private val codeLeafPteAddress = (codeLevel0Ppn << 12) + (vpn0(supervisorEntry) << 3)
  private val dataRootPteAddress = (rootPpn << 12) + (vpn2(dataVa) << 3)
  private val dataLevel1PteAddress = (dataLevel1Ppn << 12) + (vpn1(dataVa) << 3)
  private val dataLeafPteAddress = (dataLevel0Ppn << 12) + (vpn0(dataVa) << 3)

  private val nop = BigInt("00000013", 16)
  private val ebreak = BigInt("00100073", 16)
  private val mret = BigInt("30200073", 16)
  private val loadValue = BigInt("1122334455667788", 16)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(opcode & 0x7f)

  private def sType(imm: Int, rs2: Int, rs1: Int, funct3: Int, opcode: Int = 0x23): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(imm & 0x1f) << 7) |
      BigInt(opcode & 0x7f)

  private def uType(imm20: Int, rd: Int, opcode: Int = 0x37): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(opcode & 0x7f)

  private def rType(
      funct7: Int,
      rs2: Int,
      rs1: Int,
      funct3: Int,
      rd: Int,
      opcode: Int = 0x33
  ): BigInt =
    (BigInt(funct7 & 0x7f) << 25) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(opcode & 0x7f)

  private def csr(address: Int, source: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(1) << 12) |
      BigInt(0x73)

  private def add(rd: Int, rs1: Int, rs2: Int): BigInt =
    rType(0, rs2, rs1, 0, rd)

  private def slli(rd: Int, rs1: Int, shamt: Int): BigInt = {
    require(shamt >= 0 && shamt < 64)
    iType(shamt, rs1, 1, rd, 0x13)
  }

  private def place(start: BigInt, words: Seq[BigInt]): Map[BigInt, BigInt] =
    words.zipWithIndex.map { case (word, index) => start + index * 4 -> word }.toMap

  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      accessed: Boolean = false,
      dirty: Boolean = false,
      valid: Boolean = true
  ): BigInt =
    (ppn << 10) |
      (if (valid) BigInt(1) else BigInt(0)) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (write) BigInt(1) << 2 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0)) |
      (if (dirty) BigInt(1) << 7 else BigInt(0))

  private val codeRoot = pte(codeLevel1Ppn)
  private val codeLevel1 = pte(codeLevel0Ppn)
  private val codeLeaf = pte(codeLeafPpn, execute = true, accessed = true)
  private val dataRoot = pte(dataLevel1Ppn)
  private val dataLevel1 = pte(dataLevel0Ppn)
  private val dataLeaf = pte(dataLeafPpn, read = true, write = true, accessed = true, dirty = true)

  private def machineSetup(supervisorBody: Map[BigInt, BigInt]): Map[BigInt, BigInt] = {
    val machineWords = Seq(
      // PMP entry 0: TOR RWX across the bounded PA56 space used by this test.
      iType(-1, 0, 0, 5, 0x13),
      csr(0x3b0, 5),
      iType(15, 0, 0, 5, 0x13),
      csr(0x3a0, 5),

      // satp = Sv39(mode=8) | rootPpn. Build bit 63 explicitly so the
      // executable path, not the test harness, performs the CSR transition.
      iType(1, 0, 0, 1, 0x13),
      slli(1, 1, 63),
      uType(0x20, 2),
      add(1, 1, 2),
      csr(0x180, 1),

      // mepc = 0x0000000100000080, deliberately above the RV32 VA range.
      iType(1, 0, 0, 2, 0x13),
      slli(2, 2, 32),
      iType(0x80, 2, 0, 2, 0x13),
      csr(0x341, 2),

      // MPP=S. RV64 SXL/UXL remain fixed WARL state underneath this write.
      uType(0x1, 3),
      iType(-2048, 3, 0, 3, 0x13),
      csr(0x300, 3),
      mret
    )
    place(resetBase, machineWords) ++ supervisorBody
  }

  private def initialize(dut: AetherCore): Unit = {
    dut.io.imem.inst.poke(nop.U)
    dut.io.imem.fault.poke(false.B)
    dut.io.dmem.ready.poke(true.B)
    dut.io.dmem.rdata.poke(0.U)
    dut.io.dmem.fault.poke(false.B)
    dut.io.ptw.get.ready.poke(false.B)
    dut.io.ptw.get.rdata.poke(0.U)
    dut.io.ptw.get.fault.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
  }

  private def virtualPcForPhysical(pa: BigInt): BigInt = {
    if (pa >= translatedCodeBase && pa < translatedCodeBase + 0x1000)
      supervisorPageBase + (pa - translatedCodeBase)
    else pa
  }

  private def drivePte(dut: AetherCore): Option[BigInt] = {
    dut.io.ptw.get.ready.poke(false.B)
    dut.io.ptw.get.rdata.poke(0.U)
    dut.io.ptw.get.fault.poke(false.B)
    if (!dut.io.ptw.get.valid.peek().litToBoolean) {
      None
    } else {
      val address = dut.io.ptw.get.addr.peek().litValue
      val value = address match {
        case `codeRootPteAddress` => codeRoot
        case `codeLevel1PteAddress` => codeLevel1
        case `codeLeafPteAddress` => codeLeaf
        case `dataRootPteAddress` => dataRoot
        case `dataLevel1PteAddress` => dataLevel1
        case `dataLeafPteAddress` => dataLeaf
        case other => fail(f"unexpected Sv39 PTW physical address 0x$other%x")
      }
      dut.io.ptw.get.rdata.poke(value.U)
      dut.io.ptw.get.ready.poke(true.B)
      Some(address)
    }
  }

  it should "execute three-level Sv39 fetch plus 64-bit Load and Store through PA56" in {
    val supervisorBody = place(
      supervisorEntry,
      Seq(
        // x7 = 0x0000000140403024: another canonical Sv39 VA above 4 GiB.
        iType(1, 0, 0, 7, 0x13),
        slli(7, 7, 32),
        uType(0x40403, 8),
        iType(0x24, 8, 0, 8, 0x13),
        add(7, 7, 8),
        iType(0, 7, 3, 9, 0x03),
        iType(1, 9, 0, 10, 0x13),
        sType(8, 10, 7, 3),
        ebreak
      )
    )
    val program = machineSetup(supervisorBody)

    simulate(new AetherCore(CoreProfiles.rv64imsuSv39PmpSoftware)) { dut =>
      initialize(dut)
      var cycles = 0
      var sawTranslatedFetch = false
      var sawCodeLeafRead = false
      var sawDataLeafRead = false
      var loadCommitted = false
      var storeCommitted = false
      var physicalReads = 0
      var physicalWrites = 0

      while ((!loadCommitted || !storeCommitted) && cycles < 900) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        val virtualPc = virtualPcForPhysical(fetchPa)
        dut.io.imem.inst.poke(program.getOrElse(virtualPc, nop).U)
        if (fetchPa == translatedSupervisorEntry) sawTranslatedFetch = true

        drivePte(dut).foreach { address =>
          if (address == codeLeafPteAddress) sawCodeLeafRead = true
          if (address == dataLeafPteAddress) sawDataLeafRead = true
        }

        dut.io.dmem.ready.poke(true.B)
        dut.io.dmem.fault.poke(false.B)
        dut.io.dmem.rdata.poke(0.U)
        if (dut.io.dmem.valid.peek().litToBoolean) {
          val address = dut.io.dmem.addr.peek().litValue
          if (dut.io.dmem.write.peek().litToBoolean) {
            address shouldBe translatedDataPa + 8
            dut.io.dmem.wdata.peek().litValue shouldBe loadValue + 1
            dut.io.dmem.wmask.peek().litValue shouldBe 0xff
            physicalWrites += 1
          } else {
            address shouldBe translatedDataPa
            dut.io.dmem.rdata.poke(loadValue.U)
            physicalReads += 1
          }
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          dut.io.commit.exception.expect(false.B)
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == supervisorEntry + 0x14) {
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(9.U)
            dut.io.commit.rdData.expect(loadValue.U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(false.B)
            dut.io.commit.memAddr.expect(translatedDataPa.U)
            loadCommitted = true
          }
          if (pc == supervisorEntry + 0x1c) {
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(true.B)
            dut.io.commit.memAddr.expect((translatedDataPa + 8).U)
            dut.io.commit.memWdata.expect((loadValue + 1).U)
            dut.io.commit.memWmask.expect(0xff.U)
            storeCommitted = true
          }
        }
      }

      sawTranslatedFetch shouldBe true
      sawCodeLeafRead shouldBe true
      sawDataLeafRead shouldBe true
      loadCommitted shouldBe true
      storeCommitted shouldBe true
      physicalReads shouldBe 1
      physicalWrites shouldBe 1

      supervisorEntry should be > BigInt("ffffffff", 16)
      dataVa should be > BigInt("ffffffff", 16)
      codeLeaf should be > BigInt("ffffffff", 16)
      dataLeaf should be > BigInt("ffffffff", 16)
      translatedSupervisorEntry should be > BigInt("ffffffff", 16)
      translatedDataPa should be > BigInt("ffffffff", 16)
      translatedDataPa.bitLength should be <= 56
    }
  }
}
