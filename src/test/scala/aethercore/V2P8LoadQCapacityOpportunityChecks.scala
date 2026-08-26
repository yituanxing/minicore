package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

/**
  * Focused proof that the LoadQ3 opportunity seam observes an otherwise-legal
  * Load before the existing two-slot capacity gate and never changes launch.
  */
trait V2P8LoadQCapacityOpportunityChecks {
    this: AnyFlatSpec with Matchers with ChiselSim =>

  private def pokeEntry(
      dut: TinyLoadQueueIssue,
      age: Int,
      valid: Boolean,
      complete: Boolean,
      executionClass: ExecutionClass.Type,
      memoryKind: MemoryOperationKind.Type,
      index: Int,
      operandsReady: Boolean,
      ordering: OrderingClass.Type = OrderingClass.Normal,
      exception: Boolean = false,
      base: BigInt = 0x1000,
      offset: BigInt = 0
  ): Unit = {
    val entry = dut.io.window(age)
    val isLoad = memoryKind == MemoryOperationKind.Load

    entry.valid.poke(valid.B)
    entry.complete.poke(complete.B)
    entry.dependenciesValid.poke(valid.B)
    entry.operandsReady.poke(operandsReady.B)
    entry.uop.robToken.index.poke(index.U)
    entry.uop.robToken.generation.poke(0.U)
    entry.uop.producerTag.id.poke(index.U)
    entry.uop.producerTag.generation.poke(0.U)
    entry.uop.valueRef.id.poke(index.U)
    entry.uop.valueRef.generation.poke(0.U)
    entry.uop.executionClass.poke(executionClass)
    entry.uop.producesValue.poke(isLoad.B)
    entry.uop.decoded.pc.poke((0x8000 + age * 4).U)
    entry.uop.decoded.inst.poke(0.U)
    entry.uop.decoded.rawInst.poke(0x00002003.U)
    entry.uop.decoded.instBytes.poke(4.U)
    entry.uop.decoded.aluOp.poke(AluOp.Add)
    entry.uop.decoded.wordOp.poke(false.B)
    entry.uop.decoded.lhsSource.poke(OperandSourceKind.Rs1)
    entry.uop.decoded.rhsSource.poke(OperandSourceKind.Rs2)
    entry.uop.decoded.rs1.poke(1.U)
    entry.uop.decoded.rs2.poke(2.U)
    entry.uop.decoded.rd.poke(3.U)
    entry.uop.decoded.usesRs1.poke(true.B)
    entry.uop.decoded.usesRs2.poke(false.B)
    entry.uop.decoded.writesRd.poke(isLoad.B)
    entry.uop.decoded.immediate.poke(offset.U)
    entry.uop.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    entry.uop.decoded.controlFlow.branchType.poke(BranchType.None)
    entry.uop.decoded.memory.kind.poke(memoryKind)
    entry.uop.decoded.memory.size.poke(MemSize.Word)
    entry.uop.decoded.memory.unsigned.poke(false.B)
    entry.uop.decoded.memory.atomicOp.poke(AtomicOp.None)
    entry.uop.decoded.memory.acquire.poke(false.B)
    entry.uop.decoded.memory.release.poke(false.B)
    entry.uop.decoded.system.kind.poke(SystemOperationKind.None)
    entry.uop.decoded.system.csrOp.poke(CsrOp.None)
    entry.uop.decoded.system.csrAddress.poke(0.U)
    entry.uop.decoded.system.csrUseImmediate.poke(false.B)
    entry.uop.decoded.system.csrImmediate.poke(0.U)
    entry.uop.decoded.system.xret.poke(XRetOp.None)
    entry.uop.decoded.ordering.poke(ordering)
    entry.uop.decoded.exception.valid.poke(exception.B)
    entry.uop.decoded.exception.cause.poke(0.U)
    entry.uop.decoded.exception.value.poke(0.U)
    entry.rs1.ready.poke(operandsReady.B)
    entry.rs1.value.poke(base.U)
    entry.rs1.producerTag.id.poke(0.U)
    entry.rs1.producerTag.generation.poke(0.U)
    entry.rs2.ready.poke(operandsReady.B)
    entry.rs2.value.poke(0.U)
    entry.rs2.producerTag.id.poke(0.U)
    entry.rs2.producerTag.generation.poke(0.U)
  }

