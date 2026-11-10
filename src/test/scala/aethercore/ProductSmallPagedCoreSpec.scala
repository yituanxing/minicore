package aethercore

import aethercore.common.{MachineExceptionCode, PrivilegeMode}
import aethercore.core.MachineCsrWarl
import aethercore.core.product._
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Real instruction translation/fetch -> semantic decode -> Product ROB and
  * single architectural CSR owner. The test supplies only physical bus data.
  */
class ProductSmallPagedCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product SmallOoO paged frontend and privileged retirement"

  private val profile = ProductCoreProfiles.smallEngineering
  private val reset = profile.platform.resetVector
  private val nop = BigInt("00000013", 16)

  private def addi(rd: Int, rs1: Int, immediate: Int): BigInt =
    (BigInt(immediate & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(rd) << 7) | 0x13

  private def slli(rd: Int, rs1: Int, shift: Int): BigInt =
    (BigInt(shift) << 20) | (BigInt(rs1) << 15) |
      (BigInt(1) << 12) | (BigInt(rd) << 7) | 0x13

  private def csrrw(rd: Int, csr: Int, rs1: Int): BigInt =
    (BigInt(csr) << 20) | (BigInt(rs1) << 15) |
      (BigInt(1) << 12) | (BigInt(rd) << 7) | 0x73

  private def init(d: ProductSmallPagedCore): Unit = {
    d.io.imem.inst.poke(nop.U)
    d.io.imem.fault.poke(false.B)
    d.io.ptw.ready.poke(false.B)
    d.io.ptw.rdata.poke(0.U)
    d.io.ptw.fault.poke(false.B)
    d.io.resolvedAttributes.cacheable.poke(true.B)
    d.io.resolvedAttributes.idempotent.poke(true.B)
    d.io.resolvedAttributes.sideEffecting.poke(false.B)
    d.io.resolvedAttributes.ordered.poke(false.B)
    d.io.resolvedAttributes.executable.poke(true.B)
    d.io.resolvedAttributes.supportsAtomic.poke(true.B)
    d.io.resolvedAttributes.supportsPartial.poke(true.B)
    d.io.memoryRequest.ready.poke(true.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    if (d.config.software.isa.hasTimeCounter) d.io.time.get.poke(0.U)
    d.io.timerInterrupt.poke(false.B)
    if (d.withMachineExternalInterrupt) d.io.machineExternalInterrupt.get.poke(false.B)
    if (d.withSupervisorExternalInterrupt) d.io.supervisorExternalInterrupt.get.poke(false.B)
    if (d.enableInstructionBackpressure) d.io.imemReady.get.poke(true.B)
    d.clock.step()
  }

  // InstructionBusIO is combinational; driving its response for the observed
  // physical address before the clock edge creates a zero-wait ROM without an
  // extra imitation fetch pipeline in the test harness.
  private def driveInstruction(d: ProductSmallPagedCore,
                               words: Map[BigInt, BigInt],
                               faultAddrs: Set[BigInt] = Set.empty): Unit = {
    val address = d.io.imem.addr.peek().litValue
    d.io.imem.inst.poke(words.getOrElse(address, nop).U)
    d.io.imem.fault.poke(faultAddrs.contains(address).B)
  }

  it should "fetch and retire CSR instructions against the single mutable MachineCsrFile" in {
    simulate(new ProductSmallPagedCore(profile)) { d =>
      init(d)
      val words = Map(
        reset -> BigInt("00800093", 16),       // addi x1,x0,8
        (reset + 4) -> BigInt("3000a073", 16), // csrrs x0,mstatus,x1
        (reset + 8) -> BigInt("30002173", 16), // csrrs x2,mstatus,x0
        (reset + 12) -> BigInt("00110193", 16) // addi x3,x2,1
      )
      val expected = MachineCsrWarl.mstatusXlenValue(profile.software.isa) | BigInt(8)
      var sawRead = false
      var sawDependent = false
      for (_ <- 0 until 130) {
        driveInstruction(d, words)
        if (d.io.retire.valid.peek().litToBoolean) {
          val pc = d.io.retire.bits.pc.peek().litValue
          if (pc == reset + 8) {
            d.io.retire.bits.rd.expect(2.U)
            d.io.retire.bits.value.expect(expected.U)
            d.io.retire.bits.exception.valid.expect(false.B)
            sawRead = true
          }
          if (pc == reset + 12) {
            d.io.retire.bits.rd.expect(3.U)
            d.io.retire.bits.value.expect((expected + 1).U)
            sawDependent = true
          }
        }
        d.clock.step()
      }
      sawRead shouldBe true
      sawDependent shouldBe true
      d.io.currentPrivilege.expect(PrivilegeMode.Machine.U)
    }
  }

  it should "route synchronous ECALL to mtvec and expose mepc through an actual fetched CSR read" in {
    simulate(new ProductSmallPagedCore(profile)) { d =>
      init(d)
      val words = Map(
        reset -> BigInt("00000073", 16),       // ecall from M
        BigInt(0) -> BigInt("34102273", 16),   // csrrs x4,mepc,x0
        BigInt(4) -> BigInt("342022f3", 16)    // csrrs x5,mcause,x0
      )
      var sawTrap = false
      var sawMepc = false
      var sawMcause = false
      for (_ <- 0 until 130) {
        driveInstruction(d, words)
        if (d.io.retire.valid.peek().litToBoolean) {
          val pc = d.io.retire.bits.pc.peek().litValue
          if (pc == reset) {
            d.io.retire.bits.exception.valid.expect(true.B)
            d.io.retire.bits.exception.cause.expect(
              MachineExceptionCode.EnvironmentCallFromM.U)
            sawTrap = true
          }
          if (pc == 0) {
            d.io.retire.bits.value.expect(reset.U)
            sawMepc = true
          }
          if (pc == 4) {
            d.io.retire.bits.value.expect(
              MachineExceptionCode.EnvironmentCallFromM.U)
            sawMcause = true
          }
        }
        d.clock.step()
      }
      sawTrap shouldBe true
      sawMepc shouldBe true
      sawMcause shouldBe true
    }
  }

  it should "wait for a physical Load and the clean ROB boundary before taking timer IRQ" in {
    simulate(new ProductSmallPagedCore(profile)) { d =>
      init(d)
      val words = Map(
        reset -> BigInt("00800093", 16),        // addi x1,x0,8 (MIE)
        (reset + 4) -> BigInt("3000a073", 16),  // csrrs x0,mstatus,x1
        (reset + 8) -> BigInt("08000113", 16),  // addi x2,x0,128 (MTIE)
        (reset + 12) -> BigInt("30412073", 16), // csrrs x0,mie,x2
        (reset + 16) -> BigInt("00002183", 16)  // lw x3,0(x0)
      )
      var txn = BigInt(-1)
      var requestCycles = 0
      var responseSent = false
      var sawLoad = false
      var sawInterrupt = false
      var blockedWithLoad = false
      for (_ <- 0 until 240) {
        driveInstruction(d, words)
        if (txn == -1 && d.io.memoryRequest.valid.peek().litToBoolean) {
          txn = d.io.memoryRequest.bits.txnId.peek().litValue
          d.io.timerInterrupt.poke(true.B)
        }
        if (txn != -1 && !sawLoad) {
          d.io.interruptTaken.expect(false.B)
          if (d.io.occupancy.peek().litValue > 0) {
            blockedWithLoad = true
          }
        }
        if (d.io.retire.valid.peek().litToBoolean &&
            d.io.retire.bits.pc.peek().litValue == reset + 16) {
          d.io.retire.bits.value.expect(77.U)
          sawLoad = true
        }
        if (d.io.interruptTaken.peek().litToBoolean) {
          d.io.occupancy.expect(0.U)
          d.io.resolverPending.expect(0.U)
          d.io.outstandingTransactions.expect(0.U)
          sawInterrupt = true
        }
        if (txn != -1 && !responseSent && requestCycles >= 3) {
          // One accepted bus request is deliberately held for several clocks.
          // Respond only after the interrupt has been visibly deferred.
          blockedWithLoad shouldBe true
          d.io.memoryResponse.bits.txnId.poke(txn.U)
          d.io.memoryResponse.bits.rdata.poke(77.U)
          d.io.memoryResponse.bits.fault.poke(false.B)
          d.io.memoryResponse.bits.last.poke(true.B)
          d.io.memoryResponse.valid.poke(true.B)
          d.io.memoryResponse.ready.expect(true.B)
          responseSent = true
        }
        d.clock.step()
        if (responseSent)
          d.io.memoryResponse.valid.poke(false.B)
        if (txn != -1) requestCycles += 1
        if (sawInterrupt) d.io.timerInterrupt.poke(false.B)
      }
      (txn >= 0) shouldBe true
      blockedWithLoad shouldBe true
      sawLoad shouldBe true
      sawInterrupt shouldBe true
    }
  }

  it should "enter Supervisor mode and fetch its first instruction through a real Sv39 PTW" in {
    simulate(new ProductSmallPagedCore(profile)) { d =>
      init(d)
      val boot = Seq(
        addi(3, 0, -1),        // pmpaddr0 = full physical range NAPOT
        csrrw(0, 0x3b0, 3),
        addi(4, 0, 31),        // pmpcfg0 = R/W/X + NAPOT
        csrrw(0, 0x3a0, 4),
        addi(1, 0, 8),
        slli(1, 1, 60),        // satp.MODE = Sv39
        addi(1, 1, 1),        // satp root PPN = 1
        csrrw(0, 0x180, 1),
        addi(2, 0, 1),
        slli(2, 2, 11),        // mstatus.MPP = S
        csrrw(0, 0x300, 2),
        csrrw(0, 0x341, 0),   // mepc = 0
        BigInt("30200073", 16) // mret to virtual PC 0
      )
      val bootWords = boot.zipWithIndex.map { case (word, index) =>
        (reset + index * 4) -> word
      }.toMap
      val targetPa = BigInt("80001000", 16)
      val words = bootWords + (targetPa -> addi(5, 0, 42))
      val pte = Map(
        BigInt("1000", 16) -> ((BigInt(2) << 10) | 1),
        BigInt("2000", 16) -> ((BigInt(3) << 10) | 1),
        BigInt("3000", 16) -> (((targetPa >> 12) << 10) | BigInt("4b", 16))
      )
      var seenPte = Set.empty[BigInt]
      var supervisorResult = false
      for (_ <- 0 until 410 if !supervisorResult) {
        driveInstruction(d, words)
        val pteValid = d.io.ptw.valid.peek().litToBoolean
        if (pteValid) {
          val addr = d.io.ptw.addr.peek().litValue
          pte.contains(addr) shouldBe true
          seenPte += addr
          d.io.ptw.rdata.poke(pte(addr).U)
          d.io.ptw.ready.poke(true.B)
        } else {
          d.io.ptw.ready.poke(false.B)
        }
        if (d.io.retire.valid.peek().litToBoolean &&
            d.io.retire.bits.pc.peek().litValue == 0) {
          d.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
          d.io.retire.bits.rd.expect(5.U)
          d.io.retire.bits.value.expect(42.U)
          d.io.retire.bits.exception.valid.expect(false.B)
          supervisorResult = true
        }
        d.clock.step()
      }
      seenPte shouldBe pte.keySet
      supervisorResult shouldBe true
    }
  }

  it should "flush at retired SFENCE/FENCE.I and wake WFI on raw but unqualified pending" in {
    simulate(new ProductSmallPagedCore(profile)) { d =>
      init(d)
      val words = Map(
        reset -> BigInt("12000073", 16),        // sfence.vma x0,x0
        (reset + 4) -> BigInt("0000100f", 16),  // fence.i
        (reset + 8) -> BigInt("10500073", 16),  // wfi
        (reset + 12) -> BigInt("00900313", 16) // addi x6,x0,9
      )
      var sawSfence = false
      var sawFenceI = false
      var sawWfi = false
      var sleepingCycles = 0
      var rawWake = false
      var sawResumed = false
      for (_ <- 0 until 180) {
        driveInstruction(d, words)
        if (d.io.retire.valid.peek().litToBoolean) {
          val retiringPc = d.io.retire.bits.pc.peek().litValue
          if (retiringPc == reset) {
            d.io.translationFence.expect(true.B)
            sawSfence = true
          }
          if (retiringPc == reset + 4) {
            d.io.instructionFence.expect(true.B)
            sawFenceI = true
          }
          if (retiringPc == reset + 8) {
            d.io.retire.bits.exception.valid.expect(false.B)
            sawWfi = true
          }
          if (retiringPc == reset + 12) {
            d.io.retire.bits.rd.expect(6.U)
            d.io.retire.bits.value.expect(9.U)
            sawResumed = true
          }
        }
        if (d.io.halted.peek().litToBoolean) {
          sleepingCycles += 1
          d.io.interruptTaken.expect(false.B)
          if (sleepingCycles >= 4 && !rawWake) {
            d.io.timerInterrupt.poke(true.B) // MIE/MTIE disabled: wake, no trap
            rawWake = true
          }
        }
        d.clock.step()
      }
      sawSfence shouldBe true
      sawFenceI shouldBe true
      sawWfi shouldBe true
      (sleepingCycles >= 4) shouldBe true
      rawWake shouldBe true
      sawResumed shouldBe true
    }
  }

  it should "preserve PC+2 for a second C parcel access fault across a page boundary" in {
    val boundaryPc = BigInt("80000ffe", 16)
    val cProfile = ProductCoreProfiles.smallCompressedEngineering.copy(
      platform = ProductCoreProfiles.smallCompressedEngineering.platform.copy(resetVector = boundaryPc))
    simulate(new ProductSmallPagedCore(cProfile)) { d =>
      init(d)
      var sawSecondParcel = false
      var sawFault = false
      for (_ <- 0 until 120) {
        val address = d.io.imem.addr.peek().litValue
        if (address == boundaryPc) {
          // Low parcel of a 32-bit instruction, so RVC must ask for PC+2.
          d.io.imem.inst.poke(3.U)
          d.io.imem.fault.poke(false.B)
        } else if (address == boundaryPc + 2) {
          sawSecondParcel = true
          d.io.imem.inst.poke(0.U)
          d.io.imem.fault.poke(true.B)
        } else {
          d.io.imem.inst.poke(nop.U)
          d.io.imem.fault.poke(false.B)
        }
        if (d.io.retire.valid.peek().litToBoolean &&
            d.io.retire.bits.pc.peek().litValue == boundaryPc) {
          d.io.retire.bits.exception.valid.expect(true.B)
          d.io.retire.bits.exception.cause.expect(MachineExceptionCode.InstructionAccessFault.U)
          d.io.retire.bits.exception.value.expect((boundaryPc + 2).U)
          sawFault = true
        }
        d.clock.step()
      }
      sawSecondParcel shouldBe true
      sawFault shouldBe true
    }
  }
}
