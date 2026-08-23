package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.config.{CoreConfig, CoreProfiles, PageTableGeometry}
import aethercore.core.v2._

/** R3 observation shell over the real production memory/backend composition. */
private class P82R3ObservableMemoryBackend(
    coreConfig: CoreConfig,
    geometry: PageTableGeometry
) extends TinyMemoryBackend(coreConfig, geometry) {
  private val Xlen = coreConfig.isa.xlen

  val observedSelectiveIssue = IO(Output(new Bundle {
    val fire = Bool()
    val robIndex = UInt(TinyRobGeometry.IndexBits.W)
    val robGeneration = UInt(TinyRobGeometry.GenerationBits.W)
  }))
  observedSelectiveIssue.fire := selectiveIssue.io.request.fire
  observedSelectiveIssue.robIndex := selectiveIssue.io.request.bits.robToken.index
  observedSelectiveIssue.robGeneration := selectiveIssue.io.request.bits.robToken.generation

  val observedExecutionResponse = IO(Output(new Bundle {
    val fire = Bool()
    val robIndex = UInt(TinyRobGeometry.IndexBits.W)
    val robGeneration = UInt(TinyRobGeometry.GenerationBits.W)
  }))
  observedExecutionResponse.fire := execution.io.response.fire
  observedExecutionResponse.robIndex := execution.io.response.bits.robToken.index
  observedExecutionResponse.robGeneration := execution.io.response.bits.robToken.generation

  val observedWindow = IO(Output(Vec(
    TinyRobGeometry.Entries,
    new TinySchedulingEntry(Xlen)
  )))
  observedWindow := dependencyBackend.io.schedulingWindow
}

/** R3 observation shell for the real frontend PC/dispatch owner. */
private class P82R3ObservableBareCore(
    coreConfig: CoreConfig,
    geometry: PageTableGeometry
) extends TinyBareCore(coreConfig, geometry) {
  private val Xlen = coreConfig.isa.xlen

  val observedBranchRedirect = IO(Output(Bool()))
  val observedRedirectTarget = IO(Output(UInt(Xlen.W)))
  val observedDispatchFire = IO(Output(Bool()))
  val observedAllocatedValid = IO(Output(Bool()))
  val observedAllocatedPc = IO(Output(UInt(Xlen.W)))

  observedBranchRedirect := backend.io.branchRedirect.valid
  observedRedirectTarget := backend.io.branchRedirect.bits.target
  observedDispatchFire := backend.io.dispatch.fire
  observedAllocatedValid := backend.io.allocated.valid
  observedAllocatedPc := backend.io.allocated.bits.decoded.pc
}

