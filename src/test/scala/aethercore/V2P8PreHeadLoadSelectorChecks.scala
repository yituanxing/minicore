package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

/** Focused policy proof for the conservative pre-head-load selector. */
trait V2P8PreHeadLoadSelectorChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeEntry(
      dut: TinySelectiveLoadIssue,
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
      offset: BigInt = 0,
      storeData: BigInt = 0
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
    entry.uop.decoded.usesRs2.poke((!isLoad).B)
    entry.uop.decoded.writesRd.poke(isLoad.B)
    entry.uop.decoded.immediate.poke(offset.U)
    entry.uop.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    entry.uop.decoded.controlFlow.branchType.poke(BranchType.None)
    entry.uop.decoded.memory.kind.poke(memoryKind)
    entry.uop.decoded.memory.size.poke(MemSize.Word)
    entry.uop.decoded.memory.unsigned.poke(false.B)
    entry.uop.decoded.memory.atomicOp.poke(if (memoryKind == MemoryOperationKind.Atomic) AtomicOp.Add else AtomicOp.None)
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
    entry.rs2.value.poke(storeData.U)
    entry.rs2.producerTag.id.poke(0.U)
    entry.rs2.producerTag.generation.poke(0.U)
  }

  private def clear(dut: TinySelectiveLoadIssue): Unit = {
    dut.io.allocated.valid.poke(false.B)
    dut.io.block.poke(false.B)
    dut.io.available.poke(true.B)
    dut.io.request.ready.poke(true.B)
    for (age <- 0 until TinyRobGeometry.Entries) {
      pokeEntry(dut, age, valid = false, complete = false,
        executionClass = ExecutionClass.Integer, memoryKind = MemoryOperationKind.None,
        index = age, operandsReady = false)
    }
  }

  behavior of "AetherCore v2 conservative pre-head load selector"

  it should "preserve exact-head Store/Atomic eligibility without marking it pre-head" in {
    simulate(new TinySelectiveLoadIssue(32)) { dut =>
      clear(dut)
      pokeEntry(dut, 0, valid = true, complete = false, ExecutionClass.Memory,
        MemoryOperationKind.Store, index = 0, operandsReady = true,
        base = 0x2000, storeData = 0x55)
      dut.io.request.valid.expect(true.B)
      dut.io.preHead.expect(false.B)
      dut.io.request.bits.robToken.index.expect(0.U)
      dut.io.request.bits.kind.expect(MemoryOperationKind.Store)
    }
  }

  it should "choose the oldest ready Normal Load behind only ordinary compute" in {
    simulate(new TinySelectiveLoadIssue(32)) { dut =>
      clear(dut)
      pokeEntry(dut, 0, valid = true, complete = false, ExecutionClass.MulDiv,
        MemoryOperationKind.None, index = 0, operandsReady = false)
      pokeEntry(dut, 1, valid = true, complete = false, ExecutionClass.Integer,
        MemoryOperationKind.None, index = 1, operandsReady = true)
      pokeEntry(dut, 2, valid = true, complete = false, ExecutionClass.Memory,
        MemoryOperationKind.Load, index = 2, operandsReady = true,
        base = 0x3000, offset = 12)
      pokeEntry(dut, 3, valid = true, complete = false, ExecutionClass.Memory,
        MemoryOperationKind.Load, index = 3, operandsReady = true,
        base = 0x4000, offset = 16)
      dut.io.request.valid.expect(true.B)
      dut.io.preHead.expect(true.B)
      dut.io.request.bits.robToken.index.expect(2.U)
      dut.io.request.bits.base.expect(0x3000.U)
      dut.io.request.bits.offset.expect(12.U)
      dut.clock.step()
      dut.io.request.valid.expect(false.B)
    }
  }

  it should "reject younger Store and Atomic operations" in {
    simulate(new TinySelectiveLoadIssue(32)) { dut =>
      clear(dut)
      pokeEntry(dut, 0, valid = true, complete = false, ExecutionClass.Integer,
        MemoryOperationKind.None, index = 0, operandsReady = true)
      pokeEntry(dut, 1, valid = true, complete = false, ExecutionClass.Memory,
        MemoryOperationKind.Store, index = 1, operandsReady = true)
      dut.io.request.valid.expect(false.B)
      pokeEntry(dut, 1, valid = true, complete = false, ExecutionClass.Memory,
        MemoryOperationKind.Atomic, index = 1, operandsReady = true)
      dut.io.request.valid.expect(false.B)
    }
  }

  it should "fail closed across Branch, System, Memory, ordering, and exception boundaries" in {
    simulate(new TinySelectiveLoadIssue(32)) { dut =>
      def candidateBehind(
          olderClass: ExecutionClass.Type,
          olderOrdering: OrderingClass.Type = OrderingClass.Normal,
          olderException: Boolean = false
      ): Unit = {
        clear(dut)
        pokeEntry(dut, 0, valid = true, complete = true, olderClass,
          if (olderClass == ExecutionClass.Memory) MemoryOperationKind.Load else MemoryOperationKind.None,
          index = 0, operandsReady = true, ordering = olderOrdering, exception = olderException)
        pokeEntry(dut, 1, valid = true, complete = false, ExecutionClass.Memory,
          MemoryOperationKind.Load, index = 1, operandsReady = true)
        dut.io.request.valid.expect(false.B)
      }
      candidateBehind(ExecutionClass.Branch)
      candidateBehind(ExecutionClass.System)
      candidateBehind(ExecutionClass.Memory)
      candidateBehind(ExecutionClass.Integer, OrderingClass.SerializeBefore)
      candidateBehind(ExecutionClass.Integer, olderException = true)
    }
  }

  it should "remain silent while the LSU resource is unavailable" in {
    simulate(new TinySelectiveLoadIssue(32)) { dut =>
      clear(dut)
      pokeEntry(dut, 0, valid = true, complete = false, ExecutionClass.Integer,
        MemoryOperationKind.None, index = 0, operandsReady = true)
      pokeEntry(dut, 1, valid = true, complete = false, ExecutionClass.Memory,
        MemoryOperationKind.Load, index = 1, operandsReady = true)
      dut.io.available.poke(false.B)
      dut.io.request.valid.expect(false.B)
      dut.io.preHead.expect(false.B)
    }
  }
}
