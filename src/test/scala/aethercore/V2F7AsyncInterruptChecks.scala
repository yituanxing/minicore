package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore

/**
  * F7 clean-boundary asynchronous interrupt and WFI closure.
  *
  * These checks deliberately use real RV32 instruction encodings through the
  * paged frontend/semantic bridge rather than constructing RobDispatch records.
  * Machine mode bypasses translation, which keeps the proof focused on the
  * interrupt boundary while still exercising PC -> decode -> ROB -> Commit.
  */
trait V2F7AsyncInterruptChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val Config = CoreProfiles.rv32imasuSv32PmpSoftware
  private val Geometry = PageTableGeometry.Sv32
  private val Reset = Config.platform.resetVector
  private val TrapVector = BigInt("00000100", 16)
  private val MachineTimerCause = BigInt("80000007", 16)
  private val Nop = BigInt("00000013", 16)
  private val Wfi = BigInt("10500073", 16)

  private def initialize(dut: TinyPagedCore): Unit = {
    dut.io.imem.inst.poke(Nop.U)
    dut.io.imem.fault.poke(false.B)
    dut.io.time.foreach(_.poke(0.U))
    dut.io.timerInterrupt.foreach(_.poke(false.B))
    dut.io.machineExternalInterrupt.foreach(_.poke(false.B))
    dut.io.supervisorExternalInterrupt.foreach(_.poke(false.B))

    dut.io.ptw.ready.poke(true.B)
    dut.io.ptw.rdata.poke(0.U)
    dut.io.ptw.fault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(false.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(false.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def driveExternal(
      dut: TinyPagedCore,
      instructions: Map[BigInt, BigInt],
      seenImem: mutable.ArrayBuffer[BigInt]
  ): Unit = {
    val address = dut.io.imem.addr.peek().litValue
    dut.io.imem.inst.poke(instructions.getOrElse(address, Nop).U)
    dut.io.imem.fault.poke(false.B)
    if (dut.io.imem.valid.peek().litToBoolean) {
      seenImem += address
    }

    // All focused async programs stay in M-mode. Any PTW activity here would be
    // an architectural regression, but keep the external port responsive so a
    // failure cannot masquerade as a testbench deadlock.
    dut.io.ptw.ready.poke(true.B)
    dut.io.ptw.rdata.poke(0.U)
    dut.io.ptw.fault.poke(false.B)
  }

  /** Configure mtvec=0x100, mie.MTIE=1 and mstatus.MIE=1 using real CSR writes. */
  private def machineTimerSetup: Seq[BigInt] = Seq(
    BigInt("10000093", 16), // addi x1,x0,0x100
    BigInt("30509073", 16), // csrw mtvec,x1
    BigInt("08000093", 16), // addi x1,x0,0x080
    BigInt("30409073", 16), // csrw mie,x1 (MTIE)
    BigInt("00800093", 16), // addi x1,x0,0x008
    BigInt("30009073", 16)  // csrw mstatus,x1 (MIE)
  )

  private def programAtReset(words: Seq[BigInt]): Map[BigInt, BigInt] =
    words.zipWithIndex.map { case (inst, index) => (Reset + index * 4) -> inst }.toMap

  behavior of "AetherCore v2 F7 clean-boundary async owner"

  it should "hold dispatch on qualified MTIP, drain an occupied ROB, then trap at the exact next-PC boundary" in {
    simulate(new TinyPagedCore(
      Config,
      Geometry,
      enableAsyncInterrupts = true
    )) { dut =>
      initialize(dut)

      val body = machineTimerSetup ++ Seq(
        BigInt("00700113", 16), // addi x2,x0,7
        BigInt("00300193", 16), // addi x3,x0,3
        BigInt("02314233", 16), // div  x4,x2,x3 -- iterative, keeps ROB occupied
        BigInt("00500293", 16), // addi x5,x0,5
        BigInt("00600313", 16), // addi x6,x0,6
        BigInt("00700393", 16), // addi x7,x0,7
        BigInt("00800413", 16)  // addi x8,x0,8
      )
      val instructions = programAtReset(body) +
        (TrapVector -> BigInt("00900493", 16)) // addi x9,x0,9
      val seenImem = mutable.ArrayBuffer.empty[BigInt]

      var cycles = 0
      var timerRaised = false
      var heldPc = BigInt(0)
      var interruptSeen = false
      var handlerCommitted = false
      var maxOccupancy = BigInt(0)

      while (cycles < 1200 && !handlerCommitted) {
        driveExternal(dut, instructions, seenImem)
        val occupancy = dut.io.occupancy.peek().litValue
        val frontendPc = dut.io.frontendPc.peek().litValue
        maxOccupancy = maxOccupancy.max(occupancy)

        // Wait until DIV and at least one younger instruction have entered the
        // machine. This makes the interrupt proof exercise drain, not merely an
        // already-empty boundary.
        if (!timerRaised && frontendPc >= Reset + 44 && occupancy >= 2) {
          heldPc = frontendPc
          dut.io.timerInterrupt.get.poke(true.B)
          timerRaised = true
        }

        if (timerRaised && !interruptSeen) {
          dut.io.interruptHold.expect(true.B)
          dut.io.imem.valid.expect(false.B)
          dut.io.frontendPc.expect(heldPc.U)

          if (dut.io.commit.interrupt.peek().litToBoolean) {
            dut.io.occupancy.expect(0.U)
            dut.io.commit.valid.expect(false.B)
            dut.io.commit.interruptCause.expect(MachineTimerCause.U)
            dut.io.commit.interruptPc.expect(heldPc.U)
            interruptSeen = true
          }
        }

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == TrapVector) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(9.U)
          dut.io.commit.rdData.expect(9.U)
          handlerCommitted = true
        }

        dut.clock.step()
        cycles += 1

        // The interrupt must remain asserted through the edge that performs
        // trapEnter/PC redirect. Clear it only afterwards.
        if (interruptSeen) {
          dut.io.timerInterrupt.get.poke(false.B)
        }
      }

      withClue("test never built a non-empty speculative window: ") {
        maxOccupancy should be >= BigInt(2)
      }
      withClue("qualified MTIP never produced a clean-boundary interrupt: ") {
        interruptSeen shouldBe true
      }
      withClue("trap vector instruction never retired after MTIP entry: ") {
        handlerCommitted shouldBe true
      }
      withClue("trap vector was never physically fetched: ") {
        seenImem should contain (TrapVector)
      }
    }
  }

  it should "retire WFI into sleep, stop fetch, and enter MTIP directly from the empty wake boundary" in {
    simulate(new TinyPagedCore(
      Config,
      Geometry,
      enableAsyncInterrupts = true
    )) { dut =>
      initialize(dut)

      val wfiPc = Reset + machineTimerSetup.size * 4
      val nextPc = wfiPc + 4
      val instructions = programAtReset(machineTimerSetup ++ Seq(
        Wfi,
        BigInt("00a00513", 16) // addi x10,x0,10 -- must not execute before wake/trap
      )) + (TrapVector -> BigInt("00900493", 16))
      val seenImem = mutable.ArrayBuffer.empty[BigInt]

      var cycles = 0
      var wfiRetired = false
      var sleeping = false
      while (cycles < 700 && !sleeping) {
        driveExternal(dut, instructions, seenImem)
        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.pc.peek().litValue == wfiPc) {
          dut.io.commit.exception.expect(false.B)
          wfiRetired = true
        }
        sleeping = dut.io.halted.peek().litToBoolean
        dut.clock.step()
        cycles += 1
      }

      wfiRetired shouldBe true
      withClue("WFI retirement did not put the clean frontend to sleep: ") {
        sleeping shouldBe true
      }
      dut.io.frontendPc.expect(nextPc.U)

      // A sleeping core must not generate speculative instruction traffic.
      for (_ <- 0 until 4) {
        driveExternal(dut, instructions, seenImem)
        dut.io.halted.expect(true.B)
        dut.io.imem.valid.expect(false.B)
        dut.io.commit.interrupt.expect(false.B)
        dut.clock.step()
      }

      dut.io.timerInterrupt.get.poke(true.B)
      driveExternal(dut, instructions, seenImem)
      dut.io.halted.expect(true.B)
      dut.io.interruptHold.expect(true.B)
      dut.io.occupancy.expect(0.U)
      dut.io.commit.valid.expect(false.B)
      dut.io.commit.interrupt.expect(true.B)
      dut.io.commit.interruptCause.expect(MachineTimerCause.U)
      dut.io.commit.interruptPc.expect(nextPc.U)
      dut.clock.step()
      dut.io.timerInterrupt.get.poke(false.B)

      var handlerCommitted = false
      cycles = 0
      while (cycles < 300 && !handlerCommitted) {
        driveExternal(dut, instructions, seenImem)
        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.pc.peek().litValue == TrapVector) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(9.U)
          dut.io.commit.rdData.expect(9.U)
          handlerCommitted = true
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("WFI wake interrupt did not reach the trap vector: ") {
        handlerCommitted shouldBe true
      }
    }
  }

  it should "wake WFI on raw unqualified MTIP without taking a trap" in {
    simulate(new TinyPagedCore(
      Config,
      Geometry,
      enableAsyncInterrupts = true
    )) { dut =>
      initialize(dut)

      val wfiPc = Reset
      val nextPc = Reset + 4
      val instructions = programAtReset(Seq(
        Wfi,
        BigInt("00a00513", 16) // addi x10,x0,10
      ))
      val seenImem = mutable.ArrayBuffer.empty[BigInt]

      var cycles = 0
      while (cycles < 300 && !dut.io.halted.peek().litToBoolean) {
        driveExternal(dut, instructions, seenImem)
        dut.io.commit.interrupt.expect(false.B)
        dut.clock.step()
        cycles += 1
      }
      dut.io.halted.expect(true.B)
      dut.io.frontendPc.expect(nextPc.U)

      // mie.MTIE and mstatus.MIE are both zero. Raw MTIP must wake WFI, but it
      // is not an architecturally qualified interrupt and therefore must not
      // create trap state or redirect the PC.
      dut.io.timerInterrupt.get.poke(true.B)
      driveExternal(dut, instructions, seenImem)
      dut.io.interruptHold.expect(false.B)
      dut.io.commit.interrupt.expect(false.B)
      dut.clock.step()

      var resumedCommit = false
      cycles = 0
      while (cycles < 300 && !resumedCommit) {
        driveExternal(dut, instructions, seenImem)
        dut.io.interruptHold.expect(false.B)
        dut.io.commit.interrupt.expect(false.B)
        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.pc.peek().litValue == nextPc) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(10.U)
          dut.io.commit.rdData.expect(10.U)
          resumedCommit = true
        }
        dut.clock.step()
        cycles += 1
      }
      dut.io.timerInterrupt.get.poke(false.B)

      withClue("raw-but-unqualified MTIP failed to wake WFI into normal execution: ") {
        resumedCommit shouldBe true
      }
    }
  }
}
