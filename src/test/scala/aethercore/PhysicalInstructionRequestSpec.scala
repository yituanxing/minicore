package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.{AetherCore, PmpCsrAddress}
import aethercore.sim.AetherCoreSimTop

class PhysicalInstructionRequestSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore physical instruction request qualification"

  private val base = BigInt("80000000", 16)
  private val userText = BigInt("80001000", 16)
  private val userData = BigInt("80002000", 16)
  private val userLimit = BigInt("80003000", 16)
  private val trapHandler = base + 0x200
  private val supervisorEntry = base + 0x40
  private val rootPpn = BigInt("20000", 16)
  private val codeVpn1 = (supervisorEntry >> 22) & 0x3ff
  private val codeRootPteAddress = (rootPpn << 12) + (codeVpn1 << 2)
  private val translatedCodePpn = BigInt("100000", 16)
  private val translatedMegapageBase = BigInt("100000000", 16)
  private val translatedSupervisorEntry = translatedMegapageBase + (supervisorEntry & 0x3fffff)

  private val mstatus = 0x300
  private val mtvec = 0x305
  private val mepc = 0x341
  private val pmpcfg0 = 0x3a0
  private val pmpaddr0 = PmpCsrAddress.pmpaddr(0)

  private val ebreak = BigInt("00100073", 16)
  private val mret = BigInt("30200073", 16)
  private val nop = BigInt("00000013", 16)

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

  private def pmpBootstrap(userWords: Seq[BigInt]): Map[BigInt, BigInt] = {
    val kernel = mutable.ArrayBuffer.empty[BigInt]
    def emit(words: Seq[BigInt]): Unit = kernel ++= words

    emit(li(5, trapHandler))
    kernel += csr(mtvec, 5)
    emit(li(5, userText >> 2))
    kernel += csr(pmpaddr0 + 0, 5)
    emit(li(5, userData >> 2))
    kernel += csr(pmpaddr0 + 1, 5)
    emit(li(5, userLimit >> 2))
    kernel += csr(pmpaddr0 + 2, 5)
    emit(li(5, BigInt("000b0d08", 16)))
    kernel += csr(pmpcfg0, 5)
    emit(li(5, userText))
    kernel += csr(mepc, 5)
    kernel += csr(mstatus, 0)
    kernel += mret

    place(base, kernel.toSeq) ++ place(userText, userWords) ++ Map(trapHandler -> ebreak)
  }

  private def sv32Program(supervisorBody: Map[BigInt, BigInt]): Map[BigInt, BigInt] = Map(
    base -> uType(0x80020, 1),
    (base + 0x04) -> csr(0x180, 1),
    (base + 0x08) -> uType(0x80000, 2),
    (base + 0x0c) -> iType(0x40, 2, 0, 2, 0x13),
    (base + 0x10) -> csr(0x341, 2),
    (base + 0x14) -> uType(0x1, 3),
    (base + 0x18) -> iType(-2048, 3, 0, 3, 0x13),
    (base + 0x1c) -> csr(0x300, 3),
    (base + 0x20) -> mret
  ) ++ supervisorBody

  private def virtualPcForPhysical(pa: BigInt): BigInt = {
    if (pa >= translatedMegapageBase && pa < translatedMegapageBase + 0x400000)
      base + (pa - translatedMegapageBase)
    else pa
  }

  private def initializeCore(dut: AetherCore): Unit = {
    dut.io.imem.inst.poke(nop.U)
    dut.io.imem.fault.poke(false.B)
    dut.io.dmem.ready.poke(true.B)
    dut.io.dmem.rdata.poke(0.U)
    dut.io.dmem.fault.poke(false.B)
    dut.io.timerInterrupt.poke(false.B)
  }

  private def initializeSv32(dut: AetherCore): Unit = {
    initializeCore(dut)
    dut.io.ptw.get.ready.poke(false.B)
    dut.io.ptw.get.rdata.poke(0.U)
    dut.io.ptw.get.fault.poke(false.B)
  }

  private def initializeSimTop(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
  }

  it should "qualify an ordinary bare physical fetch" in {
    simulate(new AetherCore(CoreProfiles.rv32imSoftware)) { dut =>
      initializeCore(dut)
      dut.io.imem.valid.expect(true.B)
      dut.io.imem.addr.expect(base.U)
    }
  }

  it should "suppress the physical instruction request on a bare PMP deny" in {
    val program = pmpBootstrap(
      li(5, base) ++ Seq(
        iType(0, 5, 0, 0, 0x67),
        ebreak
      )
    )

    simulate(new AetherCoreSimTop(CoreProfiles.rv32imuPmpSoftware, stopOnTrap = false)) { dut =>
      initializeSimTop(dut)
      var sawUserFetch = false
      var sawDeniedAddress = false
      var sawDeniedValid = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 320) {
        val fetchAddress = dut.io.imemAddr.peek().litValue
        val fetchValid = dut.io.imemValid.peek().litToBoolean
        if (fetchValid && fetchAddress >= userText && fetchAddress < userLimit) {
          sawUserFetch = true
        }
        if (sawUserFetch && fetchAddress == base) {
          sawDeniedAddress = true
          sawDeniedValid ||= fetchValid
        }
        dut.io.imemInst.poke(program.getOrElse(fetchAddress, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(base.U)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAccessFault.U)
          dut.io.commit.exceptionValue.expect(base.U)
          sawFault = true
        }
      }

      sawUserFetch shouldBe true
      sawDeniedAddress shouldBe true
      sawDeniedValid shouldBe false
      sawFault shouldBe true
    }
  }

  it should "withhold a final request during the Sv32 walk and issue it only at the translated PA" in {
    val body = Map(
      supervisorEntry -> iType(77, 0, 0, 5, 0x13),
      (supervisorEntry + 4) -> iType(1, 5, 0, 6, 0x13),
      (supervisorEntry + 8) -> ebreak
    )
    val image = sv32Program(body)
    val codeLeaf = pte(translatedCodePpn, execute = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initializeSv32(dut)
      var sawWalkWithoutFinalRequest = false
      var sawTranslatedRequest = false
      var sawSupervisorCommit = false
      var cycles = 0

      while (!sawSupervisorCommit && cycles < 320) {
        val fetchValid = dut.io.imem.valid.peek().litToBoolean
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(
          (if (fetchValid) image.getOrElse(virtualPcForPhysical(fetchPa), nop) else BigInt(0)).U
        )

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.imem.valid.expect(false.B)
          sawWalkWithoutFinalRequest = true
          dut.io.ptw.get.addr.expect(codeRootPteAddress.U)
          dut.io.ptw.get.rdata.poke(codeLeaf.U)
          dut.io.ptw.get.ready.poke(true.B)
        }

        if (fetchValid && fetchPa == translatedSupervisorEntry) {
          sawTranslatedRequest = true
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

      sawWalkWithoutFinalRequest shouldBe true
      sawTranslatedRequest shouldBe true
      sawSupervisorCommit shouldBe true
      translatedSupervisorEntry should be > BigInt("ffffffff", 16)
    }
  }

  it should "suppress the final request for an Sv32 instruction page fault" in {
    val image = sv32Program(Map.empty)
    val nonExecutableLeaf = pte(translatedCodePpn, read = true, accessed = true)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initializeSv32(dut)
      var expectFaultResponseWithoutRequest = false
      var sawWalk = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 300) {
        if (expectFaultResponseWithoutRequest) {
          dut.io.imem.valid.expect(false.B)
          expectFaultResponseWithoutRequest = false
        }

        val fetchValid = dut.io.imem.valid.peek().litToBoolean
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(
          (if (fetchValid) image.getOrElse(fetchPa, nop) else BigInt(0)).U
        )

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.imem.valid.expect(false.B)
          sawWalk = true
          dut.io.ptw.get.addr.expect(codeRootPteAddress.U)
          dut.io.ptw.get.rdata.poke(nonExecutableLeaf.U)
          dut.io.ptw.get.ready.poke(true.B)
          expectFaultResponseWithoutRequest = true
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

      sawWalk shouldBe true
      sawFault shouldBe true
    }
  }

  it should "suppress the final request for an implicit instruction PTE access fault" in {
    val image = sv32Program(Map.empty)

    simulate(new AetherCore(CoreProfiles.rv32imsuSv32Software)) { dut =>
      initializeSv32(dut)
      var expectFaultResponseWithoutRequest = false
      var sawWalk = false
      var sawFault = false
      var cycles = 0

      while (!sawFault && cycles < 300) {
        if (expectFaultResponseWithoutRequest) {
          dut.io.imem.valid.expect(false.B)
          expectFaultResponseWithoutRequest = false
        }

        val fetchValid = dut.io.imem.valid.peek().litToBoolean
        val fetchPa = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(
          (if (fetchValid) image.getOrElse(fetchPa, nop) else BigInt(0)).U
        )

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          dut.io.imem.valid.expect(false.B)
          sawWalk = true
          dut.io.ptw.get.addr.expect(codeRootPteAddress.U)
          dut.io.ptw.get.fault.poke(true.B)
          dut.io.ptw.get.ready.poke(true.B)
          expectFaultResponseWithoutRequest = true
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

      sawWalk shouldBe true
      sawFault shouldBe true
    }
  }
}
