package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.CoreProfiles
import aethercore.core.{AetherCore, MachineCsrAddress, MachineCsrBit, MachineCsrFile}

class MprvCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore MPRV data privilege"

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

  private def write(dut: MachineCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  private def read(dut: MachineCsrFile, address: Int): BigInt = {
    dut.io.readAddr.poke(address.U)
    dut.io.readData.peek().litValue
  }

  it should "use MPP as the explicit data privilege while MPRV is set and clear MPRV on MRET to S" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initializeCsr(dut)
      val mprv = BigInt(1) << MachineCsrBit.MstatusMprv
      val mppS = BigInt(PrivilegeMode.Supervisor) << MachineCsrBit.MstatusMppLow
      val mpie = BigInt(1) << MachineCsrBit.MstatusMpie

      write(dut, MachineCsrAddress.Mstatus, mprv | mppS | mpie)
      (read(dut, MachineCsrAddress.Mstatus) & mprv) shouldBe mprv
      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)
      dut.io.effectiveDataPrivilege.expect(PrivilegeMode.Supervisor.U)

      dut.io.trapReturn.poke(true.B)
      dut.clock.step()
      dut.io.trapReturn.poke(false.B)

      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      dut.io.effectiveDataPrivilege.expect(PrivilegeMode.Supervisor.U)
      (read(dut, MachineCsrAddress.Mstatus) & mprv) shouldBe 0
    }
  }

  it should "keep MPRV read-only zero when U mode is absent" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initializeCsr(dut)
      val mprv = BigInt(1) << MachineCsrBit.MstatusMprv
      write(dut, MachineCsrAddress.Mstatus, mprv)
      (read(dut, MachineCsrAddress.Mstatus) & mprv) shouldBe 0
      dut.io.effectiveDataPrivilege.expect(PrivilegeMode.Machine.U)
    }
  }

  private val base = BigInt("80000000", 16)
  private val va = BigInt("40403024", 16)
  private val rootPpn = BigInt("20000", 16)
  private val nextPpn = BigInt("21000", 16)
  private val leafPpn = BigInt("100001", 16)
  private val translatedPa = (leafPpn << 12) | (va & 0xfff)
  private val vpn1 = (va >> 22) & 0x3ff
  private val vpn0 = (va >> 12) & 0x3ff
  private val rootPteAddress = (rootPpn << 12) + (vpn1 << 2)
  private val leafPteAddress = (nextPpn << 12) + (vpn0 << 2)

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

  private def pte(ppn: BigInt, read: Boolean = false, accessed: Boolean = false): BigInt =
    (ppn << 10) |
      BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0))

  it should "translate an M-mode explicit load through Sv32 as S privilege when MPRV is set" in {
    val program = Map(
      base -> uType(0x80020, 1),                 // satp = Sv32, root PPN 0x20000
      (base + 4) -> csr(0x180, 1),
      (base + 8) -> uType(0x21, 2),             // x2 = 0x21000
      (base + 12) -> iType(-2048, 2, 0, 2, 0x13), // x2 = 0x20800 = MPRV | MPP=S
      (base + 16) -> csr(0x300, 2),
      (base + 20) -> uType(0x40403, 5),
      (base + 24) -> iType(0x24, 5, 0, 5, 0x13),
      (base + 28) -> iType(0, 5, 2, 6, 0x03),   // lw x6, 0(x5)
      (base + 32) -> BigInt("00100073", 16)
    )
    val rootPte = pte(nextPpn)
    val leafPte = pte(leafPpn, read = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      dut.io.imem.inst.poke(BigInt("00000013", 16).U)
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(BigInt("12345678", 16).U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.ptw.get.ready.poke(false.B)
      dut.io.ptw.get.rdata.poke(0.U)
      dut.io.ptw.get.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)

      var sawLoad = false
      var sawRoot = false
      var sawLeaf = false
      var sawPhysicalLoad = false
      var cycles = 0

      while (!sawLoad && cycles < 260) {
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPa, BigInt("00100073", 16)).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          if (address == rootPteAddress) {
            dut.io.ptw.get.rdata.poke(rootPte.U)
            sawRoot = true
          } else if (address == leafPteAddress) {
            dut.io.ptw.get.rdata.poke(leafPte.U)
            sawLeaf = true
          } else {
            fail(f"unexpected PTW address 0x$address%x")
          }
          dut.io.ptw.get.ready.poke(true.B)
        }

        if (dut.io.dmem.valid.peek().litToBoolean) {
          dut.io.dmem.write.expect(false.B)
          dut.io.dmem.addr.expect(translatedPa.U)
          sawPhysicalLoad = true
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.pc.peek().litValue == base + 28) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(6.U)
          dut.io.commit.rdData.expect(BigInt("12345678", 16).U)
          dut.io.commit.memAddr.expect(translatedPa.U)
          sawLoad = true
        }
      }

      sawRoot shouldBe true
      sawLeaf shouldBe true
      sawPhysicalLoad shouldBe true
      sawLoad shouldBe true
    }
  }
}