  private def clear(dut: TinyLoadQueueIssue): Unit = {
    dut.io.allocated.valid.poke(false.B)
    dut.io.block.poke(false.B)
    dut.io.available.poke(true.B)
    dut.io.request.ready.poke(true.B)
    for (slot <- dut.io.bypassable) {
      slot.valid.poke(false.B)
      slot.bits.index.poke(0.U)
      slot.bits.generation.poke(0.U)
    }
    for (age <- 0 until TinyRobGeometry.Entries) {
      pokeEntry(
        dut,
        age,
        valid = false,
        complete = false,
        executionClass = ExecutionClass.Integer,
        memoryKind = MemoryOperationKind.None,
        index = age,
        operandsReady = false
      )
    }
  }

  private def readyLoad(dut: TinyLoadQueueIssue, age: Int): Unit =
    pokeEntry(
      dut,
      age,
      valid = true,
      complete = false,
      executionClass = ExecutionClass.Memory,
      memoryKind = MemoryOperationKind.Load,
      index = age,
      operandsReady = true,
      base = 0x3000 + age * 0x100,
      offset = age * 4
    )

  behavior of "AetherCore v2 LoadQ2 capacity-opportunity seam"

  it should "report a legal Load blocked only by full two-slot capacity without launching it" in {
    simulate(new TinyLoadQueueIssue(64)) { dut =>
      clear(dut)
      readyLoad(dut, 0)
      dut.io.available.poke(false.B)
      dut.io.block.poke(false.B)

      dut.io.candidateValid.expect(true.B)
      dut.io.capacityBlocked.expect(true.B)
      dut.io.capacityOnlyBlocked.expect(true.B)
      dut.io.request.valid.expect(false.B)
      dut.io.preHead.expect(false.B)
    }
  }

  it should "distinguish global issue blocking from pure LoadQ capacity pressure" in {
    simulate(new TinyLoadQueueIssue(64)) { dut =>
      clear(dut)
      readyLoad(dut, 0)
      dut.io.available.poke(false.B)
      dut.io.block.poke(true.B)

      dut.io.candidateValid.expect(true.B)
      dut.io.capacityBlocked.expect(true.B)
      dut.io.capacityOnlyBlocked.expect(false.B)
      dut.io.request.valid.expect(false.B)
    }
  }

  it should "remain zero when no otherwise-legal Load candidate exists" in {
    simulate(new TinyLoadQueueIssue(64)) { dut =>
      clear(dut)
      pokeEntry(
        dut,
        age = 0,
        valid = true,
        complete = false,
        executionClass = ExecutionClass.Memory,
        memoryKind = MemoryOperationKind.Load,
        index = 0,
        operandsReady = false
      )
      dut.io.available.poke(false.B)

      dut.io.candidateValid.expect(false.B)
      dut.io.capacityBlocked.expect(false.B)
      dut.io.capacityOnlyBlocked.expect(false.B)
      dut.io.request.valid.expect(false.B)
    }
  }

  it should "preserve the existing launch path while capacity is available" in {
    simulate(new TinyLoadQueueIssue(64)) { dut =>
      clear(dut)
      readyLoad(dut, 0)
      dut.io.available.poke(true.B)

      dut.io.candidateValid.expect(true.B)
      dut.io.capacityBlocked.expect(false.B)
      dut.io.capacityOnlyBlocked.expect(false.B)
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(0.U)
    }
  }
}

class V2P8LoadQCapacityOpportunitySpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2P8LoadQCapacityOpportunityChecks
