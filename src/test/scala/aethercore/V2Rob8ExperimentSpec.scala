package aethercore.core.v2

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}

/** Geometry-local proof for the isolated P8 ROB8/window8 experiment.
  *
  * This deliberately lives in core.v2 so it checks the production-private
  * TinyRobGeometry directly instead of mutating the frozen test-scope ROB4
  * mirror used by historical F1-F7 qualification.
  */
class V2Rob8ExperimentSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private final case class Identity(index: BigInt, generation: BigInt)

  private def initialize(dut: TinyRobCommitBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.rs1Addr.poke(0.U)
    dut.io.rs2Addr.poke(0.U)
  }

  private def pokeDispatch(dut: TinyRobCommitBackend, pc: BigInt, rd: Int): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(ExecutionClass.Integer)
    dut.io.dispatch.bits.producesValue.poke(true.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke("h002081b3".U)
    dut.io.dispatch.bits.decoded.rawInst.poke("h002081b3".U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(AluOp.Add)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.lhsSource.poke(OperandSourceKind.Rs1)
    dut.io.dispatch.bits.decoded.rhsSource.poke(OperandSourceKind.Rs2)
    dut.io.dispatch.bits.decoded.rs1.poke(1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(2.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(true.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(true.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(true.B)
    dut.io.dispatch.bits.decoded.immediate.poke(0.U)
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
  }

  private def allocate(dut: TinyRobCommitBackend, pc: BigInt, rd: Int): Identity = {
    pokeDispatch(dut, pc, rd)
    dut.io.dispatch.ready.expect(true.B)
    dut.io.allocated.valid.expect(true.B)
    val identity = Identity(
      dut.io.allocated.bits.robToken.index.peek().litValue,
      dut.io.allocated.bits.robToken.generation.peek().litValue
    )
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
    identity
  }

  private def complete(dut: TinyRobCommitBackend, id: Identity, value: BigInt): Unit = {
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.bits.robToken.index.poke(id.index.U)
    dut.io.completion.bits.robToken.generation.poke(id.generation.U)
    dut.io.completion.bits.producerTag.id.poke(id.index.U)
    dut.io.completion.bits.producerTag.generation.poke(id.generation.U)
    dut.io.completion.bits.valueRef.id.poke(id.index.U)
    dut.io.completion.bits.valueRef.generation.poke(id.generation.U)
    dut.io.completion.bits.hasValue.poke(true.B)
    dut.io.completion.bits.value.poke(value.U)
    dut.io.completion.bits.branchValid.poke(false.B)
    dut.io.completion.bits.branchTaken.poke(false.B)
    dut.io.completion.bits.branchTarget.poke(0.U)
    dut.io.completion.bits.exception.valid.poke(false.B)
    dut.io.completion.bits.exception.cause.poke(0.U)
    dut.io.completion.bits.exception.value.poke(0.U)
    dut.io.completion.bits.privileged.poke(0.U.asTypeOf(new PendingPrivilegedEffect(64)))
    dut.clock.step()
    dut.io.completion.valid.poke(false.B)
  }

  behavior of "P8 ROB8/window8 experiment geometry"

  it should "expose eight physical slots with three-bit lifetime indices" in {
    TinyRobGeometry.Entries shouldBe 8
    TinyRobGeometry.IndexBits shouldBe 3

    simulate(new TinySelectiveComputeIssue(64)) { dut =>
      dut.io.window.length shouldBe 8
    }
  }

  it should "fill all eight slots before backpressure and preserve generation on reuse" in {
    simulate(new TinyRobCommitBackend(64)) { dut =>
      initialize(dut)

      val ids = (0 until TinyRobGeometry.Entries).map { index =>
        val id = allocate(dut, BigInt("88000000", 16) + index * 4, rd = index + 1)
        id.index shouldBe index
        id
      }

      dut.io.occupancy.expect(8.U)
      dut.io.dispatch.ready.expect(false.B)

      // A younger completion must not retire around the still-incomplete head.
      complete(dut, ids.last, value = 88)
      dut.io.commit.valid.expect(false.B)
      dut.io.occupancy.expect(8.U)

      complete(dut, ids.head, value = 11)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.rd.expect(1.U)
      dut.io.commit.rdData.expect(11.U)
      dut.clock.step()

      dut.io.occupancy.expect(7.U)
      dut.io.dispatch.ready.expect(true.B)

      val replacement = allocate(dut, BigInt("88000020", 16), rd = 9)
      replacement.index shouldBe ids.head.index
      replacement.generation should not be ids.head.generation
      dut.io.occupancy.expect(8.U)
      dut.io.dispatch.ready.expect(false.B)
    }
  }
}
