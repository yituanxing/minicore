package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class Sv32PmpCompositionSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore Sv32 plus PMP physical composition"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x40
  private val rootPpn = BigInt("20000", 16)
  private val nextPpn = BigInt("21000", 16)
  private val va = BigInt("40403024", 16)
  private val vpn1 = (va >> 22) & 0x3ff
  private val vpn0 = (va >> 12) & 0x3ff
  private val rootPteAddress = (rootPpn << 12) + (vpn1 << 2)
  private val leafPteAddress = (nextPpn << 12) + (vpn0 << 2)
  private val leafPpn = BigInt("100001", 16)
  private val translatedDataPa = (leafPpn << 12) | (va & 0xfff)

  private val codeVpn1 = (supervisorEntry >> 22) & 0x3ff
  private val codeRootPteAddress = (rootPpn << 12) + (codeVpn1 << 2)
  private val identityCodePpn = base >> 12
  private val translatedCodePpn = BigInt("100000", 16)
  private val translatedCodeBase = BigInt("100000000", 16)
  private val translatedSupervisorEntry = translatedCodeBase + (supervisorEntry & 0x3fffff)

  private val pmpcfg0 = 0x3a0
  private val pmpaddr0 = 0x3b0
  private val satp = 0x180
  private val mstatus = 0x300
  private val mepc = 0x341

  private val nop = BigInt("00000013", 16)
  private val ebreak = BigInt("00100073", 16)
  private val mret = BigInt("30200073", 16)

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

  private def machineSetup(
      supervisorBody: Map[BigInt, BigInt],
      pmpSetup: Seq[BigInt]
  ): Map[BigInt, BigInt] = {
    val words = mutable.ArrayBuffer.empty[BigInt]
    words ++= pmpSetup
    words ++= Seq(
      uType(0x80020, 1),
      csr(satp, 1),
      uType(0x80000, 2),
      iType(0x40, 2, 0, 2, 0x13),
      csr(mepc, 2),
      uType(0x1, 3),
      iType(-2048, 3, 0, 3, 0x13),
      csr(mstatus, 3),
      mret
    )
    place(base, words.toSeq) ++ supervisorBody
  }

  private def allowBelow4GiB: Seq[BigInt] =
    li(5, BigInt("40000000", 16)) ++ Seq(csr(pmpaddr0, 5)) ++
      li(5, 0x0f) ++ Seq(csr(pmpcfg0, 5))

  private def denyPageTablesAllowCode: Seq[BigInt] =
    li(5, BigInt("20000000", 16)) ++ Seq(csr(pmpaddr0 + 0, 5)) ++
      li(5, BigInt("20100000", 16)) ++ Seq(csr(pmpaddr0 + 1, 5)) ++
      li(5, BigInt("00000f08", 16)) ++ Seq(csr(pmpcfg0, 5))

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
    dut.io.time.get.poke(0.U)
  }

  it should "deny a translated instruction PA without issuing a physical fetch" in {
    val program = machineSetup(Map(supervisorEntry -> ebreak), allowBelow4GiB)
    val translatedCodeLeaf = pte(translatedCodePpn, execute = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv32imasuSv32PmpSoftware)) { dut =>
      initialize(dut)
      var sawPteRead = false
      var sawDeniedPa = false
      var sawDeniedPhysicalFetch = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 360) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        val fetchValid = dut.io.imem.valid.peek().litToBoolean
        if (fetchPa == translatedSupervisorEntry) {
          sawDeniedPa = true
          sawDeniedPhysicalFetch ||= fetchValid
        }
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, nop).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.ptw.get.addr.expect(codeRootPteAddress.U)
          dut.io.ptw.get.rdata.poke(translatedCodeLeaf.U)
          dut.io.ptw.get.ready.poke(true.B)
          sawPteRead = true
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

      sawPteRead shouldBe true
      sawDeniedPa shouldBe true
      sawDeniedPhysicalFetch shouldBe false
      sawFault shouldBe true
      translatedSupervisorEntry should be > BigInt("ffffffff", 16)
    }
  }

  it should "deny a translated data PA without issuing a physical data request" in {
    val program = machineSetup(
      Map(
        supervisorEntry -> uType(0x40403, 5),
        (supervisorEntry + 0x04) -> iType(0x24, 5, 0, 5, 0x13),
        (supervisorEntry + 0x08) -> iType(0, 5, 2, 6, 0x03),
        (supervisorEntry + 0x0c) -> ebreak
      ),
      allowBelow4GiB
    )
    val identityCodeLeaf = pte(identityCodePpn, execute = true, accessed = true)
    val dataRoot = pte(nextPpn)
    val dataLeaf = pte(leafPpn, read = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv32imasuSv32PmpSoftware)) { dut =>
      initialize(dut)
      var sawDataWalk = false
      var sawDeniedDataRequest = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 520) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, nop).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          if (address == codeRootPteAddress) {
            dut.io.ptw.get.rdata.poke(identityCodeLeaf.U)
          } else if (address == rootPteAddress) {
            dut.io.ptw.get.rdata.poke(dataRoot.U)
            sawDataWalk = true
          } else if (address == leafPteAddress) {
            dut.io.ptw.get.rdata.poke(dataLeaf.U)
            sawDataWalk = true
          } else {
            fail(f"unexpected PTW address 0x$address%x")
          }
          dut.io.ptw.get.ready.poke(true.B)
        }

        if (dut.io.dmem.valid.peek().litToBoolean &&
            dut.io.dmem.addr.peek().litValue == translatedDataPa) {
          sawDeniedDataRequest = true
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry + 0x08) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.LoadAccessFault.U)
          dut.io.commit.exceptionValue.expect(va.U)
          dut.io.commit.memValid.expect(false.B)
          sawFault = true
        }
      }

      sawDataWalk shouldBe true
      sawDeniedDataRequest shouldBe false
      sawFault shouldBe true
      translatedDataPa should be > BigInt("ffffffff", 16)
    }
  }

  it should "deny an implicit S-mode PTE read without issuing an external PTW request" in {
    val program = machineSetup(Map(supervisorEntry -> ebreak), denyPageTablesAllowCode)

    simulate(new AetherCore(CoreProfiles.rv32imasuSv32PmpSoftware)) { dut =>
      initialize(dut)
      var externalPtwRequests = 0
      var sawSuppressedPteAddress = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 360) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, nop).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          externalPtwRequests += 1
        } else if (dut.io.ptw.get.addr.peek().litValue == codeRootPteAddress) {
          sawSuppressedPteAddress = true
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

      sawSuppressedPteAddress shouldBe true
      externalPtwRequests shouldBe 0
      sawFault shouldBe true
    }
  }
}
