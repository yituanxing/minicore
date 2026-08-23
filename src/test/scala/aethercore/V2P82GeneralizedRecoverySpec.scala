package aethercore.core.v2

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}

/** Focused P8.2 R1 proof for arbitrary-age ROB branch recovery.
  *
  * Dependency rebuild and arbitrary-age Branch issue are deliberately out of
  * scope here. This spec drives TinyRob directly so the ROB can prove its new
  * younger-only recovery authority before the higher layers consume it.
  */
class V2P82GeneralizedRecoverySpec extends AnyFlatSpec with Matchers with ChiselSim {
  private final case class Identity(
      index: BigInt,
      generation: BigInt,
      producerId: BigInt,
      producerGeneration: BigInt,
      valueId: BigInt,
      valueGeneration: BigInt
  )

  private def initialize(dut: TinyRob): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.retire.ready.poke(true.B)
  }

  private def pokeDispatch(
      dut: TinyRob,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke(false.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke("h00000013".U)
    dut.io.dispatch.bits.decoded.rawInst.poke("h00000013".U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(AluOp.Add)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.lhsSource.poke(OperandSourceKind.Zero)
    dut.io.dispatch.bits.decoded.rhsSource.poke(OperandSourceKind.Zero)
    dut.io.dispatch.bits.decoded.rs1.poke(0.U)
    dut.io.dispatch.bits.decoded.rs2.poke(0.U)
    dut.io.dispatch.bits.decoded.rd.poke(0.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(false.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(false.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(false.B)
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
      dut: TinyRob,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None
  ): Identity = {
    pokeDispatch(dut, pc, executionClass, controlFlowKind)
    dut.io.dispatch.ready.expect(true.B)
    dut.io.allocated.valid.expect(true.B)
    val identity = Identity(
      index = dut.io.allocated.bits.robToken.index.peek().litValue,
      generation = dut.io.allocated.bits.robToken.generation.peek().litValue,
      producerId = dut.io.allocated.bits.producerTag.id.peek().litValue,
      producerGeneration = dut.io.allocated.bits.producerTag.generation.peek().litValue,
      valueId = dut.io.allocated.bits.valueRef.id.peek().litValue,
      valueGeneration = dut.io.allocated.bits.valueRef.generation.peek().litValue
    )
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
    identity
  }

  private def pokeCompletion(
      dut: TinyRob,
      identity: Identity,
      branch: Boolean = false,
      taken: Boolean = false,
      target: BigInt = 0
  ): Unit = {
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.bits.robToken.index.poke(identity.index.U)
    dut.io.completion.bits.robToken.generation.poke(identity.generation.U)
    dut.io.completion.bits.producerTag.id.poke(identity.producerId.U)
    dut.io.completion.bits.producerTag.generation.poke(identity.producerGeneration.U)
    dut.io.completion.bits.valueRef.id.poke(identity.valueId.U)
    dut.io.completion.bits.valueRef.generation.poke(identity.valueGeneration.U)
    dut.io.completion.bits.hasValue.poke(false.B)
    dut.io.completion.bits.value.poke(0.U)
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

  private def completeOrdinary(dut: TinyRob, identity: Identity): Unit = {
    pokeCompletion(dut, identity)
    dut.io.acceptedCompletion.valid.expect(true.B)
    dut.io.acceptedRecovery.valid.expect(false.B)
    dut.clock.step()
    dut.io.completion.valid.poke(false.B)
  }

  behavior of "AetherCore v2 P8.2 generalized ROB recovery"

  it should "preserve older work and kill only younger work across circular slot wrap" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new TinyRob(xlen)) { dut =>
        initialize(dut)

        val base = BigInt("b0000000", 16)
        val a = allocate(dut, base, ExecutionClass.Integer)
        val b = allocate(dut, base + 4, ExecutionClass.Integer)
        val c = allocate(dut, base + 8, ExecutionClass.Integer)
        val branch = allocate(
          dut,
          base + 12,
          ExecutionClass.Branch,
          ControlFlowKind.DirectJump
        )
        dut.io.occupancy.expect(4.U)

        // Retire A so head moves to slot1, then allocate E into wrapped slot0.
        completeOrdinary(dut, a)
        dut.io.retire.valid.expect(true.B)
        dut.clock.step()
        dut.io.occupancy.expect(3.U)

        val youngerWrapped = allocate(dut, base + 16, ExecutionClass.Integer)
        youngerWrapped.index shouldBe a.index
        youngerWrapped.generation should not be a.generation
        dut.io.occupancy.expect(4.U)

        // Make B complete but do not let it retire before the middle-aged
        // Branch recovery. C remains older and incomplete.
        completeOrdinary(dut, b)
        dut.io.retire.valid.expect(true.B)

        val target = base + 0x100
        pokeCompletion(dut, branch, branch = true, taken = true, target = target)
        dut.io.acceptedCompletion.valid.expect(true.B)
        dut.io.acceptedRecovery.valid.expect(true.B)
        dut.io.acceptedRecovery.bits.robToken.index.expect(branch.index.U)
        dut.io.acceptedRecovery.bits.robToken.generation.expect(branch.generation.U)
        dut.io.acceptedRecovery.bits.branchTarget.expect(target.U)
        dut.io.dispatch.ready.expect(false.B)

        // Recovery owns the ROB boundary atomically: even the already-complete
        // older head B waits until the next cycle to retire.
        dut.io.retire.valid.expect(false.B)
        dut.clock.step()
        dut.io.completion.valid.poke(false.B)

        // Architectural age order was B, C, Branch, E. Only E is younger than
        // the recovering Branch, even though E lives in wrapped physical slot0.
        dut.io.occupancy.expect(3.U)
        dut.io.window(0).valid.expect(true.B)
        dut.io.window(0).uop.robToken.index.expect(b.index.U)
        dut.io.window(0).complete.expect(true.B)
        dut.io.window(1).valid.expect(true.B)
        dut.io.window(1).uop.robToken.index.expect(c.index.U)
        dut.io.window(1).complete.expect(false.B)
        dut.io.window(2).valid.expect(true.B)
        dut.io.window(2).uop.robToken.index.expect(branch.index.U)
        dut.io.window(2).complete.expect(true.B)
        dut.io.window(3).valid.expect(false.B)

        // Hold retirement so the first post-recovery allocation must reuse the
        // killed wrapped slot. Its generation must already have advanced.
        dut.io.retire.ready.poke(false.B)
        val replacement = allocate(dut, target, ExecutionClass.Integer)
        replacement.index shouldBe youngerWrapped.index
        replacement.generation should not be youngerWrapped.generation
        dut.io.occupancy.expect(4.U)

        // A late response from killed E must not complete the reused slot.
        pokeCompletion(dut, youngerWrapped)
        dut.io.acceptedCompletion.valid.expect(false.B)
        dut.io.acceptedRecovery.valid.expect(false.B)
        dut.clock.step()
        dut.io.completion.valid.poke(false.B)
        dut.io.occupancy.expect(4.U)
      }
    }
  }
}
