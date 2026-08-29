package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, BranchType}
import aethercore.core.v2._

/** Focused policy proof for what younger selective compute may bypass. */
trait V2A8SelectiveBarrierChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeEntry(
      dut: TinySelectiveComputeIssue,
      age: Int,
      valid: Boolean,
      executionClass: ExecutionClass.Type,
      ordering: OrderingClass.Type,
      index: Int,
      exception: Boolean = false,
      ready: Boolean = true
  ): Unit = {
    val entry = dut.io.window(age)
    entry.valid.poke(valid.B)
    entry.complete.poke(false.B)
    entry.dependenciesValid.poke(valid.B)
    entry.operandsReady.poke(ready.B)
    entry.uop.robToken.index.poke(index.U)
    entry.uop.robToken.generation.poke(0.U)
    entry.uop.producerTag.id.poke(index.U)
    entry.uop.producerTag.generation.poke(0.U)
    entry.uop.valueRef.id.poke(index.U)
    entry.uop.valueRef.generation.poke(0.U)
    entry.uop.executionClass.poke(executionClass)
    entry.uop.producesValue.poke(true.B)
    entry.uop.decoded.aluOp.poke(AluOp.Add)
    entry.uop.decoded.wordOp.poke(false.B)
    entry.uop.decoded.lhsSource.poke(OperandSourceKind.Rs1)
    entry.uop.decoded.rhsSource.poke(OperandSourceKind.Rs2)
    entry.uop.decoded.pc.poke((0x3000 + age * 4).U)
    entry.uop.decoded.instBytes.poke(4.U)
    entry.uop.decoded.immediate.poke(0.U)
    entry.uop.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    entry.uop.decoded.controlFlow.branchType.poke(BranchType.None)
    entry.uop.decoded.ordering.poke(ordering)
    entry.uop.decoded.exception.valid.poke(exception.B)
    entry.uop.decoded.exception.cause.poke(0.U)
    entry.uop.decoded.exception.value.poke(0.U)
    entry.rs1.ready.poke(ready.B)
    entry.rs1.value.poke(7.U)
    entry.rs1.producerTag.id.poke(0.U)
    entry.rs1.producerTag.generation.poke(0.U)
    entry.rs2.ready.poke(ready.B)
    entry.rs2.value.poke(8.U)
    entry.rs2.producerTag.id.poke(0.U)
    entry.rs2.producerTag.generation.poke(0.U)
  }

  private def initialize(dut: TinySelectiveComputeIssue): Unit = {
    dut.io.allocated.valid.poke(false.B)
    dut.io.block.poke(false.B)
    dut.io.availability.integer.poke(true.B)
    dut.io.availability.multiply.poke(true.B)
    dut.io.availability.divide.poke(true.B)
    dut.io.request.ready.poke(false.B)
    for (age <- 0 until TinyRobGeometry.Entries) {
      pokeEntry(dut, age, valid = false, ExecutionClass.Integer, OrderingClass.Normal, age)
    }
  }

  behavior of "AetherCore v2 A8 selective bypass boundaries"

  it should "stop at serializing or known-trap elders but bypass ordinary branch and memory" in {
    simulate(new TinySelectiveComputeIssue(32)) { dut =>
      initialize(dut)

      // A serializing System elder is an architectural boundary even when its
      // operands are ready and the younger Integer itself is side-effect free.
      pokeEntry(dut, 0, valid = true, ExecutionClass.System, OrderingClass.SerializeBoth, 0)
      pokeEntry(dut, 1, valid = true, ExecutionClass.Integer, OrderingClass.Normal, 1)
      dut.io.request.valid.expect(false.B)

      // An already-known exception is also a stop point; executing younger work
      // would only create doomed speculative traffic before precise recovery.
      pokeEntry(dut, 0, valid = true, ExecutionClass.Integer, OrderingClass.Normal, 0, exception = true)
      dut.io.request.valid.expect(false.B)

      // Ordinary head memory is allowed to overlap side-effect-free compute.
      pokeEntry(dut, 0, valid = true, ExecutionClass.Memory, OrderingClass.Normal, 0)
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(1.U)

      // The same is true for an unresolved ordinary branch; precise recovery
      // remains Commit/ROB-owned and will kill the younger lifetime if taken.
      pokeEntry(dut, 0, valid = true, ExecutionClass.Branch, OrderingClass.Normal, 0)
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(1.U)

      // Any explicit non-Normal ordering class closes the bypass prefix even if
      // the elder is not classified as System.
      pokeEntry(dut, 0, valid = true, ExecutionClass.Memory, OrderingClass.MemoryFence, 0)
      dut.io.request.valid.expect(false.B)
    }
  }
}
