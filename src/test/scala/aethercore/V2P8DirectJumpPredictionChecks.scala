package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

trait V2P8DirectJumpPredictionChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private case class Identity(
      robIndex: BigInt,
      robGeneration: BigInt,
      producerId: BigInt,
      producerGeneration: BigInt,
      valueId: BigInt,
      valueGeneration: BigInt
  )

  private def pokeDispatch(
      dut: TinyRob,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      immediate: BigInt = 0,
      predictionValid: Boolean = false,
      predictedNextPc: BigInt = 0
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke(false.B)
    dut.io.dispatch.bits.predictionValid.poke(predictionValid.B)
    dut.io.dispatch.bits.predictedNextPc.poke(predictedNextPc.U)

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
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
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
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      immediate: BigInt = 0,
      predictionValid: Boolean = false,
      predictedNextPc: BigInt = 0
  ): Identity = {
    pokeDispatch(
      dut,
      pc,
      executionClass,
      controlFlowKind,
      immediate,
      predictionValid,
      predictedNextPc
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

  private def pokeBranchCompletion(
      dut: TinyRob,
      identity: Identity,
      taken: Boolean,
      target: BigInt
  ): Unit = {
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.bits.robToken.index.poke(identity.robIndex.U)
    dut.io.completion.bits.robToken.generation.poke(identity.robGeneration.U)
    dut.io.completion.bits.producerTag.id.poke(identity.producerId.U)
    dut.io.completion.bits.producerTag.generation.poke(identity.producerGeneration.U)
    dut.io.completion.bits.valueRef.id.poke(identity.valueId.U)
    dut.io.completion.bits.valueRef.generation.poke(identity.valueGeneration.U)
    dut.io.completion.bits.hasValue.poke(false.B)
    dut.io.completion.bits.value.poke(0.U)
    dut.io.completion.bits.branchValid.poke(true.B)
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

  behavior of "AetherCore v2 static control-flow prediction"

  it should "keep younger work when a predicted direct jump resolves to the predicted target" in {
    simulate(new TinyRob(64)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.retire.ready.poke(false.B)

      val pc = BigInt("80200000", 16)
      val target = pc + 0x40
      val branch = allocate(
        dut,
        pc,
        ExecutionClass.Branch,
        controlFlowKind = ControlFlowKind.DirectJump,
        immediate = 0x40,
        predictionValid = true,
        predictedNextPc = target
      )
      allocate(dut, target, ExecutionClass.Integer)
      dut.io.occupancy.expect(2.U)

      pokeBranchCompletion(dut, branch, taken = true, target)
      dut.io.acceptedCompletion.valid.expect(true.B)
      dut.io.acceptedRecovery.valid.expect(false.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      // Correct-path younger work survives; only misprediction may squash it.
      dut.io.occupancy.expect(2.U)
      dut.io.retire.valid.expect(true.B)
    }
  }

  it should "preserve legacy taken recovery when no frontend prediction was attached" in {
    simulate(new TinyRob(64)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.retire.ready.poke(false.B)

      val pc = BigInt("80210000", 16)
      val target = pc + 0x80
      val branch = allocate(
        dut,
        pc,
        ExecutionClass.Branch,
        controlFlowKind = ControlFlowKind.DirectJump,
        immediate = 0x80,
        predictionValid = false
      )
      allocate(dut, pc + 4, ExecutionClass.Integer)
      dut.io.occupancy.expect(2.U)

      pokeBranchCompletion(dut, branch, taken = true, target)
      dut.io.acceptedRecovery.valid.expect(true.B)
      dut.io.acceptedRecovery.bits.branchTarget.expect(target.U)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      dut.io.occupancy.expect(1.U)
    }
  }

  it should "recover a predicted-taken conditional to fallthrough when it is actually not taken" in {
    simulate(new TinyRob(64)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.retire.ready.poke(false.B)

      val pc = BigInt("80220000", 16)
      val predictedTarget = pc - 0x20
      val fallthrough = pc + 4
      val branch = allocate(
        dut,
        pc,
        ExecutionClass.Branch,
        controlFlowKind = ControlFlowKind.Conditional,
        predictionValid = true,
        predictedNextPc = predictedTarget
      )
      allocate(dut, predictedTarget, ExecutionClass.Integer)
      dut.io.occupancy.expect(2.U)

      pokeBranchCompletion(dut, branch, taken = false, target = predictedTarget)
      dut.io.acceptedCompletion.valid.expect(true.B)
      dut.io.acceptedRecovery.valid.expect(true.B)
      dut.io.acceptedRecovery.bits.branchTarget.expect(fallthrough.U)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      // Wrong-path predicted-target work is squashed; the frontend restarts at fallthrough.
      dut.io.occupancy.expect(1.U)
    }
  }
}
