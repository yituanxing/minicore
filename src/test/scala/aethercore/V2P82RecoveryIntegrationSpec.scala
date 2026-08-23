package aethercore.core.v2

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.config.{CoreConfig, CoreProfiles, PageTableGeometry}

/** Test-only seam for forcing an already-executed arbitrary-age completion.
  *
  * R4 has not yet enabled arbitrary-age Branch issue in production. R3 still
  * needs an end-to-end proof that TinyMemoryBackend consumes the generalized
  * recovery boundary correctly, so this wrapper injects only the completion
  * that R4 will eventually produce and exposes no new production interface.
  */
private class P82RecoveryObservableMemoryBackend(
    coreConfig: CoreConfig,
    geometry: PageTableGeometry
) extends TinyMemoryBackend(coreConfig, geometry) {
  private val xlen = coreConfig.isa.xlen

  val forcedCompletion = IO(Flipped(Valid(new ExecutionResponse(
    xlen,
    TinyRobGeometry.IndexBits,
    TinyRobGeometry.GenerationBits
  ))))
  val observedRecoveryBusy = IO(Output(Bool()))
  val observedSelectiveFire = IO(Output(Bool()))
  val observedBranchFire = IO(Output(Bool()))
  val observedLsuLaunchFire = IO(Output(Bool()))

  // Parent wiring remains the default path. A forced completion overrides it
  // only for the focused cycle under test.
  when(forcedCompletion.valid) {
    dependencyBackend.io.completion.valid := true.B
    dependencyBackend.io.completion.bits := forcedCompletion.bits
  }

  observedRecoveryBusy := dependencyBackend.io.recoveryBusy
  observedSelectiveFire := selectiveIssue.io.request.fire
  observedBranchFire := branchIssue.io.request.fire
  observedLsuLaunchFire := lsu.io.request.fire
}

