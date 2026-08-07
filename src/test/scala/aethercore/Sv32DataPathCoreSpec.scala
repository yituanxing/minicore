package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore

class Sv32DataPathCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore Sv32 data path"

  private val base = BigInt("80000000", 16)
  private val supervisorEntry = base + 0x40
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

  private def csr(address: Int, source: Int, funct3: Int = 1, rd: Int = 0): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
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

  private def machineSetup(supervisorBody: Map[BigInt, BigInt]): Map[BigInt, BigInt] = {
    val setup = Map(
      base -> uType(0x80020, 1),                         // x1 = Sv32 | rootPpn 0x20000
      (base + 0x04) -> csr(0x180, 1),                   // csrw satp, x1
      (base + 0x08) -> uType(0x80000, 2),
      (base + 0x0c) -> iType(0x40, 2, 0, 2, 0x13),      // x2 = supervisorEntry
      (base + 0x10) -> csr(0x341, 2),                   // csrw mepc, x2
      (base + 0x14) -> uType(0x1, 3),
      (base + 0x18) -> iType(-2048, 3, 0, 3, 0x13),     // x3 = MPP=S (0x800)
      (base + 0x1c) -> csr(0x300, 3),                   // csrw mstatus, x3
      (base + 0x20) -> BigInt("30200073", 16)           // mret
    )
    setup ++ supervisorBody
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

  it should "translate real S-mode Load and Store accesses to a PA above 4 GiB" in {
    val program = machineSetup(Map(
      supervisorEntry -> uType(0x40403, 5),
      (supervisorEntry + 0x04) -> iType(0x24, 5, 0, 5, 0x13),
      (supervisorEntry + 0x08) -> iType(0, 5, 2, 6, 0x03), // lw x6, 0(x5)
      (supervisorEntry + 0x0c) -> iType(1, 6, 0, 7, 0x13),
      (supervisorEntry + 0x10) -> sType(4, 7, 5, 2),       // sw x7, 4(x5)
      (supervisorEntry + 0x14) -> BigInt("00100073", 16)
    ))
    val pointerPte = pte(nextPpn)
    val leaf = pte(leafPpn, read = true, write = true, accessed = true, dirty = true)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initialize(dut)
      var cycles = 0
      var pteReads = 0
      var loadCommitted = false
      var storeCommitted = false
      var physicalReads = 0
      var physicalWrites = 0

      while ((!loadCommitted || !storeCommitted) && cycles < 300) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          val value =
            if (address == rootPteAddress) pointerPte
            else if (address == leafPteAddress) leaf
            else fail(f"unexpected PTW physical address 0x$address%x")
          dut.io.ptw.get.rdata.poke(value.U)
          dut.io.ptw.get.ready.poke(true.B)
          pteReads += 1
        }

        dut.io.dmem.ready.poke(true.B)
        dut.io.dmem.fault.poke(false.B)
        dut.io.dmem.rdata.poke(BigInt("12345678", 16).U)
        if (dut.io.dmem.valid.peek().litToBoolean) {
          val address = dut.io.dmem.addr.peek().litValue
          if (dut.io.dmem.write.peek().litToBoolean) {
            address shouldBe translatedPa + 4
            dut.io.dmem.wdata.peek().litValue shouldBe BigInt("12345679", 16)
            dut.io.dmem.wmask.peek().litValue shouldBe 0xf
            physicalWrites += 1
          } else {
            address shouldBe translatedPa
            physicalReads += 1
          }
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          dut.io.commit.exception.expect(false.B)
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == supervisorEntry + 0x08) {
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(6.U)
            dut.io.commit.rdData.expect(BigInt("12345678", 16).U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(false.B)
            dut.io.commit.memAddr.expect(translatedPa.U)
            loadCommitted = true
          }
          if (pc == supervisorEntry + 0x10) {
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(true.B)
            dut.io.commit.memAddr.expect((translatedPa + 4).U)
            storeCommitted = true
          }
        }
      }

      loadCommitted shouldBe true
      storeCommitted shouldBe true
      pteReads shouldBe 4
      physicalReads shouldBe 1
      physicalWrites shouldBe 1
      translatedPa should be > BigInt("ffffffff", 16)
    }
  }

  it should "raise precise Load and Store page faults before any physical data request" in {
    val loadProgram = machineSetup(Map(
      supervisorEntry -> uType(0x40403, 5),
      (supervisorEntry + 0x04) -> iType(0x24, 5, 0, 5, 0x13),
      (supervisorEntry + 0x08) -> iType(0, 5, 2, 6, 0x03)
    ))
    val storeProgram = machineSetup(Map(
      supervisorEntry -> uType(0x40403, 5),
      (supervisorEntry + 0x04) -> iType(0x24, 5, 0, 5, 0x13),
      (supervisorEntry + 0x08) -> iType(7, 0, 0, 6, 0x13),
      (supervisorEntry + 0x0c) -> sType(0, 6, 5, 2)
    ))

    def runFault(program: Map[BigInt, BigInt], expectedPc: BigInt, expectedCause: Int, leaf: BigInt): Unit = {
      simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
        initialize(dut)
        var cycles = 0
        var sawFault = false
        var physicalRequests = 0

        while (!sawFault && cycles < 260) {
          val fetchPc = dut.io.imem.addr.peek().litValue
          dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

          dut.io.ptw.get.ready.poke(false.B)
          dut.io.ptw.get.rdata.poke(0.U)
          dut.io.ptw.get.fault.poke(false.B)
          if (dut.io.ptw.get.valid.peek().litToBoolean) {
            val address = dut.io.ptw.get.addr.peek().litValue
            dut.io.ptw.get.rdata.poke(
              (if (address == rootPteAddress) pte(nextPpn) else leaf).U
            )
            dut.io.ptw.get.ready.poke(true.B)
          }

          dut.io.dmem.ready.poke(true.B)
          dut.io.dmem.fault.poke(false.B)
          dut.io.dmem.rdata.poke(0.U)
          if (dut.io.dmem.valid.peek().litToBoolean) physicalRequests += 1

          dut.clock.step()
          cycles += 1

          if (dut.io.commit.valid.peek().litToBoolean &&
              dut.io.commit.pc.peek().litValue == expectedPc) {
            dut.io.commit.exception.expect(true.B)
            dut.io.commit.exceptionCause.expect(expectedCause.U)
            dut.io.commit.exceptionValue.expect(va.U)
            dut.io.commit.memValid.expect(false.B)
            sawFault = true
          }
        }

        sawFault shouldBe true
        physicalRequests shouldBe 0
      }
    }

    runFault(
      loadProgram,
      supervisorEntry + 0x08,
      MachineExceptionCode.LoadPageFault,
      pte(leafPpn, execute = true, accessed = true)
    )
    runFault(
      storeProgram,
      supervisorEntry + 0x0c,
      MachineExceptionCode.StorePageFault,
      pte(leafPpn, read = true, accessed = true, dirty = true)
    )
  }

  it should "report an implicit PTE read failure as a Load access fault at the original VA" in {
    val program = machineSetup(Map(
      supervisorEntry -> uType(0x40403, 5),
      (supervisorEntry + 0x04) -> iType(0x24, 5, 0, 5, 0x13),
      (supervisorEntry + 0x08) -> iType(0, 5, 2, 6, 0x03)
    ))

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initialize(dut)
      var cycles = 0
      var sawFault = false
      var physicalRequests = 0

      while (!sawFault && cycles < 240) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.ptw.get.fault.poke(true.B)
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.io.dmem.ready.poke(true.B)
        dut.io.dmem.fault.poke(false.B)
        dut.io.dmem.rdata.poke(0.U)
        if (dut.io.dmem.valid.peek().litToBoolean) physicalRequests += 1

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

      sawFault shouldBe true
      physicalRequests shouldBe 0
    }
  }
}
