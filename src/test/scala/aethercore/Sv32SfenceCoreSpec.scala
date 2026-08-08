package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class Sv32SfenceCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore SFENCE.VMA"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x40
  private val userEntry = base + 0x80
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

  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      user: Boolean = false,
      accessed: Boolean = false,
      dirty: Boolean = false
  ): BigInt =
    (ppn << 10) | BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (write) BigInt(1) << 2 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (user) BigInt(1) << 4 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0)) |
      (if (dirty) BigInt(1) << 7 else BigInt(0))

  private def machineSetup(entry: BigInt, supervisor: Boolean, body: Map[BigInt, BigInt]): Map[BigInt, BigInt] = {
    val common = Map(
      base -> uType(0x80020, 1),              // satp = Sv32 | rootPpn 0x20000
      (base + 0x04) -> csr(0x180, 1),
      (base + 0x08) -> uType(0x80000, 2),
      (base + 0x0c) -> iType((entry - base).toInt, 2, 0, 2, 0x13),
      (base + 0x10) -> csr(0x341, 2)          // mepc = entry
    )
    val privilegeSetup = if (supervisor) {
      Map(
        (base + 0x14) -> uType(0x1, 3),
        (base + 0x18) -> iType(-2048, 3, 0, 3, 0x13), // x3 = 0x800, MPP=S
        (base + 0x1c) -> csr(0x300, 3),
        (base + 0x20) -> BigInt("30200073", 16)
      )
    } else {
      Map(
        (base + 0x14) -> iType(0, 0, 0, 3, 0x13),    // x3 = 0, MPP=U
        (base + 0x18) -> csr(0x300, 3),
        (base + 0x1c) -> BigInt("30200073", 16)
      )
    }
    common ++ privilegeSetup ++ body
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

  it should "retain an old cached mapping before SFENCE and observe the new PTE after the fence" in {
    val body = Map(
      supervisorEntry -> uType(0x40403, 5),
      (supervisorEntry + 0x04) -> iType(0x24, 5, 0, 5, 0x13),
      (supervisorEntry + 0x08) -> iType(0, 5, 2, 6, 0x03),
      (supervisorEntry + 0x0c) -> iType(0, 5, 2, 7, 0x03),
      (supervisorEntry + 0x10) -> sfenceVma,
      (supervisorEntry + 0x14) -> iType(0, 5, 2, 8, 0x03),
      (supervisorEntry + 0x18) -> BigInt("00100073", 16)
    )
    val program = machineSetup(supervisorEntry, supervisor = true, body)
    val codeLeaf = pte(codePpn, execute = true, accessed = true)
    val pointer = pte(nextPpn)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initialize(dut)
      var cycles = 0
      var newMapping = false
      var dataPteReads = 0
      var sfenceCommitted = false
      var firstValue = Option.empty[BigInt]
      var secondValue = Option.empty[BigInt]
      var thirdValue = Option.empty[BigInt]

      while (thirdValue.isEmpty && cycles < 700) {
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
            dataPteReads += 1
          } else if (address == dataLeafPteAddress) {
            val leaf = if (newMapping) newLeafPpn else oldLeafPpn
            dut.io.ptw.get.rdata.poke(pte(leaf, read = true, accessed = true).U)
            dataPteReads += 1
          } else {
            fail(f"unexpected PTW address 0x$address%x")
          }
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.io.dmem.ready.poke(true.B)
        dut.io.dmem.fault.poke(false.B)
        if (dut.io.dmem.valid.peek().litToBoolean && !dut.io.dmem.write.peek().litToBoolean) {
          val address = dut.io.dmem.addr.peek().litValue
          if (address == oldPa) dut.io.dmem.rdata.poke(BigInt("11111111", 16).U)
          else if (address == newPa) dut.io.dmem.rdata.poke(BigInt("22222222", 16).U)
          else fail(f"unexpected translated data address 0x$address%x")
        } else {
          dut.io.dmem.rdata.poke(0.U)
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          dut.io.commit.exception.expect(false.B)
          if (pc == supervisorEntry + 0x08) {
            firstValue = Some(dut.io.commit.rdData.peek().litValue)
            newMapping = true // page table changes without a fence
          } else if (pc == supervisorEntry + 0x0c) {
            secondValue = Some(dut.io.commit.rdData.peek().litValue)
            dataPteReads shouldBe 2 // second load must have hit the stale TLB entry
          } else if (pc == supervisorEntry + 0x10) {
            dut.io.commit.inst.expect(sfenceVma.U)
            sfenceCommitted = true
          } else if (pc == supervisorEntry + 0x14) {
            thirdValue = Some(dut.io.commit.rdData.peek().litValue)
          }
        }
      }

      firstValue shouldBe Some(BigInt("11111111", 16))
      secondValue shouldBe Some(BigInt("11111111", 16))
      thirdValue shouldBe Some(BigInt("22222222", 16))
      sfenceCommitted shouldBe true
      dataPteReads shouldBe 4
    }
  }

  it should "trap SFENCE.VMA from U-mode as a precise illegal instruction" in {
    val body = Map(userEntry -> sfenceVma)
    val program = machineSetup(userEntry, supervisor = false, body)
    val userCodeLeaf = pte(codePpn, execute = true, user = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initialize(dut)
      var cycles = 0
      var sawIllegal = false

      while (!sawIllegal && cycles < 300) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, BigInt("00000013", 16)).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.ptw.get.addr.expect(codeRootPteAddress.U)
          dut.io.ptw.get.rdata.poke(userCodeLeaf.U)
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == userEntry) {
          dut.io.commit.exception.expect(true.B)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
          dut.io.commit.exceptionValue.expect(sfenceVma.U)
          sawIllegal = true
        }
      }

      sawIllegal shouldBe true
    }
  }
}
