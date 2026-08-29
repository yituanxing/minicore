package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

trait V2F1RobCommitChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private final case class Identity(
      index: BigInt,
      generation: BigInt,
      producerId: BigInt,
      producerGeneration: BigInt,
      valueId: BigInt,
      valueGeneration: BigInt
  )

  private def initializeF1(dut: TinyRobCommitBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.rs1Addr.poke(0.U)
    dut.io.rs2Addr.poke(0.U)
  }

  private def pokeDispatch(
      dut: TinyRobCommitBackend,
      pc: BigInt,
      rd: Int,
      writesRd: Boolean = true
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(ExecutionClass.Integer)
    dut.io.dispatch.bits.producesValue.poke(writesRd.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke("h002081b3".U)
    dut.io.dispatch.bits.decoded.rawInst.poke("h002081b3".U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(AluOp.Add)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.rs1.poke(1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(2.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(true.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(true.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(writesRd.B)
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
    dut.io.dispatch.bits.decoded.system.xret.poke(XRetOp.None)
    dut.io.dispatch.bits.decoded.ordering.poke(OrderingClass.Normal)
    dut.io.dispatch.bits.decoded.exception.valid.poke(false.B)
    dut.io.dispatch.bits.decoded.exception.cause.poke(0.U)
    dut.io.dispatch.bits.decoded.exception.value.poke(0.U)
  }

  private def allocate(
      dut: TinyRobCommitBackend,
      pc: BigInt,
      rd: Int,
      writesRd: Boolean = true
  ): Identity = {
    pokeDispatch(dut, pc, rd, writesRd)
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

  private def complete(
      dut: TinyRobCommitBackend,
      identity: Identity,
      value: BigInt,
      exception: Boolean = false,
      exceptionCause: BigInt = 0,
      exceptionValue: BigInt = 0,
      producerIdOverride: Option[BigInt] = None,
      valueIdOverride: Option[BigInt] = None
  ): Unit = {
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.bits.robToken.index.poke(identity.index.U)
    dut.io.completion.bits.robToken.generation.poke(identity.generation.U)
    dut.io.completion.bits.producerTag.id.poke(producerIdOverride.getOrElse(identity.producerId).U)
    dut.io.completion.bits.producerTag.generation.poke(identity.producerGeneration.U)
    dut.io.completion.bits.valueRef.id.poke(valueIdOverride.getOrElse(identity.valueId).U)
    dut.io.completion.bits.valueRef.generation.poke(identity.valueGeneration.U)
    dut.io.completion.bits.hasValue.poke(true.B)
    dut.io.completion.bits.value.poke(value.U)
    dut.io.completion.bits.branchValid.poke(false.B)
    dut.io.completion.bits.branchTaken.poke(false.B)
    dut.io.completion.bits.branchTarget.poke(0.U)
    dut.io.completion.bits.exception.valid.poke(exception.B)
    dut.io.completion.bits.exception.cause.poke(exceptionCause.U)
    dut.io.completion.bits.exception.value.poke(exceptionValue.U)
    dut.clock.step()
    dut.io.completion.valid.poke(false.B)
  }

  behavior of "AetherCore v2 F1 Tiny ROB + Commit"

  it should "backpressure at four entries and retire only a completed head" in {
    simulate(new TinyRobCommitBackend(64)) { dut =>
      initializeF1(dut)

      val ids = (0 until 4).map { index =>
        allocate(dut, BigInt("80000000", 16) + index * 4, rd = index + 1)
      }

      dut.io.occupancy.expect(4.U)
      dut.io.dispatch.ready.expect(false.B)

      complete(dut, ids(2), value = 33)
      dut.io.commit.valid.expect(false.B)
      dut.io.occupancy.expect(4.U)

      complete(dut, ids(0), value = 11)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.rd.expect(1.U)
      dut.io.commit.rdWrite.expect(true.B)
      dut.io.commit.rdData.expect(11.U)
      dut.clock.step()

      dut.io.occupancy.expect(3.U)
      dut.io.dispatch.ready.expect(true.B)
      dut.io.commit.valid.expect(false.B)

      val replacement = allocate(dut, BigInt("80000010", 16), rd = 5)
      replacement.index shouldBe ids(0).index
      replacement.generation should not be ids(0).generation
      dut.io.occupancy.expect(4.U)
      dut.io.dispatch.ready.expect(false.B)
    }
  }

  it should "change generation on circular reuse and reject a stale completion" in {
    simulate(new TinyRobCommitBackend(64)) { dut =>
      initializeF1(dut)

      var firstIdentity: Option[Identity] = None
      for (index <- 0 until 4) {
        val identity = allocate(dut, BigInt("81000000", 16) + index * 4, rd = index + 1)
        if (index == 0) firstIdentity = Some(identity)
        complete(dut, identity, value = index + 10)
        dut.io.commit.valid.expect(true.B)
        dut.clock.step()
        dut.io.occupancy.expect(0.U)
      }

      val old = firstIdentity.get
      val current = allocate(dut, BigInt("81000010", 16), rd = 7)
      current.index shouldBe old.index
      current.generation should not be old.generation

      complete(dut, old, value = 99)
      dut.io.commit.valid.expect(false.B)
      dut.io.occupancy.expect(1.U)

      complete(dut, current, value = 77)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.rd.expect(7.U)
      dut.io.commit.rdData.expect(77.U)
    }
  }

  it should "keep stale completions distinct past the former two-bit wrap window" in {
    simulate(new TinyRobCommitBackend(64)) { dut =>
      initializeF1(dut)

      var old: Option[Identity] = None
      for (index <- 0 until 16) {
        val identity = allocate(dut, BigInt("81100000", 16) + index * 4, rd = (index % 31) + 1)
        if (index == 0) old = Some(identity)
        complete(dut, identity, value = index + 1)
        dut.io.commit.valid.expect(true.B)
        dut.clock.step()
        dut.io.occupancy.expect(0.U)
      }

      // With the frozen F7 2-bit generation this allocation numerically aliases
      // the first lifetime (same slot, generation wrapped after four reuses).
      // A8 must retain a distinct lifetime across that old alias window.
      val stale = old.get
      val current = allocate(dut, BigInt("81100040", 16), rd = 30)
      current.index shouldBe stale.index
      current.generation should not be stale.generation
      current.producerGeneration should not be stale.producerGeneration
      current.valueGeneration should not be stale.valueGeneration

      complete(dut, stale, value = BigInt("dead", 16))
      dut.io.commit.valid.expect(false.B)
      dut.io.occupancy.expect(1.U)

      complete(dut, current, value = BigInt("1234", 16))
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.rd.expect(30.U)
      dut.io.commit.rdData.expect("h1234".U)
    }
  }

  it should "reject completion when dependency or value identity does not match" in {
    simulate(new TinyRobCommitBackend(64)) { dut =>
      initializeF1(dut)
      val identity = allocate(dut, BigInt("82000000", 16), rd = 8)

      complete(
        dut,
        identity,
        value = 88,
        producerIdOverride = Some((identity.producerId + 1) & 3)
      )
      dut.io.commit.valid.expect(false.B)

      complete(
        dut,
        identity,
        value = 88,
        valueIdOverride = Some((identity.valueId + 1) & 3)
      )
      dut.io.commit.valid.expect(false.B)

      complete(dut, identity, value = 88)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.rdData.expect(88.U)
    }
  }

  it should "suppress x0 and exception writes while preserving retirement semantics" in {
    simulate(new TinyRobCommitBackend(64)) { dut =>
      initializeF1(dut)
      dut.io.rs1Addr.poke(0.U)

      val x0 = allocate(dut, BigInt("83000000", 16), rd = 0)
      complete(dut, x0, value = 123)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.rd.expect(0.U)
      dut.io.commit.rdWrite.expect(false.B)
      dut.io.commit.exception.expect(false.B)
      dut.io.rs1Data.expect(0.U)
      dut.clock.step()

      dut.io.rs1Addr.poke(5.U)
      val trapped = allocate(dut, BigInt("83000004", 16), rd = 5)
      complete(
        dut,
        trapped,
        value = 55,
        exception = true,
        exceptionCause = 2,
        exceptionValue = BigInt("ffffffff", 16)
      )
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.rd.expect(5.U)
      dut.io.commit.rdWrite.expect(false.B)
      dut.io.commit.exception.expect(true.B)
      dut.io.commit.exceptionCause.expect(2.U)
      dut.io.commit.exceptionValue.expect("hffffffff".U)
      dut.io.rs1Data.expect(0.U)
      dut.clock.step()
      dut.io.rs1Data.expect(0.U)
    }
  }
}
