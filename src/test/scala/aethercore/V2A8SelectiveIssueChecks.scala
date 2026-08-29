package aethercore

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

private class A8SelectiveIssueHarness extends Module {
  private val xlen = 32
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val request = Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits))
  })

  private val backend = Module(new TinyDependencyBackend(xlen))
  private val issue = Module(new TinySelectiveComputeIssue(xlen))

  backend.io.dispatch <> io.dispatch
  backend.io.completion := io.completion

  issue.io.window := backend.io.schedulingWindow
  issue.io.allocated := backend.io.allocated
  issue.io.block := false.B
  issue.io.availability.integer := true.B
  issue.io.availability.multiply := true.B
  issue.io.availability.divide := true.B
  io.request <> issue.io.request
}

/** Focused A8.3b checks for oldest-ready side-effect-free compute issue. */
trait V2A8SelectiveIssueChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeWindowEntry(
      dut: TinySelectiveComputeIssue,
      age: Int,
      valid: Boolean,
      complete: Boolean,
      executionClass: ExecutionClass.Type,
      op: AluOp.Type,
      index: Int,
      generation: Int,
      operandsReady: Boolean,
      lhs: BigInt = 0,
      rhs: BigInt = 0,
      ordering: OrderingClass.Type = OrderingClass.Normal,
      predecodedException: Boolean = false
  ): Unit = {
    val entry = dut.io.window(age)
    entry.valid.poke(valid.B)
    entry.complete.poke(complete.B)
    entry.dependenciesValid.poke(valid.B)
    entry.operandsReady.poke(operandsReady.B)

    entry.uop.robToken.index.poke(index.U)
    entry.uop.robToken.generation.poke(generation.U)
    entry.uop.producerTag.id.poke(index.U)
    entry.uop.producerTag.generation.poke(generation.U)
    entry.uop.valueRef.id.poke(index.U)
    entry.uop.valueRef.generation.poke(generation.U)
    entry.uop.executionClass.poke(executionClass)
    entry.uop.producesValue.poke(true.B)

    entry.uop.decoded.pc.poke((0x1000 + age * 4).U)
    entry.uop.decoded.instBytes.poke(4.U)
    entry.uop.decoded.aluOp.poke(op)
    entry.uop.decoded.wordOp.poke(false.B)
    entry.uop.decoded.lhsSource.poke(OperandSourceKind.Rs1)
    entry.uop.decoded.rhsSource.poke(OperandSourceKind.Rs2)
    entry.uop.decoded.immediate.poke(0.U)
    entry.uop.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    entry.uop.decoded.controlFlow.branchType.poke(BranchType.None)
    entry.uop.decoded.ordering.poke(ordering)
    entry.uop.decoded.exception.valid.poke(predecodedException.B)
    entry.uop.decoded.exception.cause.poke(0.U)
    entry.uop.decoded.exception.value.poke(0.U)

    entry.rs1.ready.poke(operandsReady.B)
    entry.rs1.value.poke(lhs.U)
    entry.rs1.producerTag.id.poke(0.U)
    entry.rs1.producerTag.generation.poke(0.U)
    entry.rs2.ready.poke(operandsReady.B)
    entry.rs2.value.poke(rhs.U)
    entry.rs2.producerTag.id.poke(0.U)
    entry.rs2.producerTag.generation.poke(0.U)
  }

  private def clearWindow(dut: TinySelectiveComputeIssue): Unit = {
    for (age <- 0 until TinyRobGeometry.Entries) {
      pokeWindowEntry(
        dut,
        age = age,
        valid = false,
        complete = false,
        executionClass = ExecutionClass.Integer,
        op = AluOp.Add,
        index = age,
        generation = 0,
        operandsReady = false
      )
    }
  }

  private def initializeSelector(dut: TinySelectiveComputeIssue): Unit = {
    dut.io.allocated.valid.poke(false.B)
    dut.io.block.poke(false.B)
    dut.io.availability.integer.poke(true.B)
    dut.io.availability.multiply.poke(true.B)
    dut.io.availability.divide.poke(true.B)
    dut.io.request.ready.poke(true.B)
    clearWindow(dut)
  }

  private def pokeDispatch(
      dut: A8SelectiveIssueHarness,
      pc: BigInt,
      rd: Int,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      immediate: BigInt = 0
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(ExecutionClass.Integer)
    dut.io.dispatch.bits.producesValue.poke(true.B)
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
    dut.io.dispatch.bits.decoded.writesRd.poke(true.B)
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
    dut.io.dispatch.bits.decoded.system.xret.poke(XRetOp.None)
    dut.io.dispatch.bits.decoded.ordering.poke(OrderingClass.Normal)
    dut.io.dispatch.bits.decoded.exception.valid.poke(false.B)
    dut.io.dispatch.bits.decoded.exception.cause.poke(0.U)
    dut.io.dispatch.bits.decoded.exception.value.poke(0.U)
    dut.io.dispatch.ready.expect(true.B)
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def completeProducer(
      dut: A8SelectiveIssueHarness,
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

  behavior of "AetherCore v2 A8 selective compute issue"

  it should "choose the oldest ready safe compute entry exactly once" in {
    simulate(new TinySelectiveComputeIssue(32)) { dut =>
      initializeSelector(dut)
      pokeWindowEntry(dut, 0, valid = true, complete = false, ExecutionClass.Integer, AluOp.Add, 0, 0, operandsReady = false)
      pokeWindowEntry(dut, 1, valid = true, complete = false, ExecutionClass.Integer, AluOp.Add, 1, 0, operandsReady = true, lhs = 10, rhs = 1)
      pokeWindowEntry(dut, 2, valid = true, complete = false, ExecutionClass.Integer, AluOp.Add, 2, 0, operandsReady = true, lhs = 20, rhs = 2)

      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(1.U)
      dut.io.request.bits.lhs.expect(10.U)
      dut.io.request.bits.rhs.expect(1.U)
      dut.clock.step()

      // age1 is now issued; age2 becomes the next oldest eligible entry.
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(2.U)
      dut.clock.step()
      dut.io.request.valid.expect(false.B)
    }
  }

  it should "skip a ready uOp whose target resource is busy and fail closed on non-compute classes" in {
    simulate(new TinySelectiveComputeIssue(32)) { dut =>
      initializeSelector(dut)
      dut.io.availability.divide.poke(false.B)
      pokeWindowEntry(dut, 0, valid = true, complete = false, ExecutionClass.MulDiv, AluOp.Divu, 0, 0, operandsReady = true, lhs = 100, rhs = 7)
      pokeWindowEntry(dut, 1, valid = true, complete = false, ExecutionClass.Integer, AluOp.Add, 1, 0, operandsReady = true, lhs = 4, rhs = 5)
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(1.U)

      // Once DIV becomes available the older age0 entry wins.
      dut.io.request.ready.poke(false.B)
      dut.io.availability.divide.poke(true.B)
      dut.io.request.bits.robToken.index.expect(0.U)

      clearWindow(dut)
      dut.io.request.ready.poke(true.B)
      pokeWindowEntry(dut, 0, valid = true, complete = false, ExecutionClass.Branch, AluOp.Add, 0, 0, operandsReady = true)
      pokeWindowEntry(dut, 1, valid = true, complete = false, ExecutionClass.Memory, AluOp.Add, 1, 0, operandsReady = true)
      pokeWindowEntry(dut, 2, valid = true, complete = false, ExecutionClass.System, AluOp.Add, 2, 0, operandsReady = true)
      pokeWindowEntry(dut, 3, valid = true, complete = false, ExecutionClass.Integer, AluOp.Add, 3, 0, operandsReady = true)
      // age1 Memory is not the exact head, so it cannot have launched into the
      // LSU yet and must block younger selective compute. The following System
      // is an independent architectural barrier as well.
      dut.io.request.valid.expect(false.B)

      dut.io.block.poke(true.B)
      dut.io.request.valid.expect(false.B)
    }
  }

  it should "bypass a blocked older consumer and return to it after producer wakeup" in {
    simulate(new A8SelectiveIssueHarness) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.request.ready.poke(false.B)

      // P writes x1. C is older than I but waits on P; I is independent.
      pokeDispatch(dut, pc = 0x2000, rd = 1, immediate = 11)
      pokeDispatch(dut, pc = 0x2004, rd = 2, rs1 = 1, usesRs1 = true, immediate = 1)
      pokeDispatch(dut, pc = 0x2008, rd = 3, immediate = 33)

      // P is the oldest ready entry.
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(0.U)
      dut.io.request.ready.poke(true.B)
      dut.clock.step()

      // P is now marked issued. C remains blocked, so younger independent I
      // issues even though C is older in the ROB.
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(2.U)
      dut.clock.step()
      dut.io.request.valid.expect(false.B)

      // P completion wakes C through the real dependency tracker. The selector
      // then returns to age1 and materializes the forwarded producer value.
      completeProducer(dut, index = 0, generation = 0, value = 99)
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(1.U)
      dut.io.request.bits.lhs.expect(99.U)
      dut.io.request.bits.rhs.expect(1.U)
    }
  }
}
