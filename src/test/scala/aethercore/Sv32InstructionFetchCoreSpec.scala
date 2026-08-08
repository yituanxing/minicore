package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class Sv32InstructionFetchCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore Sv32 instruction fetch"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x40
  private val rootPpn = BigInt("20000", 16)
  private val codeVpn1 = (supervisorEntry >> 22) & 0x3ff
  private val codeRootPteAddress = (rootPpn << 12) + (codeVpn1 << 2)

  // Map the 0x80000000 virtual megapage onto PA 0x100000000 so instruction
  // fetch proves the full Sv32 34-bit physical address path rather than an
  // identity mapping that could accidentally pass through old RV32 wiring.
  private val translatedCodePpn = BigInt("100000", 16)
  private val translatedMegapageBase = BigInt("100000000", 16)
  private val translatedSupervisorEntry = translatedMegapageBase + (supervisorEntry & 0x3fffff)

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

  private def program(supervisorBody: Map[BigInt, BigInt]): Map[BigInt, BigInt] = Map(
    base -> uType(0x80020, 1),
    (base + 0x04) -> csr(0x180, 1),
    (base + 0x08) -> uType(0x80000, 2),
    (base + 0x0c) -> iType(0x40, 2, 0, 2, 0x13),
    (base + 0x10) -> csr(0x341, 2),
    (base + 0x14) -> uType(0x1, 3),
    (base + 0x18) -> iType(-2048, 3, 0, 3, 0x13),
    (base + 0x1c) -> csr(0x300, 3),
    (base + 0x20) -> BigInt("30200073", 16)
  ) ++ supervisorBody

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

  private def virtualPcForPhysical(pa: BigInt): BigInt = {
    if (pa >= translatedMegapageBase && pa < translatedMegapageBase + 0x400000)
      base + (pa - translatedMegapageBase)
    else pa
  }

  it should "fetch and retire Supervisor instructions through a PA above 4 GiB" in {
    val body = Map(
      supervisorEntry -> iType(77, 0, 0, 5, 0x13),
      (supervisorEntry + 4) -> iType(1, 5, 0, 6, 0x13),
      (supervisorEntry + 8) -> BigInt("00100073", 16)
    )
    val image = program(body)
    val codeLeaf = pte(translatedCodePpn, execute = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initialize(dut)
      var cycles = 0
      var sawTranslatedFetch = false
      var sawSupervisorCommit = false

      while (!sawSupervisorCommit && cycles < 320) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        val virtualPc = virtualPcForPhysical(fetchPa)
        dut.io.imem.inst.poke(image.getOrElse(virtualPc, BigInt("00000013", 16)).U)
        if (fetchPa == translatedSupervisorEntry) sawTranslatedFetch = true

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.ptw.get.addr.expect(codeRootPteAddress.U)
          dut.io.ptw.get.rdata.poke(codeLeaf.U)
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry + 4) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(6.U)
          dut.io.commit.rdData.expect(78.U)
          sawSupervisorCommit = true
        }
      }

      sawTranslatedFetch shouldBe true
      sawSupervisorCommit shouldBe true
      translatedSupervisorEntry should be > BigInt("ffffffff", 16)
    }
  }

  it should "raise a precise instruction page fault with the original virtual PC" in {
    val image = program(Map.empty)
    val nonExecutableLeaf = pte(translatedCodePpn, read = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initialize(dut)
      var cycles = 0
      var sawFault = false

      while (!sawFault && cycles < 300) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(image.getOrElse(fetchPa, BigInt("00000013", 16)).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.ptw.get.addr.expect(codeRootPteAddress.U)
          dut.io.ptw.get.rdata.poke(nonExecutableLeaf.U)
          dut.io.ptw.get.ready.poke(true.B)
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

      sawFault shouldBe true
    }
  }

  it should "report an implicit instruction PTE read failure as an instruction access fault" in {
    val image = program(Map.empty)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initialize(dut)
      var cycles = 0
      var sawFault = false

      while (!sawFault && cycles < 300) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(image.getOrElse(fetchPa, BigInt("00000013", 16)).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.ptw.get.addr.expect(codeRootPteAddress.U)
          dut.io.ptw.get.fault.poke(true.B)
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

      sawFault shouldBe true
    }
  }
}
