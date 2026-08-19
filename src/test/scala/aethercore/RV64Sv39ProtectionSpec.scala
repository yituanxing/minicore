package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class RV64Sv39ProtectionSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV64 Sv39 production protection and fencing"

  private val resetBase = BigInt("80000000", 16)
  private val supervisorEntry = BigInt("100000080", 16)
  private val supervisorPageBase = supervisorEntry & ~BigInt("fff", 16)
  private val dataVa = BigInt("140403020", 16)

  private val rootPpn = BigInt("20000", 16)
  private val codeLevel1Ppn = BigInt("21000", 16)
  private val codeLevel0Ppn = BigInt("22000", 16)
  private val dataLevel1Ppn = BigInt("23000", 16)
  private val dataLevel0Ppn = BigInt("24000", 16)

  private val highCodeLeafPpn = BigInt("1000000", 16)
  private val lowCodeLeafPpn = resetBase >> 12
  private val oldDataLeafPpn = BigInt("1000010", 16)
  private val newDataLeafPpn = BigInt("1001010", 16)

  private val highCodeBase = highCodeLeafPpn << 12
  private val translatedSupervisorEntry = highCodeBase | (supervisorEntry & 0xfff)
  private val oldDataPa = (oldDataLeafPpn << 12) | (dataVa & 0xfff)
  private val newDataPa = (newDataLeafPpn << 12) | (dataVa & 0xfff)

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
  private val dataRoot = pte(dataLevel1Ppn)
  private val dataLevel1 = pte(dataLevel0Ppn)

  private def allowAllPmp: Seq[BigInt] = Seq(
    iType(-1, 0, 0, 5, 0x13),
    csr(0x3b0, 5),
    iType(15, 0, 0, 5, 0x13),
    csr(0x3a0, 5)
  )

  // pmpaddr stores bits PA[55:2]. 0x40000000 therefore makes one TOR region
  // [0, 4 GiB), enough for the page tables and a deliberately low code leaf.
  private def allowBelow4GiB: Seq[BigInt] = Seq(
    uType(0x40000, 5),
    csr(0x3b0, 5),
    iType(15, 0, 0, 5, 0x13),
    csr(0x3a0, 5)
  )

  // [0, 256 MiB) excludes the first root PTE around physical 0x20000000.
  // M-mode setup still executes because unlocked PMP does not constrain M-mode.
  private def denyPageTables: Seq[BigInt] = Seq(
    uType(0x04000, 5),
    csr(0x3b0, 5),
    iType(15, 0, 0, 5, 0x13),
    csr(0x3a0, 5)
  )

  private def machineSetup(
      physicalSupervisorBody: Map[BigInt, BigInt],
      pmpSetup: Seq[BigInt]
  ): Map[BigInt, BigInt] = {
    val words = pmpSetup ++ Seq(
      // satp = Sv39(mode=8) | rootPpn.
      iType(1, 0, 0, 1, 0x13),
      slli(1, 1, 63),
      uType(0x20, 2),
      add(1, 1, 2),
      csr(0x180, 1),

      // mepc = 0x0000000100000080.
      iType(1, 0, 0, 2, 0x13),
      slli(2, 2, 32),
      iType(0x80, 2, 0, 2, 0x13),
      csr(0x341, 2),

      // MPP=S.
      uType(0x1, 3),
      iType(-2048, 3, 0, 3, 0x13),
      csr(0x300, 3),
      mret
    )
    place(resetBase, words) ++ physicalSupervisorBody
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

  private def virtualPcForHighCode(pa: BigInt): BigInt = {
    if (pa >= highCodeBase && pa < highCodeBase + 0x1000)
      supervisorPageBase + (pa - highCodeBase)
    else pa
  }

  it should "raise an Sv39 instruction page fault before any final physical fetch" in {
    val program = machineSetup(Map.empty, allowAllPmp)
    val invalidLeaf = pte(highCodeLeafPpn, execute = true, accessed = true, valid = false)

    simulate(new AetherCore(CoreProfiles.rv64imsuSv39PmpSoftware)) { dut =>
      initialize(dut)
      var cycles = 0
      var pteReads = 0
      var leakedFinalFetch = false
      var sawFault = false

      while (!sawFault && cycles < 520) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        if (dut.io.imem.valid.peek().litToBoolean && fetchPa == translatedSupervisorEntry) {
          leakedFinalFetch = true
        }
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, nop).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          val value = address match {
            case `codeRootPteAddress` => codeRoot
            case `codeLevel1PteAddress` => codeLevel1
            case `codeLeafPteAddress` => invalidLeaf
            case other => fail(f"unexpected Sv39 PTW address 0x$other%x")
          }
          dut.io.ptw.get.rdata.poke(value.U)
          dut.io.ptw.get.ready.poke(true.B)
          pteReads += 1
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionPageFault.U)
          dut.io.commit.exceptionValue.expect(supervisorEntry.U)
          sawFault = true
        }
      }

      pteReads shouldBe 3
      leakedFinalFetch shouldBe false
      sawFault shouldBe true
    }
  }

  it should "apply PMP to the final Sv39 instruction PA before exposing imem" in {
    val program = machineSetup(Map.empty, allowBelow4GiB)
    val highLeaf = pte(highCodeLeafPpn, execute = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv64imsuSv39PmpSoftware)) { dut =>
      initialize(dut)
      var cycles = 0
      var leafRead = false
      var sawDeniedPa = false
      var leakedDeniedFetch = false
      var sawFault = false

      while (!sawFault && cycles < 520) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        if (fetchPa == translatedSupervisorEntry) {
          sawDeniedPa = true
          if (dut.io.imem.valid.peek().litToBoolean) leakedDeniedFetch = true
        }
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, nop).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          val value = address match {
            case `codeRootPteAddress` => codeRoot
            case `codeLevel1PteAddress` => codeLevel1
            case `codeLeafPteAddress` =>
              leafRead = true
              highLeaf
            case other => fail(f"unexpected Sv39 PTW address 0x$other%x")
          }
          dut.io.ptw.get.rdata.poke(value.U)
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAccessFault.U)
          dut.io.commit.exceptionValue.expect(supervisorEntry.U)
          sawFault = true
        }
      }

      leafRead shouldBe true
      sawDeniedPa shouldBe true
      leakedDeniedFetch shouldBe false
      sawFault shouldBe true
      translatedSupervisorEntry should be > BigInt("ffffffff", 16)
    }
  }

  it should "apply PMP before issuing an implicit Sv39 page-table read" in {
    val program = machineSetup(Map.empty, denyPageTables)

    simulate(new AetherCore(CoreProfiles.rv64imsuSv39PmpSoftware)) { dut =>
      initialize(dut)
      var cycles = 0
      var externalPtwRequests = 0
      var sawFault = false

      while (!sawFault && cycles < 520) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, nop).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          externalPtwRequests += 1
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAccessFault.U)
          dut.io.commit.exceptionValue.expect(supervisorEntry.U)
          sawFault = true
        }
      }

      externalPtwRequests shouldBe 0
      sawFault shouldBe true
    }
  }

  it should "retain a stale Sv39 data mapping until SFENCE.VMA then rewalk all three levels" in {
    val supervisorBody = place(
      supervisorEntry,
      Seq(
        // x7 = 0x0000000140403020.
        iType(1, 0, 0, 7, 0x13),
        slli(7, 7, 32),
        uType(0x40403, 8),
        iType(0x20, 8, 0, 8, 0x13),
        add(7, 7, 8),
        iType(0, 7, 3, 9, 0x03),
        nop,
        nop,
        iType(0, 7, 3, 10, 0x03),
        sfenceVma,
        iType(0, 7, 3, 11, 0x03),
        ebreak
      )
    )
    val program = machineSetup(supervisorBody, allowAllPmp)
    val codeLeaf = pte(highCodeLeafPpn, execute = true, accessed = true)
    val oldValue = BigInt("1111111122222222", 16)
    val newValue = BigInt("3333333344444444", 16)

    simulate(new AetherCore(CoreProfiles.rv64imsuSv39PmpSoftware)) { dut =>
      initialize(dut)
      var cycles = 0
      var newMapping = false
      var dataPteReads = 0
      var sfenceCommitted = false
      var firstValue = Option.empty[BigInt]
      var secondValue = Option.empty[BigInt]
      var thirdValue = Option.empty[BigInt]

      while (thirdValue.isEmpty && cycles < 1200) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        val virtualPc = virtualPcForHighCode(fetchPa)
        dut.io.imem.inst.poke(program.getOrElse(virtualPc, nop).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          val value = address match {
            case `codeRootPteAddress` => codeRoot
            case `codeLevel1PteAddress` => codeLevel1
            case `codeLeafPteAddress` => codeLeaf
            case `dataRootPteAddress` =>
              dataPteReads += 1
              dataRoot
            case `dataLevel1PteAddress` =>
              dataPteReads += 1
              dataLevel1
            case `dataLeafPteAddress` =>
              dataPteReads += 1
              val ppn = if (newMapping) newDataLeafPpn else oldDataLeafPpn
              pte(ppn, read = true, accessed = true)
            case other => fail(f"unexpected Sv39 PTW address 0x$other%x")
          }
          dut.io.ptw.get.rdata.poke(value.U)
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.io.dmem.ready.poke(true.B)
        dut.io.dmem.fault.poke(false.B)
        dut.io.dmem.rdata.poke(0.U)
        if (dut.io.dmem.valid.peek().litToBoolean && !dut.io.dmem.write.peek().litToBoolean) {
          val address = dut.io.dmem.addr.peek().litValue
          if (address == oldDataPa) dut.io.dmem.rdata.poke(oldValue.U)
          else if (address == newDataPa) dut.io.dmem.rdata.poke(newValue.U)
          else fail(f"unexpected translated data address 0x$address%x")
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          dut.io.commit.exception.expect(false.B)
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == supervisorEntry + 0x14) {
            firstValue = Some(dut.io.commit.rdData.peek().litValue)
            newMapping = true
          } else if (pc == supervisorEntry + 0x20) {
            secondValue = Some(dut.io.commit.rdData.peek().litValue)
            dataPteReads shouldBe 3
          } else if (pc == supervisorEntry + 0x24) {
            dut.io.commit.inst.expect(sfenceVma.U)
            sfenceCommitted = true
          } else if (pc == supervisorEntry + 0x28) {
            thirdValue = Some(dut.io.commit.rdData.peek().litValue)
          }
        }
      }

      firstValue shouldBe Some(oldValue)
      secondValue shouldBe Some(oldValue)
      thirdValue shouldBe Some(newValue)
      sfenceCommitted shouldBe true
      dataPteReads shouldBe 6
      oldDataPa should be > BigInt("ffffffff", 16)
      newDataPa should be > BigInt("ffffffff", 16)
    }
  }
}
