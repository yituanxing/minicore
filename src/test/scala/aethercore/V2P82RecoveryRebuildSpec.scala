package aethercore.core.v2

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}

/** P8.2 R2 proof for bounded speculative rename rebuild.
  *
  * The test deliberately creates a younger WAW mapping that must disappear on
  * middle-aged Branch recovery. The surviving Branch link producer must be
  * restored before dispatch resumes, without rolling back committed state.
  */
class V2P82RecoveryRebuildSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private final case class Identity(
      index: BigInt,
      generation: BigInt,
      producerId: BigInt,
      producerGeneration: BigInt,
      valueId: BigInt,
      valueGeneration: BigInt
  )

  private def initialize(dut: TinyDependencyBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.completion.valid.poke(false.B)
  }

  private def pokeDispatch(
      dut: TinyDependencyBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None
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
    dut.io.dispatch.bits.decoded.lhsSource.poke(if (usesRs1) OperandSourceKind.Rs1 else OperandSourceKind.Zero)
    dut.io.dispatch.bits.decoded.rhsSource.poke(OperandSourceKind.Zero)
    dut.io.dispatch.bits.decoded.rs1.poke(rs1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(0.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(usesRs1.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(false.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(writesRd.B)
    dut.io.dispatch.bits.decoded.immediate.poke(0.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(controlFlowKind)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(BranchType.None)
    dut.io.dispatch.bits.decoded.memory.kind.poke(MemoryOperationKind.None)
    dut.io.dispatch.bits.decoded.memory.size.poke(MemSize.Word)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(false.B)
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
      dut: TinyDependencyBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None
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
      controlFlowKind = controlFlowKind
    )
    dut.io.dispatch.ready.expect(true.B)
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

  private def pokeCompletion(
      dut: TinyDependencyBackend,
      id: Identity,
      hasValue: Boolean,
      value: BigInt,
      branch: Boolean = false,
      taken: Boolean = false,
      target: BigInt = 0
  ): Unit = {
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.bits.robToken.index.poke(id.index.U)
    dut.io.completion.bits.robToken.generation.poke(id.generation.U)
    dut.io.completion.bits.producerTag.id.poke(id.producerId.U)
    dut.io.completion.bits.producerTag.generation.poke(id.producerGeneration.U)
    dut.io.completion.bits.valueRef.id.poke(id.valueId.U)
    dut.io.completion.bits.valueRef.generation.poke(id.valueGeneration.U)
    dut.io.completion.bits.hasValue.poke(hasValue.B)
    dut.io.completion.bits.value.poke(value.U)
    dut.io.completion.bits.branchValid.poke(branch.B)
    dut.io.completion.bits.branchTaken.poke(taken.B)
    dut.io.completion.bits.branchTarget.poke(target.U)
    dut.io.completion.bits.exception.valid.poke(false.B)
    dut.io.completion.bits.exception.cause.poke(0.U)
    dut.io.completion.bits.exception.value.poke(0.U)
    dut.io.completion.bits.privileged.csrWriteValid.poke(false.B)
    dut.io.completion.bits.privileged.csrAddress.poke(0.U)
    dut.io.completion.bits.privileged.csrWriteData.poke(0.U)
    dut.io.completion.bits.privileged.trapReturn.poke(false.B)
    dut.io.completion.bits.privileged.trapReturnSupervisor.poke(false.B)
  }

  behavior of "AetherCore v2 P8.2 bounded recovery rebuild"

  it should "restore the youngest surviving producer after a killed WAW" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new TinyDependencyBackend(xlen)) { dut =>
        initialize(dut)
        val base = BigInt("b1000000", 16)
        val link = base + 8
        val target = base + 0x80

        val older = allocate(dut, base, ExecutionClass.Integer)
        val branch = allocate(
          dut,
          base + 4,
          ExecutionClass.Branch,
          rd = 5,
          writesRd = true,
          producesValue = true,
          controlFlowKind = ControlFlowKind.DirectJump
        )
        val killedWaw = allocate(
          dut,
          base + 8,
          ExecutionClass.Integer,
          rd = 5,
          writesRd = true,
          producesValue = true
        )
        dut.io.occupancy.expect(3.U)

        // Complete the older head, then present the middle-aged Branch response
        // on the immediately following cycle. ROB recovery must suppress that
        // otherwise-ready retirement and dependency rebuild owns the boundary.
        pokeCompletion(dut, older, hasValue = false, value = 0)
        dut.io.acceptedRecovery.valid.expect(false.B)
        dut.clock.step()
        dut.io.completion.valid.poke(false.B)
        dut.io.commit.valid.expect(true.B)

        pokeCompletion(
          dut,
          branch,
          hasValue = true,
          value = link,
          branch = true,
          taken = true,
          target = target
        )
        dut.io.acceptedRecovery.valid.expect(true.B)
        dut.io.acceptedRecoverySurvivorCount.expect(2.U)
        dut.io.commit.valid.expect(false.B)
        dut.io.dispatch.ready.expect(false.B)
        dut.clock.step()
        dut.io.completion.valid.poke(false.B)

        dut.io.occupancy.expect(2.U)
        dut.io.recoveryBusy.expect(true.B)
        dut.io.dispatch.ready.expect(false.B)
        dut.io.commit.valid.expect(false.B)

        // survivorCount=2: age0 was replayed on the recovery cycle and age1
        // (the Branch) is replayed during this single rebuild cycle.
        dut.clock.step()
        dut.io.recoveryBusy.expect(false.B)
        dut.io.commit.valid.expect(true.B)

        // Dispatch resumes in the same cycle that the complete older head may
        // retire. The new consumer must resolve x5 through the surviving Branch,
        // not the killed younger WAW and not stale committed RF state.
        val consumer = allocate(
          dut,
          target,
          ExecutionClass.Integer,
          rd = 6,
          rs1 = 5,
          usesRs1 = true,
          writesRd = true,
          producesValue = true
        )
        consumer.index shouldBe killedWaw.index
        consumer.generation should not be killedWaw.generation

        dut.io.schedulingWindow(0).uop.robToken.index.expect(branch.index.U)
        dut.io.schedulingWindow(1).uop.robToken.index.expect(consumer.index.U)
        dut.io.schedulingWindow(1).dependenciesValid.expect(true.B)
        // Diagnose mapping ownership before value storage. If these fail, RAT
        // rebuild did not restore the surviving Branch; if they pass but value
        // fails, the mapping is correct and producer value retention is wrong.
        dut.io.schedulingWindow(1).rs1.producerTag.id.expect(branch.producerId.U)
        dut.io.schedulingWindow(1).rs1.producerTag.generation.expect(branch.producerGeneration.U)
        dut.io.schedulingWindow(1).rs1.ready.expect(true.B)
        dut.io.schedulingWindow(1).rs1.value.expect(link.U)

        // The killed WAW's late completion cannot wake or complete the reused
        // physical slot after generation advance.
        pokeCompletion(dut, killedWaw, hasValue = true, value = 99)
        dut.io.acceptedRecovery.valid.expect(false.B)
        dut.clock.step()
        dut.io.completion.valid.poke(false.B)
        dut.io.schedulingWindow(1).complete.expect(false.B)
        dut.io.schedulingWindow(1).rs1.value.expect(link.U)
      }
    }
  }
}
