package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize}
import aethercore.core.v2._

/** Focused A8.3a proof for the read-only ROB/dependency scheduling projection. */
trait V2A8SchedulingViewChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def initialize(dut: TinyDependencyBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.completion.valid.poke(false.B)
  }

  private def dispatch(
      dut: TinyDependencyBackend,
      pc: BigInt,
      rd: Int,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      immediate: BigInt = 0,
      producesValue: Boolean = true
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(ExecutionClass.Integer)
    dut.io.dispatch.bits.producesValue.poke(producesValue.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke(0x13.U)
    dut.io.dispatch.bits.decoded.rawInst.poke(0x13.U)
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
    dut.io.dispatch.bits.decoded.writesRd.poke((rd != 0).B)
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(ControlFlowKind.None)
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
    dut.io.dispatch.bits.decoded.system.xret.poke(aethercore.common.XRetOp.None)
    dut.io.dispatch.bits.decoded.ordering.poke(OrderingClass.Normal)
    dut.io.dispatch.bits.decoded.exception.valid.poke(false.B)
    dut.io.dispatch.bits.decoded.exception.cause.poke(0.U)
    dut.io.dispatch.bits.decoded.exception.value.poke(0.U)

    dut.io.dispatch.ready.expect(true.B)
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def complete(
      dut: TinyDependencyBackend,
      index: Int,
      generation: Int,
      value: BigInt
  ): Unit = {
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.bits.robToken.index.poke(index.U)
    dut.io.completion.bits.robToken.generation.poke(generation.U)
    dut.io.completion.bits.producerTag.id.poke(index.U)
    dut.io.completion.bits.producerTag.generation.poke(generation.U)
    dut.io.completion.bits.valueRef.id.poke(index.U)
    dut.io.completion.bits.valueRef.generation.poke(generation.U)
    dut.io.completion.bits.hasValue.poke(true.B)
    dut.io.completion.bits.value.poke(value.U)
    dut.io.completion.bits.branchValid.poke(false.B)
    dut.io.completion.bits.branchTaken.poke(false.B)
    dut.io.completion.bits.branchTarget.poke(0.U)
    dut.io.completion.bits.exception.valid.poke(false.B)
    dut.io.completion.bits.exception.cause.poke(0.U)
    dut.io.completion.bits.exception.value.poke(0.U)
    dut.io.completion.bits.privileged.csrWriteValid.poke(false.B)
    dut.io.completion.bits.privileged.csrAddress.poke(0.U)
    dut.io.completion.bits.privileged.csrWriteData.poke(0.U)
    dut.io.completion.bits.privileged.trapReturn.poke(false.B)
    dut.io.completion.bits.privileged.trapReturnSupervisor.poke(false.B)
    dut.clock.step()
    dut.io.completion.valid.poke(false.B)
  }

  behavior of "AetherCore v2 A8 read-only scheduling view"

  it should "compose ROB age with exact-lifetime dependency readiness across ring wrap" in {
    simulate(new TinyDependencyBackend(32)) { dut =>
      initialize(dut)

      // P produces x1. C is older than I but waits on P; I is independent.
      dispatch(dut, pc = 0x1000, rd = 1, immediate = 11)
      dispatch(dut, pc = 0x1004, rd = 2, rs1 = 1, usesRs1 = true, immediate = 1)
      dispatch(dut, pc = 0x1008, rd = 3, immediate = 33)

      dut.io.occupancy.expect(3.U)
      for (age <- 0 until 3) dut.io.schedulingWindow(age).valid.expect(true.B)
      dut.io.schedulingWindow(3).valid.expect(false.B)
      dut.io.schedulingWindow(0).uop.robToken.index.expect(0.U)
      dut.io.schedulingWindow(1).uop.robToken.index.expect(1.U)
      dut.io.schedulingWindow(2).uop.robToken.index.expect(2.U)
      dut.io.schedulingWindow(0).complete.expect(false.B)

      dut.io.schedulingWindow(1).dependenciesValid.expect(true.B)
      dut.io.schedulingWindow(1).operandsReady.expect(false.B)
      dut.io.schedulingWindow(1).rs1.ready.expect(false.B)
      dut.io.schedulingWindow(1).rs1.producerTag.id.expect(0.U)
      dut.io.schedulingWindow(1).rs1.producerTag.generation.expect(0.U)
      dut.io.schedulingWindow(2).operandsReady.expect(true.B)

      // Completing P marks only age0 complete and wakes C by ProducerTag.
      complete(dut, index = 0, generation = 0, value = 99)
      dut.io.schedulingWindow(0).uop.robToken.index.expect(0.U)
      dut.io.schedulingWindow(0).complete.expect(true.B)
      dut.io.schedulingWindow(1).uop.robToken.index.expect(1.U)
      dut.io.schedulingWindow(1).operandsReady.expect(true.B)
      dut.io.schedulingWindow(1).rs1.ready.expect(true.B)
      dut.io.schedulingWindow(1).rs1.value.expect(99.U)

      // Retire P. The same physical dependency slots must now project from the
      // new ROB head without turning slot number into age.
      dut.clock.step()
      dut.io.occupancy.expect(2.U)
      dut.io.schedulingWindow(0).uop.robToken.index.expect(1.U)
      dut.io.schedulingWindow(1).uop.robToken.index.expect(2.U)
      dut.io.schedulingWindow(0).operandsReady.expect(true.B)
      dut.io.schedulingWindow(0).rs1.value.expect(99.U)

      // Fill slot3 and then wrapped slot0. Slot0 must carry its incremented
      // lifetime generation while appearing as the youngest age3 entry.
      dispatch(dut, pc = 0x100c, rd = 4, immediate = 44)
      dispatch(dut, pc = 0x1010, rd = 5, immediate = 55)
      dut.io.occupancy.expect(4.U)

      val expectedIndices = Seq(1, 2, 3, 0)
      for ((index, age) <- expectedIndices.zipWithIndex) {
        dut.io.schedulingWindow(age).valid.expect(true.B)
        dut.io.schedulingWindow(age).uop.robToken.index.expect(index.U)
        dut.io.schedulingWindow(age).dependenciesValid.expect(true.B)
      }
      dut.io.schedulingWindow(0).uop.robToken.generation.expect(0.U)
      dut.io.schedulingWindow(1).uop.robToken.generation.expect(0.U)
      dut.io.schedulingWindow(2).uop.robToken.generation.expect(0.U)
      dut.io.schedulingWindow(3).uop.robToken.generation.expect(1.U)
    }
  }
}
