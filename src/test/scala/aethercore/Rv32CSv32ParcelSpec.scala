package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.{CoreConfig, CoreProfiles}
import aethercore.core.AetherCore

class Rv32CSv32ParcelSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV32C Sv32 parcel fetch"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x0ffe
  private val rootPpn = BigInt("20000", 16)
  private val l0Ppn = BigInt("21000", 16)
  private val codeVpn1 = (supervisorEntry >> 22) & 0x3ff
  private val codeVpn0 = (supervisorEntry >> 12) & 0x3ff
  private val rootPteAddress = (rootPpn << 12) + (codeVpn1 << 2)
  private val firstLeafAddress = (l0Ppn << 12) + (codeVpn0 << 2)
  private val secondLeafAddress = firstLeafAddress + 4
  private val translatedFirstPpn = BigInt("100000", 16)
  private val translatedSecondPpn = BigInt("120000", 16)
  private val translatedFirstParcel = (translatedFirstPpn << 12) + 0xffe
  private val translatedSecondParcel = translatedSecondPpn << 12

  private val config = CoreProfiles.rv32imsuSv32Software.copy(
    name = "rv32imcsu-sv32-parcel-test",
    isa = CoreProfiles.rv32imsuSv32Software.isa.copy(
      extensions = CoreProfiles.rv32imsuSv32Software.isa.extensions + 'C'
    )
  )
  private val pmpConfig = config.copy(
    name = "rv32imcsu-sv32-pmp-parcel-test",
    isa = config.isa.copy(pmpEntries = 16)
  )

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

  private def splitWord(address: BigInt, word: BigInt): Seq[(BigInt, BigInt)] = Seq(
    address -> (word & 0xffff),
    (address + 2) -> ((word >> 16) & 0xffff)
  )

  private val bootWords = Map(
    base -> uType(0x80020, 1), // satp = Sv32 | rootPpn
    (base + 0x04) -> csr(0x180, 1),
    (base + 0x08) -> uType(0x80001, 2),
    (base + 0x0c) -> iType(-2, 2, 0, 2, 0x13), // mepc = page_end - 2
    (base + 0x10) -> csr(0x341, 2),
    (base + 0x14) -> uType(0x1, 3),
    (base + 0x18) -> iType(-2048, 3, 0, 3, 0x13), // MPP=S
    (base + 0x1c) -> csr(0x300, 3),
    (base + 0x20) -> BigInt("30200073", 16)
  )
  private val bootParcels = bootWords.toSeq.flatMap { case (address, word) => splitWord(address, word) }.toMap

  // Allow S-mode/PTW access through the first translated code page, while
  // leaving the deliberately non-contiguous second code page outside TOR.
  private val pmpTop = ((translatedFirstPpn << 12) + 0x1000) >> 2
  private val pmpBootWords = Map(
    base -> uType((pmpTop >> 12).toInt, 4),
    (base + 0x04) -> iType((pmpTop & 0xfff).toInt, 4, 0, 4, 0x13),
    (base + 0x08) -> csr(0x3b0, 4), // pmpaddr0
    (base + 0x0c) -> iType(0x0d, 0, 0, 4, 0x13), // TOR + R + X
    (base + 0x10) -> csr(0x3a0, 4), // pmpcfg0
    (base + 0x14) -> uType(0x80020, 1),
    (base + 0x18) -> csr(0x180, 1),
    (base + 0x1c) -> uType(0x80001, 2),
    (base + 0x20) -> iType(-2, 2, 0, 2, 0x13),
    (base + 0x24) -> csr(0x341, 2),
    (base + 0x28) -> uType(0x1, 3),
    (base + 0x2c) -> iType(-2048, 3, 0, 3, 0x13),
    (base + 0x30) -> csr(0x300, 3),
    (base + 0x34) -> BigInt("30200073", 16)
  )
  private val pmpBootParcels =
    pmpBootWords.toSeq.flatMap { case (address, word) => splitWord(address, word) }.toMap

  private def pte(
      ppn: BigInt,
      execute: Boolean = false,
      accessed: Boolean = false,
      valid: Boolean = true
  ): BigInt =
    (ppn << 10) |
      (if (valid) BigInt(1) else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0))

  private val rootPte = pte(l0Ppn)
  private val firstLeaf = pte(translatedFirstPpn, execute = true, accessed = true)
  private val mappedSecondLeaf = pte(translatedSecondPpn, execute = true, accessed = true)

  private def initialize(dut: AetherCore): Unit = {
    dut.io.imem.inst.poke(0.U)
    dut.io.imem.fault.poke(false.B)
    dut.io.dmem.ready.poke(true.B)
    dut.io.dmem.rdata.poke(0.U)
    dut.io.dmem.fault.poke(false.B)
    dut.io.ptw.get.ready.poke(false.B)
    dut.io.ptw.get.rdata.poke(0.U)
    dut.io.ptw.get.fault.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
  }

  private def drivePtw(dut: AetherCore, secondLeafPte: BigInt = 0): Boolean = {
    dut.io.ptw.get.ready.poke(false.B)
    dut.io.ptw.get.rdata.poke(0.U)
    dut.io.ptw.get.fault.poke(false.B)
    var secondLeaf = false
    if (dut.io.ptw.get.valid.peek().litToBoolean) {
      val address = dut.io.ptw.get.addr.peek().litValue
      val data =
        if (address == rootPteAddress) rootPte
        else if (address == firstLeafAddress) firstLeaf
        else if (address == secondLeafAddress) {
          secondLeaf = true
          secondLeafPte
        } else BigInt(0)
      dut.io.ptw.get.rdata.poke(data.U)
      dut.io.ptw.get.ready.poke(true.B)
    }
    secondLeaf
  }

  private def parcelFor(
      address: BigInt,
      supervisorFirstParcel: BigInt,
      supervisorSecondParcel: Option[BigInt] = None,
      bootImage: Map[BigInt, BigInt] = bootParcels
  ): BigInt =
    if (address == translatedFirstParcel) supervisorFirstParcel
    else if (address == translatedSecondParcel) supervisorSecondParcel.getOrElse(BigInt("9002", 16))
    else bootImage.getOrElse(address, BigInt("9002", 16))

  it should "retire a compressed instruction at page_end-2 without consuming the next page" in {
    simulate(new AetherCore(config)) { dut =>
      initialize(dut)
      var sawCommit = false
      var cycles = 0

      while (!sawCommit && cycles < 420) {
        dut.io.imem.bytes.expect(2.U)
        val address = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(parcelFor(address, BigInt("12e5", 16)).U)
        drivePtw(dut)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.instBytes.expect(2.U)
          dut.io.commit.rawInst.expect("h000012e5".U)
          dut.io.commit.inst.expect("hff928293".U)
          dut.io.commit.rd.expect(5.U)
          dut.io.commit.rdData.expect("hfffffff9".U)
          sawCommit = true
        }
      }

      sawCommit shouldBe true
    }
  }

  it should "assemble a cross-page 32-bit instruction from non-contiguous physical pages" in {
    val instruction = BigInt("00a28313", 16)
    val lowParcel = instruction & 0xffff
    val highParcel = (instruction >> 16) & 0xffff

    simulate(new AetherCore(config)) { dut =>
      initialize(dut)
      var sawSecondLeaf = false
      var sawSecondPhysicalParcel = false
      var sawCommit = false
      var cycles = 0

      while (!sawCommit && cycles < 460) {
        dut.io.imem.bytes.expect(2.U)
        val address = dut.io.imem.addr.peek().litValue
        if (address == translatedSecondParcel && dut.io.imem.valid.peek().litToBoolean) {
          sawSecondPhysicalParcel = true
        }
        dut.io.imem.inst.poke(parcelFor(address, lowParcel, Some(highParcel)).U)
        sawSecondLeaf ||= drivePtw(dut, mappedSecondLeaf)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.inst.expect(instruction.U)
          dut.io.commit.rawInst.expect(instruction.U)
          dut.io.commit.instBytes.expect(4.U)
          dut.io.commit.rd.expect(6.U)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rdData.expect(10.U)
          sawCommit = true
        }
      }

      sawSecondLeaf shouldBe true
      sawSecondPhysicalParcel shouldBe true
      sawCommit shouldBe true
    }
  }

  it should "fault the second half of a cross-page 32-bit instruction at PC+2" in {
    simulate(new AetherCore(config)) { dut =>
      initialize(dut)
      var sawSecondLeaf = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 420) {
        dut.io.imem.bytes.expect(2.U)
        val address = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(parcelFor(address, BigInt("8313", 16)).U)
        sawSecondLeaf ||= drivePtw(dut)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionPageFault.U)
          dut.io.commit.exceptionValue.expect((supervisorEntry + 2).U)
          dut.io.commit.instBytes.expect(4.U)
          sawFault = true
        }
      }

      sawSecondLeaf shouldBe true
      sawFault shouldBe true
    }
  }

  it should "deny the translated second parcel at final physical PMP without issuing imem valid" in {
    val instruction = BigInt("00a28313", 16)
    val lowParcel = instruction & 0xffff
    val highParcel = (instruction >> 16) & 0xffff

    simulate(new AetherCore(pmpConfig)) { dut =>
      initialize(dut)
      var sawFirstPhysicalParcel = false
      var sawSecondLeaf = false
      var sawDeniedPhysicalAddress = false
      var sawDeniedPhysicalValid = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 520) {
        dut.io.imem.bytes.expect(2.U)
        val address = dut.io.imem.addr.peek().litValue
        val valid = dut.io.imem.valid.peek().litToBoolean
        if (address == translatedFirstParcel && valid) sawFirstPhysicalParcel = true
        if (address == translatedSecondParcel) {
          sawDeniedPhysicalAddress = true
          sawDeniedPhysicalValid ||= valid
        }
        dut.io.imem.inst.poke(parcelFor(address, lowParcel, Some(highParcel), pmpBootParcels).U)
        sawSecondLeaf ||= drivePtw(dut, mappedSecondLeaf)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAccessFault.U)
          dut.io.commit.exceptionValue.expect((supervisorEntry + 2).U)
          dut.io.commit.instBytes.expect(4.U)
          sawFault = true
        }
      }

      sawFirstPhysicalParcel shouldBe true
      sawSecondLeaf shouldBe true
      sawDeniedPhysicalAddress shouldBe true
      sawDeniedPhysicalValid shouldBe false
      sawFault shouldBe true
    }
  }
}