/** P8.2 R3: redirect ownership plus rejection of already-issued killed work. */
class V2P82RecoveryIntegrationSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private final case class Identity(index: BigInt, generation: BigInt)

  private def initializeMemoryBackend(dut: TinyMemoryBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.time.foreach(_.poke(0.U))
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
    dut.io.pteFault.poke(false.B)
    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(false.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(true.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def pokeDispatch(
      dut: TinyMemoryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      op: AluOp.Type = AluOp.Add,
      rd: Int = 0,
      lhsSource: OperandSourceKind.Type = OperandSourceKind.Zero,
      rhsSource: OperandSourceKind.Type = OperandSourceKind.Immediate,
      immediate: BigInt = 0,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      branchType: BranchType.Type = BranchType.None,
      memoryKind: MemoryOperationKind.Type = MemoryOperationKind.None,
      memorySize: MemSize.Type = MemSize.Word,
      memoryUnsigned: Boolean = false
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke((rd != 0).B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke(0x13.U)
    dut.io.dispatch.bits.decoded.rawInst.poke(0x13.U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(op)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.lhsSource.poke(lhsSource)
    dut.io.dispatch.bits.decoded.rhsSource.poke(rhsSource)
    dut.io.dispatch.bits.decoded.rs1.poke(0.U)
    dut.io.dispatch.bits.decoded.rs2.poke(0.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(false.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(false.B)
    dut.io.dispatch.bits.decoded.writesRd.poke((rd != 0).B)
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(controlFlowKind)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(branchType)
    dut.io.dispatch.bits.decoded.memory.kind.poke(memoryKind)
    dut.io.dispatch.bits.decoded.memory.size.poke(memorySize)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(memoryUnsigned.B)
    dut.io.dispatch.bits.decoded.memory.atomicOp.poke(AtomicOp.None)
    dut.io.dispatch.bits.decoded.memory.acquire.poke(false.B)
    dut.io.dispatch.bits.decoded.memory.release.poke(false.B)
    dut.io.dispatch.bits.decoded.system.kind.poke(SystemOperationKind.None)
    dut.io.dispatch.bits.decoded.system.csrOp.poke(CsrOp.None)
    dut.io.dispatch.bits.decoded.system.csrAddress.poke(0.U)
    dut.io.dispatch.bits.decoded.system.csrUseImmediate.poke(false.B)
    dut.io.dispatch.bits.decoded.system.csrImmediate.poke(0.U)
    dut.io.dispatch.bits.decoded.system.xret.poke(XRetOp.None)
    dut.io.dispatch.bits.decoded.ordering.poke(OrderingClass.Normal)
    dut.io.dispatch.bits.decoded.exception.valid.poke(false.B)
    dut.io.dispatch.bits.decoded.exception.cause.poke(0.U)
    dut.io.dispatch.bits.decoded.exception.value.poke(0.U)
  }

  private def allocate(
      dut: TinyMemoryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      op: AluOp.Type = AluOp.Add,
      rd: Int = 0,
      lhsSource: OperandSourceKind.Type = OperandSourceKind.Zero,
      rhsSource: OperandSourceKind.Type = OperandSourceKind.Immediate,
      immediate: BigInt = 0,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      branchType: BranchType.Type = BranchType.None,
      memoryKind: MemoryOperationKind.Type = MemoryOperationKind.None,
      memorySize: MemSize.Type = MemSize.Word,
      memoryUnsigned: Boolean = false
  ): Identity = {
    pokeDispatch(
      dut,
      pc,
      executionClass,
      op,
      rd,
      lhsSource,
      rhsSource,
      immediate,
      controlFlowKind,
      branchType,
      memoryKind,
      memorySize,
      memoryUnsigned
    )
    var cycles = 0
    while (!dut.io.dispatch.ready.peek().litToBoolean && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    withClue("dispatch never became ready: ") {
      dut.io.dispatch.ready.peek().litToBoolean shouldBe true
    }
    dut.io.allocated.valid.expect(true.B)
    val identity = Identity(
      dut.io.allocated.bits.robToken.index.peek().litValue,
      dut.io.allocated.bits.robToken.generation.peek().litValue
    )
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
    identity
  }

  private def sameIdentity(index: BigInt, generation: BigInt, id: Identity): Boolean =
    index == id.index && generation == id.generation

  private def windowAge(dut: P82R3ObservableMemoryBackend, id: Identity): Option[Int] =
    (0 until TinyRobGeometry.Entries).find { age =>
      dut.observedWindow(age).valid.peek().litToBoolean &&
      sameIdentity(
        dut.observedWindow(age).uop.robToken.index.peek().litValue,
        dut.observedWindow(age).uop.robToken.generation.peek().litValue,
        id
      )
    }

  behavior of "AetherCore v2 P8.2 R3 recovery integration"

  it should "reject a late response from selectively issued younger work after Branch recovery and slot reuse" in {
    val cases = Seq(
      CoreProfiles.rv32imasuSv32PmpSoftware -> PageTableGeometry.Sv32,
      CoreProfiles.rv64imsuSv39PmpSoftware -> PageTableGeometry.Sv39
    )

    for ((coreConfig, geometry) <- cases) {
      simulate(new P82R3ObservableMemoryBackend(coreConfig, geometry)) { dut =>
        initializeMemoryBackend(dut)

        val loadPc = BigInt("80020000", 16)
        val branchPc = loadPc + 4
        val staleDivPc = loadPc + 8
        val target = loadPc + 0x40
        val replacementPc = target

        allocate(
          dut,
          loadPc,
          ExecutionClass.Memory,
          rd = 1,
          immediate = 0x1000,
          memoryKind = MemoryOperationKind.Load,
          memorySize = MemSize.Word,
          memoryUnsigned = true
        )
        val branch = allocate(
          dut,
          branchPc,
          ExecutionClass.Branch,
          lhsSource = OperandSourceKind.Zero,
          rhsSource = OperandSourceKind.Zero,
          immediate = target - branchPc,
          controlFlowKind = ControlFlowKind.Conditional,
          branchType = BranchType.Eq
        )
        val staleDiv = allocate(
          dut,
          staleDivPc,
          ExecutionClass.MulDiv,
          op = AluOp.Divu,
          rd = 2,
          lhsSource = OperandSourceKind.Pc,
          rhsSource = OperandSourceKind.Immediate,
          immediate = 7
        )

        // The head load has transferred ownership into the LSU. Branch may be
        // bypassed by safe compute, so the younger long DIV must really launch
        // before the load is allowed to finish.
        var cycles = 0
        var sawStaleDivIssue = false
        while (!sawStaleDivIssue && cycles < 24) {
          if (dut.observedSelectiveIssue.fire.peek().litToBoolean &&
              sameIdentity(
                dut.observedSelectiveIssue.robIndex.peek().litValue,
                dut.observedSelectiveIssue.robGeneration.peek().litValue,
                staleDiv
              )) {
            sawStaleDivIssue = true
          }
          if (!sawStaleDivIssue) dut.clock.step()
          cycles += 1
        }
        withClue("younger DIV never became genuinely in-flight behind launched Load + older Branch: ") {
          sawStaleDivIssue shouldBe true
        }

        cycles = 0
        while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 24) {
          dut.clock.step()
          cycles += 1
        }
        dut.io.memoryRequest.valid.expect(true.B)
        val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
        dut.io.memoryRequest.ready.poke(true.B)
        dut.clock.step()
        dut.io.memoryRequest.ready.poke(false.B)

        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.bits.txnId.poke(txn.U)
        dut.io.memoryResponse.bits.rdata.poke("h12345678".U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.bits.last.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step()
        dut.io.memoryResponse.valid.poke(false.B)

        // Once the load retires, Branch becomes the conservative head owner.
        // Its taken response must recover while the younger DIV is still live
        // inside the iterative execution unit.
        cycles = 0
        var sawRedirect = false
        while (!sawRedirect && cycles < 24) {
          if (dut.io.branchRedirect.valid.peek().litToBoolean) {
            dut.io.branchRedirect.bits.robToken.index.expect(branch.index.U)
            dut.io.branchRedirect.bits.robToken.generation.expect(branch.generation.U)
            dut.io.branchRedirect.bits.target.expect(target.U)
            dut.io.dispatch.ready.expect(false.B)
            sawRedirect = true
          } else {
            dut.clock.step()
          }
          cycles += 1
        }
        withClue("taken Branch never reached the production recovery seam: ") {
          sawRedirect shouldBe true
        }
        dut.clock.step()

        // Recovery makes the killed physical slot immediately reusable with a
        // fresh generation. Reuse it with another DIV while the old divider
        // request is still finishing; the replacement cannot issue yet because
        // that execution resource is still owned by the killed lifetime.
        val replacement = allocate(
          dut,
          replacementPc,
          ExecutionClass.MulDiv,
          op = AluOp.Divu,
          rd = 3,
          lhsSource = OperandSourceKind.Pc,
          rhsSource = OperandSourceKind.Immediate,
          immediate = 5
        )
        replacement.index shouldBe staleDiv.index
        replacement.generation should not be staleDiv.generation

        val replacementAge = windowAge(dut, replacement)
        withClue("replacement lifetime missing from scheduling window after reuse: ") {
          replacementAge.isDefined shouldBe true
        }
        dut.observedWindow(replacementAge.get).complete.expect(false.B)

        // Wait for the old physical divider response itself, not a synthetic
        // completion injection. The exact stale RobToken must be visible at the
        // execution seam and then be ignored by the ROB after the edge.
        cycles = 0
        var sawLateStaleResponse = false
        while (!sawLateStaleResponse && cycles < 96) {
          if (dut.observedExecutionResponse.fire.peek().litToBoolean &&
              sameIdentity(
                dut.observedExecutionResponse.robIndex.peek().litValue,
                dut.observedExecutionResponse.robGeneration.peek().litValue,
                staleDiv
              )) {
            val age = windowAge(dut, replacement)
            withClue("replacement lifetime disappeared before stale response arrived: ") {
              age.isDefined shouldBe true
            }
            dut.observedWindow(age.get).complete.expect(false.B)
            sawLateStaleResponse = true
          } else {
            dut.clock.step()
          }
          cycles += 1
        }
        withClue("killed younger DIV never produced its expected late physical response: ") {
          sawLateStaleResponse shouldBe true
        }
        dut.clock.step()

        val ageAfterStale = windowAge(dut, replacement)
        withClue("stale response damaged or removed the replacement lifetime: ") {
          ageAfterStale.isDefined shouldBe true
        }
        dut.observedWindow(ageAfterStale.get).complete.expect(false.B)

        // The freed divider may now accept the replacement normally. Prove that
        // it launches under the new generation and eventually commits its own
        // semantic result rather than the stale predecessor's value.
        cycles = 0
        var sawReplacementIssue = false
        while (!sawReplacementIssue && cycles < 24) {
          if (dut.observedSelectiveIssue.fire.peek().litToBoolean &&
              sameIdentity(
                dut.observedSelectiveIssue.robIndex.peek().litValue,
                dut.observedSelectiveIssue.robGeneration.peek().litValue,
                replacement
              )) {
            sawReplacementIssue = true
          }
          if (!sawReplacementIssue) dut.clock.step()
          cycles += 1
        }
        withClue("replacement DIV did not acquire the freed divider resource: ") {
          sawReplacementIssue shouldBe true
        }

        val expected = replacementPc / 5
        cycles = 0
        var sawReplacementCommit = false
        while (!sawReplacementCommit && cycles < 128) {
          if (dut.io.commit.valid.peek().litToBoolean &&
              dut.io.commit.pc.peek().litValue == replacementPc) {
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(3.U)
            dut.io.commit.rdData.expect(expected.U)
            sawReplacementCommit = true
          }
          if (!sawReplacementCommit) dut.clock.step()
          cycles += 1
        }
        withClue("replacement lifetime never reached precise Commit after stale-response rejection: ") {
          sawReplacementCommit shouldBe true
        }
      }
    }
  }

  it should "give Branch redirect priority over same-cycle fall-through frontend dispatch" in {
    val config = CoreProfiles.rv64imsuSv39PmpSoftware
    val reset = config.platform.resetVector
    val target = reset + 0x20

    simulate(new P82R3ObservableBareCore(config, PageTableGeometry.Sv39)) { dut =>
      dut.io.imem.inst.poke("h00000013".U)
      dut.io.imem.fault.poke(false.B)
      dut.io.time.foreach(_.poke(0.U))
      dut.io.ptw.ready.poke(false.B)
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

      val program = Map(
        reset -> BigInt("02000063", 16),       // beq x0,x0,+32
        (reset + 0x04) -> BigInt("00b00093", 16), // wrong path
        (reset + 0x08) -> BigInt("00c00093", 16), // stale fall-through at redirect
        target -> BigInt("00700113", 16)       // target: addi x2,x0,7
      )

      def driveInstruction(): Unit = {
        val pc = dut.io.frontendPc.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(pc, BigInt("00000013", 16)).U)
        dut.io.imem.fault.poke(false.B)
      }

      var cycles = 0
      var sawRedirect = false
      while (!sawRedirect && cycles < 24) {
        driveInstruction()
        if (dut.observedBranchRedirect.peek().litToBoolean) {
          dut.observedRedirectTarget.expect(target.U)
          dut.io.imem.valid.expect(false.B)
          dut.observedDispatchFire.expect(false.B)
          dut.observedAllocatedValid.expect(false.B)
          withClue("redirect should suppress a still-sequential frontend PC, not rediscover an already-target PC: ") {
            dut.io.frontendPc.peek().litValue should not be target
          }
          sawRedirect = true
        } else {
          dut.clock.step()
        }
        cycles += 1
      }
      withClue("real frontend never observed the taken Branch redirect: ") {
        sawRedirect shouldBe true
      }

      dut.clock.step()
      dut.io.frontendPc.expect(target.U)

      // The target may wait briefly for the surviving Branch to retire, but the
      // first post-recovery allocation must come from the redirect target rather
      // than the stale fall-through PC suppressed above.
      cycles = 0
      var sawTargetAllocation = false
      while (!sawTargetAllocation && cycles < 16) {
        driveInstruction()
        if (dut.observedAllocatedValid.peek().litToBoolean) {
          dut.observedAllocatedPc.expect(target.U)
          sawTargetAllocation = true
        } else {
          dut.clock.step()
        }
        cycles += 1
      }
      withClue("redirect target was not the first post-recovery frontend allocation: ") {
        sawTargetAllocation shouldBe true
      }
    }
  }
}
