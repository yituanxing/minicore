package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.{CoreConfig, CoreProfiles}
import aethercore.core.{AetherCore, MachineCsrAddress, MachineCsrBit, MachineCsrFile, SupervisorCsrAddress}

class SupervisorExternalInterruptSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Supervisor external interrupts"

  private val seip = BigInt(1) << MachineCsrBit.SupervisorExternalInterrupt
  private val supervisorProfiles = Seq(
    CoreProfiles.rv32imsuSoftware,
    CoreProfiles.rv64imsuSoftware
  )

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.supervisorExternalInterruptPending.get.poke(false.B)
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
    dut.io.trapReturnSupervisor.poke(false.B)
  }

  private def read(dut: MachineCsrFile, address: Int): BigInt = {
    dut.io.readAddr.poke(address.U)
    dut.io.readData.peek().litValue
  }

  private def write(dut: MachineCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  private def enterSupervisor(dut: MachineCsrFile, supervisorInterruptEnable: Boolean): Unit = {
    val mppSupervisor = BigInt(PrivilegeMode.Supervisor) << MachineCsrBit.MstatusMppLow
    val sie = if (supervisorInterruptEnable) BigInt(1) << MachineCsrBit.SstatusSie else BigInt(0)
    write(dut, MachineCsrAddress.Mstatus, mppSupervisor | sie)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
    dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
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

  private def auipc(imm20: Int, rd: Int): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x17)

  private def csr(address: Int, source: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  it should "expose SEIP through mip/sip and qualify a delegated S-mode external interrupt for both XLENs" in {
    supervisorProfiles.foreach { profile =>
      simulate(
        new MachineCsrFile(
          profile.isa,
          false,
          true
        )
      ) { dut =>
        initialize(dut)

        write(dut, MachineCsrAddress.Mideleg, seip)
        read(dut, MachineCsrAddress.Mideleg) shouldBe seip
        write(dut, SupervisorCsrAddress.Sie, seip)
        read(dut, SupervisorCsrAddress.Sie) shouldBe seip

        dut.io.supervisorExternalInterruptPending.get.poke(true.B)
        read(dut, MachineCsrAddress.Mip) shouldBe seip
        read(dut, SupervisorCsrAddress.Sip) shouldBe seip
        dut.io.supervisorExternalInterrupt.get.expect(false.B)

        enterSupervisor(dut, supervisorInterruptEnable = true)
        dut.io.supervisorExternalInterrupt.get.expect(true.B)

        write(dut, SupervisorCsrAddress.Sstatus, 0)
        dut.io.supervisorExternalInterrupt.get.expect(false.B)
      }
    }
  }

  it should "require mideleg.SEIP before delivering the external interrupt to S-mode for both XLENs" in {
    supervisorProfiles.foreach { profile =>
      simulate(
        new MachineCsrFile(
          profile.isa,
          false,
          true
        )
      ) { dut =>
        initialize(dut)
        write(dut, MachineCsrAddress.Mie, seip)
        enterSupervisor(dut, supervisorInterruptEnable = true)
        dut.io.supervisorExternalInterruptPending.get.poke(true.B)

        read(dut, MachineCsrAddress.Mip) shouldBe seip
        read(dut, SupervisorCsrAddress.Sip) shouldBe 0
        dut.io.supervisorExternalInterrupt.get.expect(false.B)

        write(dut, MachineCsrAddress.Mideleg, seip)
        read(dut, SupervisorCsrAddress.Sip) shouldBe seip
        dut.io.supervisorExternalInterrupt.get.expect(true.B)
      }
    }
  }

  private def proveCoreDelivery(profile: CoreConfig): Unit = {
    val base = profile.platform.resetVector
    val handler = base + 0x100
    val supervisorEntry = base + 0x38
    val expectedCause =
      (BigInt(1) << (profile.isa.xlen - 1)) | BigInt(MachineCsrBit.SupervisorExternalInterrupt)

    val program = Map(
      // AUIPC keeps the workload XLEN-neutral: RV64 LUI 0x80000 would sign-extend.
      base -> auipc(0, 1),
      (base + 0x04) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 0x08) -> csr(0x105, 1, 1, 0),
      // mideleg.SEIP = 1, then sie.SEIE = 1.
      (base + 0x0c) -> iType(0x200, 0, 0, 2, 0x13),
      (base + 0x10) -> csr(0x303, 2, 1, 0),
      (base + 0x14) -> csr(0x104, 2, 1, 0),
      // mepc = supervisorEntry using a second PC-relative address.
      (base + 0x18) -> auipc(0, 3),
      (base + 0x1c) -> iType(0x20, 3, 0, 3, 0x13),
      (base + 0x20) -> csr(0x341, 3, 1, 0),
      // mstatus.MPP=S plus sstatus.SIE, then MRET.
      (base + 0x24) -> uType(0x1, 4),
      (base + 0x28) -> iType(-0x7fe, 4, 0, 4, 0x13),
      (base + 0x2c) -> csr(0x300, 4, 1, 0),
      (base + 0x30) -> BigInt("30200073", 16),
      (base + 0x34) -> BigInt("00000013", 16),
      // Supervisor loop.
      supervisorEntry -> iType(1, 5, 0, 5, 0x13),
      (supervisorEntry + 0x04) -> BigInt("0000006f", 16),
      // S-mode handler records scause/sepc and returns.
      handler -> csr(0x142, 0, 2, 6),
      (handler + 0x04) -> csr(0x141, 0, 2, 7),
      (handler + 0x08) -> BigInt("10200073", 16)
    )

    simulate(new AetherCore(
      profile,
      withSupervisorExternalInterrupt = true
    )) { dut =>
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)
      dut.io.supervisorExternalInterrupt.get.poke(false.B)

      var injected = false
      var sawInterrupt = false
      var sawCause = false
      var sawEpc = false
      var sawSret = false
      var resumed = false
      var expectedEpc = BigInt(0)
      var cycles = 0

      while ((!resumed || !sawCause || !sawEpc) && cycles < 320) {
        val fetchPc = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchPc, BigInt("00000013", 16)).U)

        if (!injected && dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == supervisorEntry) {
          dut.io.supervisorExternalInterrupt.get.poke(true.B)
          injected = true
        }

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.interrupt.peek().litToBoolean) {
            dut.io.commit.interruptCause.expect(expectedCause.U)
            expectedEpc = dut.io.commit.interruptPc.peek().litValue
            sawInterrupt = true
          }

          val instruction = dut.io.commit.inst.peek().litValue
          if (instruction == BigInt("10200073", 16)) sawSret = true

          if (dut.io.commit.rdWrite.peek().litToBoolean) {
            val rd = dut.io.commit.rd.peek().litValue
            val value = dut.io.commit.rdData.peek().litValue
            if (rd == 6 && value == expectedCause) sawCause = true
            if (rd == 7 && expectedEpc != 0 && value == expectedEpc) sawEpc = true
          }

          if (sawSret && expectedEpc != 0 && dut.io.commit.pc.peek().litValue == expectedEpc) {
            resumed = true
          }
        }

        dut.clock.step()
        cycles += 1
        if (injected) dut.io.supervisorExternalInterrupt.get.poke(false.B)
      }

      injected shouldBe true
      sawInterrupt shouldBe true
      sawCause shouldBe true
      sawEpc shouldBe true
      sawSret shouldBe true
      resumed shouldBe true
    }
  }

  it should "deliver XLEN-wide cause 9 through the core, record scause/sepc and return with SRET" in {
    supervisorProfiles.foreach(proveCoreDelivery)
  }
}
