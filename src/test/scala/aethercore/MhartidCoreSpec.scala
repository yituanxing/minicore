package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}
import aethercore.sim.AetherCoreSimTop

class MhartidCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore mhartid"

  private val base = BigInt("80000000", 16)
  private val userEntry = base + 0x80
  private val trapHandler = base + 0x100

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

  private def csr(address: Int, source: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x73)

  private val ebreak = BigInt("00100073", 16)
  private val mret = BigInt("30200073", 16)

  private def initializeCsrFile(dut: MachineCsrFile): Unit = {
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
  }

  private def initializeCore(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
  }

  it should "expose one read-only Machine hart numbered zero" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initializeCsrFile(dut)
      dut.io.readAddr.poke(MachineCsrAddress.Mhartid.U)
      dut.io.readData.expect(0.U)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)
    }
  }

  it should "retire a Machine-mode read and reject a write without side effects" in {
    val readInstruction = csr(MachineCsrAddress.Mhartid, 0, 2, 5)
    val writeInstruction = csr(MachineCsrAddress.Mhartid, 1, 1, 6)
    val writePc = base + 8
    val program = Map(
      base -> readInstruction,
      (base + 4) -> iType(1, 0, 0, 1, 0x13),
      writePc -> writeInstruction,
      (base + 12) -> ebreak
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imSoftware)) { dut =>
      initializeCore(dut)
      var sawRead = false
      var sawIllegalWrite = false
      var cycles = 0

      while (!sawIllegalWrite && cycles < 120) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == base && dut.io.commit.rdWrite.peek().litToBoolean) {
            dut.io.commit.rd.expect(5.U)
            dut.io.commit.rdData.expect(0.U)
            dut.io.commit.exception.expect(false.B)
            sawRead = true
          }
          if (pc == writePc && dut.io.commit.exception.peek().litToBoolean) {
            dut.io.commit.inst.expect(writeInstruction.U)
            dut.io.commit.rdWrite.expect(false.B)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
            dut.io.commit.exceptionValue.expect(writeInstruction.U)
            sawIllegalWrite = true
          }
        }
      }

      sawRead shouldBe true
      sawIllegalWrite shouldBe true
    }
  }

  it should "reject a User-mode read of the Machine-level hart ID" in {
    val instruction = csr(MachineCsrAddress.Mhartid, 0, 2, 3)
    val program = Map(
      base -> uType(0x80000, 1),
      (base + 4) -> iType(0x100, 1, 0, 1, 0x13),
      (base + 8) -> csr(MachineCsrAddress.Mtvec, 1, 1, 0),
      (base + 12) -> uType(0x80000, 2),
      (base + 16) -> iType(0x80, 2, 0, 2, 0x13),
      (base + 20) -> csr(MachineCsrAddress.Mepc, 2, 1, 0),
      (base + 24) -> csr(MachineCsrAddress.Mstatus, 0, 1, 0),
      (base + 28) -> mret,
      userEntry -> instruction,
      (userEntry + 4) -> ebreak,
      trapHandler -> ebreak
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuSoftware, stopOnTrap = false)) { dut =>
      initializeCore(dut)
      var sawIllegalRead = false
      var cycles = 0

      while (!sawIllegalRead && cycles < 180) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == userEntry) {
            dut.io.commit.inst.expect(instruction.U)
            dut.io.commit.rdWrite.expect(false.B)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
            dut.io.commit.exceptionValue.expect(instruction.U)
            sawIllegalRead = true
          }
        }
      }

      sawIllegalRead shouldBe true
    }
  }
}
