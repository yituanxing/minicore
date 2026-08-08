package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.{AetherCore, MachineCsrAddress, MachineCsrFile}

class Sv32PageFaultDelegationSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Sv32 page-fault delegation"

  private val base = BigInt("80000000", 16)
  private val userEntry = base + 0x80
  private val supervisorTrap = BigInt("80400000", 16)
  private val dataVa = BigInt("40403024", 16)
  private val rootPpn = BigInt("20000", 16)
  private val userCodePpn = base >> 12
  private val supervisorCodePpn = supervisorTrap >> 12
  private val userRootPteAddress = (rootPpn << 12) + (((userEntry >> 22) & 0x3ff) << 2)
  private val supervisorRootPteAddress = (rootPpn << 12) + (((supervisorTrap >> 22) & 0x3ff) << 2)
  private val dataRootPteAddress = (rootPpn << 12) + (((dataVa >> 22) & 0x3ff) << 2)
  private val pageFaultMask =
    (BigInt(1) << MachineExceptionCode.InstructionPageFault) |
      (BigInt(1) << MachineExceptionCode.LoadPageFault) |
      (BigInt(1) << MachineExceptionCode.StorePageFault)

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

  it should "make page-fault delegation WARL-visible only on the Sv32 profile" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)
      writeCsr(dut, MachineCsrAddress.Medeleg, pageFaultMask)
      dut.io.readAddr.poke(MachineCsrAddress.Medeleg.U)
      dut.io.readData.expect(0.U)
    }

    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSv32Software.isa)) { dut =>
      initializeCsr(dut)
      writeCsr(dut, MachineCsrAddress.Medeleg, pageFaultMask)
      dut.io.readAddr.poke(MachineCsrAddress.Medeleg.U)
      dut.io.readData.expect(pageFaultMask.U)
    }
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

  private def csrWrite(address: Int, source: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(1) << 12) |
      BigInt(0x73)

  private def csrRead(address: Int, rd: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(2) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  private def leafPte(ppn: BigInt, user: Boolean): BigInt =
    (ppn << 10) | BigInt(1) | (BigInt(1) << 3) |
      (if (user) BigInt(1) << 4 else BigInt(0)) |
      (BigInt(1) << 6)

  it should "route a real U-mode Load page fault to the S-mode trap vector" in {
    val program = Map(
      base -> uType(0x80020, 1),
      (base + 0x04) -> csrWrite(0x180, 1),             // satp
      (base + 0x08) -> uType(0x80400, 2),
      (base + 0x0c) -> csrWrite(0x105, 2),             // stvec = 0x80400000
      (base + 0x10) -> uType(0x2, 3),
      (base + 0x14) -> csrWrite(0x302, 3),             // medeleg[13]
      (base + 0x18) -> uType(0x80000, 4),
      (base + 0x1c) -> iType(0x80, 4, 0, 4, 0x13),
      (base + 0x20) -> csrWrite(0x341, 4),             // mepc = userEntry
      (base + 0x24) -> iType(0, 0, 0, 5, 0x13),
      (base + 0x28) -> csrWrite(0x300, 5),             // MPP=U
      (base + 0x2c) -> BigInt("30200073", 16),

      userEntry -> uType(0x40403, 10),
      (userEntry + 0x04) -> iType(0x24, 10, 0, 10, 0x13),
      (userEntry + 0x08) -> iType(0, 10, 2, 11, 0x03), // unmapped Load

      supervisorTrap -> csrRead(0x142, 12),            // scause
      (supervisorTrap + 0x04) -> csrRead(0x143, 13),   // stval
      (supervisorTrap + 0x08) -> iType(99, 0, 0, 14, 0x13),
      (supervisorTrap + 0x0c) -> BigInt("00100073", 16)
    )
    val userCodeLeaf = leafPte(userCodePpn, user = true)
    val supervisorCodeLeaf = leafPte(supervisorCodePpn, user = false)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      dut.io.imem.inst.poke(BigInt("00000013", 16).U)
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.ptw.get.ready.poke(false.B)
      dut.io.ptw.get.rdata.poke(0.U)
      dut.io.ptw.get.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)

      var cycles = 0
      var sawFault = false
      var sawCause = false
      var sawValue = false
      var sawHandler = false
      var physicalDataRequests = 0

      while (!sawHandler && cycles < 600) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, BigInt("00000013", 16)).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          if (address == userRootPteAddress) {
            dut.io.ptw.get.rdata.poke(userCodeLeaf.U)
          } else if (address == supervisorRootPteAddress) {
            dut.io.ptw.get.rdata.poke(supervisorCodeLeaf.U)
          } else if (address == dataRootPteAddress) {
            dut.io.ptw.get.rdata.poke(0.U) // invalid root PTE -> Load page fault
          } else {
            fail(f"unexpected PTW address 0x$address%x")
          }
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.io.dmem.ready.poke(true.B)
        dut.io.dmem.rdata.poke(0.U)
        dut.io.dmem.fault.poke(false.B)
        if (dut.io.dmem.valid.peek().litToBoolean) physicalDataRequests += 1

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == userEntry + 0x08) {
            dut.io.commit.exception.expect(true.B)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.LoadPageFault.U)
            dut.io.commit.exceptionValue.expect(dataVa.U)
            sawFault = true
          }
          if (pc == supervisorTrap && dut.io.commit.rdWrite.peek().litToBoolean) {
            dut.io.commit.rd.expect(12.U)
            dut.io.commit.rdData.expect(MachineExceptionCode.LoadPageFault.U)
            sawCause = true
          }
          if (pc == supervisorTrap + 0x04 && dut.io.commit.rdWrite.peek().litToBoolean) {
            dut.io.commit.rd.expect(13.U)
            dut.io.commit.rdData.expect(dataVa.U)
            sawValue = true
          }
          if (pc == supervisorTrap + 0x08 && dut.io.commit.rdWrite.peek().litToBoolean) {
            dut.io.commit.rd.expect(14.U)
            dut.io.commit.rdData.expect(99.U)
            sawHandler = true
          }
        }
      }

      sawFault shouldBe true
      sawCause shouldBe true
      sawValue shouldBe true
      sawHandler shouldBe true
      physicalDataRequests shouldBe 0
    }
  }
}