/** P8.2 R3 cross-backend recovery integration proof. */
class V2P82RecoveryIntegrationSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private val config = CoreProfiles.rv32imasuSv32PmpSoftware

  private final case class Identity(
      index: BigInt,
      generation: BigInt,
      producerId: BigInt,
      producerGeneration: BigInt,
      valueId: BigInt,
      valueGeneration: BigInt
  )

  private def initialize(dut: P82RecoveryObservableMemoryBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.forcedCompletion.valid.poke(false.B)
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
      dut: P82RecoveryObservableMemoryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      immediate: BigInt = 0,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      memoryKind: MemoryOperationKind.Type = MemoryOperationKind.None,
      memorySize: MemSize.Type = MemSize.Word,
      memoryUnsigned: Boolean = false
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke(producesValue.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke("h00000013".U)
    dut.io.dispatch.bits.decoded.rawInst.poke("h00000013".U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(AluOp.Add)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.lhsSource.poke(
      if (usesRs1) OperandSourceKind.Rs1 else OperandSourceKind.Zero
    )
    dut.io.dispatch.bits.decoded.rhsSource.poke(OperandSourceKind.Immediate)
    dut.io.dispatch.bits.decoded.rs1.poke(rs1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(0.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(usesRs1.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(false.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(writesRd.B)
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(controlFlowKind)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(BranchType.None)
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
      dut: P82RecoveryObservableMemoryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      immediate: BigInt = 0,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      memoryKind: MemoryOperationKind.Type = MemoryOperationKind.None,
      memorySize: MemSize.Type = MemSize.Word,
      memoryUnsigned: Boolean = false
  ): Identity = {
    pokeDispatch(
      dut,
      pc,
      executionClass,
      rd = rd,
      rs1 = rs1,
      usesRs1 = usesRs1,
      writesRd = writesRd,
      producesValue = producesValue,
      immediate = immediate,
      controlFlowKind = controlFlowKind,
      memoryKind = memoryKind,
      memorySize = memorySize,
      memoryUnsigned = memoryUnsigned
    )
    var cycles = 0
    while (!dut.io.dispatch.ready.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    withClue("dispatch did not become ready: ") {
      dut.io.dispatch.ready.peek().litToBoolean shouldBe true
    }
    dut.io.allocated.valid.expect(true.B)
    val identity = Identity(
      dut.io.allocated.bits.robToken.index.peek().litValue,
      dut.io.allocated.bits.robToken.generation.peek().litValue,
      dut.io.allocated.bits.producerTag.id.peek().litValue,
      dut.io.allocated.bits.producerTag.generation.peek().litValue,
      dut.io.allocated.bits.valueRef.id.peek().litValue,
      dut.io.allocated.bits.valueRef.generation.peek().litValue
    )
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
    identity
  }

  private def forceTakenBranch(
      dut: P82RecoveryObservableMemoryBackend,
      id: Identity,
      target: BigInt
  ): Unit = {
    dut.forcedCompletion.valid.poke(true.B)
    dut.forcedCompletion.bits.robToken.index.poke(id.index.U)
    dut.forcedCompletion.bits.robToken.generation.poke(id.generation.U)
    dut.forcedCompletion.bits.producerTag.id.poke(id.producerId.U)
    dut.forcedCompletion.bits.producerTag.generation.poke(id.producerGeneration.U)
    dut.forcedCompletion.bits.valueRef.id.poke(id.valueId.U)
    dut.forcedCompletion.bits.valueRef.generation.poke(id.valueGeneration.U)
    dut.forcedCompletion.bits.hasValue.poke(false.B)
    dut.forcedCompletion.bits.value.poke(0.U)
    dut.forcedCompletion.bits.branchValid.poke(true.B)
    dut.forcedCompletion.bits.branchTaken.poke(true.B)
    dut.forcedCompletion.bits.branchTarget.poke(target.U)
    dut.forcedCompletion.bits.exception.valid.poke(false.B)
    dut.forcedCompletion.bits.exception.cause.poke(0.U)
    dut.forcedCompletion.bits.exception.value.poke(0.U)
    dut.forcedCompletion.bits.privileged.csrWriteValid.poke(false.B)
    dut.forcedCompletion.bits.privileged.csrAddress.poke(0.U)
    dut.forcedCompletion.bits.privileged.csrWriteData.poke(0.U)
    dut.forcedCompletion.bits.privileged.trapReturn.poke(false.B)
    dut.forcedCompletion.bits.privileged.trapReturnSupervisor.poke(false.B)
  }

  behavior of "AetherCore v2 P8.2 recovery integration"

  it should "hold launches during rebuild and preserve an older completed memory trace" in {
    simulate(new P82RecoveryObservableMemoryBackend(config, PageTableGeometry.Sv32)) { dut =>
      initialize(dut)
      val loadPc = BigInt("80018000", 16)
      val consumerPc = loadPc + 4
      val branchPc = loadPc + 8
      val youngerPc = loadPc + 12
      val target = loadPc + 0x100
      val loadAddress = BigInt("1000", 16)
      val loadData = BigInt("12345678", 16)

      allocate(
        dut,
        loadPc,
        ExecutionClass.Memory,
        rd = 1,
        writesRd = true,
        producesValue = true,
        immediate = loadAddress,
        memoryKind = MemoryOperationKind.Load,
        memorySize = MemSize.Word,
        memoryUnsigned = true
      )
      allocate(
        dut,
        consumerPc,
        ExecutionClass.Integer,
        rd = 2,
        rs1 = 1,
        usesRs1 = true,
        writesRd = true,
        producesValue = true,
        immediate = 1
      )
      val branch = allocate(
        dut,
        branchPc,
        ExecutionClass.Branch,
        controlFlowKind = ControlFlowKind.DirectJump
      )
      allocate(
        dut,
        youngerPc,
        ExecutionClass.Integer,
        rd = 3,
        writesRd = true,
        producesValue = true,
        immediate = 33
      )
      dut.io.occupancy.expect(4.U)

      // The head load is already owned by the LSU. Hold the physical request
      // until the full survivor shape is resident in the ROB.
      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 24) {
        dut.clock.step()
        cycles += 1
      }
      withClue("head load never reached the physical memory seam: ") {
        dut.io.memoryRequest.valid.peek().litToBoolean shouldBe true
      }
      val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(txn.U)
      dut.io.memoryResponse.bits.rdata.poke(loadData.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      // The completed head would normally retire now. Inject the age2 Branch
      // completion before that edge: recovery must atomically retain Load,
      // dependent Integer and Branch, while killing only the younger age3 work.
      dut.io.commit.valid.expect(true.B)
      forceTakenBranch(dut, branch, target)
      dut.io.branchRedirect.valid.expect(true.B)
      dut.io.branchRedirect.bits.target.expect(target.U)
      dut.io.commit.valid.expect(false.B)
      dut.observedSelectiveFire.expect(false.B)
      dut.observedBranchFire.expect(false.B)
      dut.observedLsuLaunchFire.expect(false.B)
      dut.clock.step()
      dut.forcedCompletion.valid.poke(false.B)

      dut.io.occupancy.expect(3.U)
      dut.observedRecoveryBusy.expect(true.B)
      dut.io.commit.valid.expect(false.B)

      // survivorCount=3: age0 replayed on recovery, then age1 and age2 rebuild
      // sequentially. The dependent Integer has become ready from the Load
      // completion, so this explicitly proves recoveryBusy blocks a real
      // otherwise-eligible selective launch.
      var rebuildCycles = 0
      while (dut.observedRecoveryBusy.peek().litToBoolean && rebuildCycles < 4) {
        dut.io.commit.valid.expect(false.B)
        dut.observedSelectiveFire.expect(false.B)
        dut.observedBranchFire.expect(false.B)
        dut.observedLsuLaunchFire.expect(false.B)
        dut.clock.step()
        rebuildCycles += 1
      }
      rebuildCycles shouldBe 2
      dut.observedRecoveryBusy.expect(false.B)

      // The old head-only implementation cleared every pending memory trace on
      // recovery. P8.2 must retain the older Load trace, so its first retirement
      // after rebuild still carries exact physical memory observation.
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.pc.expect(loadPc.U)
      dut.io.commit.rd.expect(1.U)
      dut.io.commit.rdWrite.expect(true.B)
      dut.io.commit.rdData.expect(loadData.U)
      dut.io.commit.memValid.expect(true.B)
      dut.io.commit.memWrite.expect(false.B)
      dut.io.commit.memAddr.expect(loadAddress.U)
      dut.clock.step()

      // The surviving dependent Integer is now allowed to make progress; the
      // killed age3 instruction never reappears in the recovered ROB window.
      dut.io.occupancy.expect(2.U)
      cycles = 0
      var sawConsumerCommit = false
      while (!sawConsumerCommit && cycles < 24) {
        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == consumerPc) {
          dut.io.commit.rdData.expect((loadData + 1).U)
          sawConsumerCommit = true
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("surviving dependent Integer did not resume after rebuild: ") {
        sawConsumerCommit shouldBe true
      }
    }
  }
}
