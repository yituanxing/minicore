package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

trait V2F2DependencyChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private final case class F2Identity(
      index: BigInt,
      generation: BigInt,
      producerId: BigInt,
      producerGeneration: BigInt,
      valueId: BigInt,
      valueGeneration: BigInt
  )

  private def initializeF2(dut: TinyDependencyBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.completion.valid.poke(false.B)
  }

  private def pokeF2Dispatch(
      dut: TinyDependencyBackend,
      pc: BigInt,
      rd: Int,
      rs1: Int = 0,
      rs2: Int = 0,
      usesRs1: Boolean = false,
      usesRs2: Boolean = false,
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
    dut.io.dispatch.bits.decoded.rs1.poke(rs1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(rs2.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(usesRs1.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(usesRs2.B)
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

  private def allocateF2(
      dut: TinyDependencyBackend,
      pc: BigInt,
      rd: Int,
      rs1: Int = 0,
      rs2: Int = 0,
      usesRs1: Boolean = false,
      usesRs2: Boolean = false,
      writesRd: Boolean = true
  ): F2Identity = {
    pokeF2Dispatch(dut, pc, rd, rs1, rs2, usesRs1, usesRs2, writesRd)
    dut.io.dispatch.ready.expect(true.B)
    dut.io.allocated.valid.expect(true.B)
    val identity = F2Identity(
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

  private def pokeF2Completion(
      dut: TinyDependencyBackend,
      identity: F2Identity,
      value: BigInt,
      generationOverride: Option[BigInt] = None
  ): Unit = {
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.bits.robToken.index.poke(identity.index.U)
    dut.io.completion.bits.robToken.generation.poke(generationOverride.getOrElse(identity.generation).U)
    dut.io.completion.bits.producerTag.id.poke(identity.producerId.U)
    dut.io.completion.bits.producerTag.generation.poke(identity.producerGeneration.U)
    dut.io.completion.bits.valueRef.id.poke(identity.valueId.U)
    dut.io.completion.bits.valueRef.generation.poke(identity.valueGeneration.U)
    dut.io.completion.bits.hasValue.poke(true.B)
    dut.io.completion.bits.value.poke(value.U)
    dut.io.completion.bits.branchValid.poke(false.B)
    dut.io.completion.bits.branchTaken.poke(false.B)
    dut.io.completion.bits.branchTarget.poke(0.U)
    dut.io.completion.bits.exception.valid.poke(false.B)
    dut.io.completion.bits.exception.cause.poke(0.U)
    dut.io.completion.bits.exception.value.poke(0.U)
  }

  private def completeF2(
      dut: TinyDependencyBackend,
      identity: F2Identity,
      value: BigInt
  ): Unit = {
    pokeF2Completion(dut, identity, value)
    dut.clock.step()
    dut.io.completion.valid.poke(false.B)
  }

  behavior of "AetherCore v2 F2 dependency readiness"

  it should "capture a RAW dependency and wake it by ProducerTag at both XLENs" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new TinyDependencyBackend(xlen)) { dut =>
        initializeF2(dut)
        val producer = allocateF2(dut, BigInt("84000000", 16), rd = 5)
        allocateF2(
          dut,
          BigInt("84000004", 16),
          rd = 6,
          rs1 = 5,
          usesRs1 = true
        )

        completeF2(dut, producer, value = 55)
        dut.io.commit.valid.expect(true.B)
        dut.io.commit.rd.expect(5.U)
        dut.clock.step()

        dut.io.head.valid.expect(true.B)
        dut.io.head.bits.decoded.rd.expect(6.U)
        dut.io.headDependenciesValid.expect(true.B)
        dut.io.headRs1.ready.expect(true.B)
        dut.io.headRs1.value.expect(55.U)
        dut.io.headRs2.ready.expect(true.B)
        dut.io.headOperandsReady.expect(true.B)
      }
    }
  }

  it should "resolve a consumer allocated on the same cycle as an accepted completion" in {
    simulate(new TinyDependencyBackend(64)) { dut =>
      initializeF2(dut)
      val producer = allocateF2(dut, BigInt("85000000", 16), rd = 7)

      pokeF2Dispatch(
        dut,
        BigInt("85000004", 16),
        rd = 8,
        rs1 = 7,
        usesRs1 = true
      )
      pokeF2Completion(dut, producer, value = 77)
      dut.io.allocated.valid.expect(true.B)
      dut.clock.step()
      dut.io.dispatch.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)

      dut.io.commit.valid.expect(true.B)
      dut.clock.step()

      dut.io.head.bits.decoded.rd.expect(8.U)
      dut.io.headDependenciesValid.expect(true.B)
      dut.io.headRs1.ready.expect(true.B)
      dut.io.headRs1.value.expect(77.U)
    }
  }

  it should "retain a completed producer value until retirement for later dispatch" in {
    simulate(new TinyDependencyBackend(64)) { dut =>
      initializeF2(dut)
      val producer = allocateF2(dut, BigInt("86000000", 16), rd = 9)
      completeF2(dut, producer, value = 99)
      dut.io.commit.valid.expect(true.B)

      allocateF2(
        dut,
        BigInt("86000004", 16),
        rd = 10,
        rs1 = 9,
        usesRs1 = true
      )

      dut.io.head.bits.decoded.rd.expect(10.U)
      dut.io.headDependenciesValid.expect(true.B)
      dut.io.headRs1.ready.expect(true.B)
      dut.io.headRs1.value.expect(99.U)
    }
  }

  it should "preserve a younger WAW mapping when the older writer retires" in {
    simulate(new TinyDependencyBackend(64)) { dut =>
      initializeF2(dut)
      val older = allocateF2(dut, BigInt("87000000", 16), rd = 11)
      val younger = allocateF2(dut, BigInt("87000004", 16), rd = 11)

      completeF2(dut, older, value = 111)
      dut.io.commit.valid.expect(true.B)
      dut.clock.step()
      dut.io.head.bits.decoded.rd.expect(11.U)

      allocateF2(
        dut,
        BigInt("87000008", 16),
        rd = 12,
        rs1 = 11,
        usesRs1 = true
      )

      completeF2(dut, younger, value = 222)
      dut.io.commit.valid.expect(true.B)
      dut.clock.step()

      dut.io.head.bits.decoded.rd.expect(12.U)
      dut.io.headDependenciesValid.expect(true.B)
      dut.io.headRs1.ready.expect(true.B)
      dut.io.headRs1.value.expect(222.U)
    }
  }

  it should "fall back to committed RF state after the latest producer retires" in {
    simulate(new TinyDependencyBackend(64)) { dut =>
      initializeF2(dut)
      val producer = allocateF2(dut, BigInt("88000000", 16), rd = 13)
      completeF2(dut, producer, value = 1313)
      dut.io.commit.valid.expect(true.B)
      dut.clock.step()
      dut.io.head.valid.expect(false.B)

      allocateF2(
        dut,
        BigInt("88000004", 16),
        rd = 14,
        rs1 = 13,
        usesRs1 = true
      )

      dut.io.head.bits.decoded.rd.expect(14.U)
      dut.io.headDependenciesValid.expect(true.B)
      dut.io.headRs1.ready.expect(true.B)
      dut.io.headRs1.value.expect(1313.U)
    }
  }

  it should "let ROB validation reject a stale completion before dependency wakeup" in {
    simulate(new TinyDependencyBackend(64)) { dut =>
      initializeF2(dut)
      val producer = allocateF2(dut, BigInt("89000000", 16), rd = 15)
      allocateF2(
        dut,
        BigInt("89000004", 16),
        rd = 16,
        rs1 = 15,
        usesRs1 = true
      )

      pokeF2Completion(
        dut,
        producer,
        value = 1515,
        generationOverride = Some((producer.generation + 1) & 3)
      )
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.commit.valid.expect(false.B)

      completeF2(dut, producer, value = 1515)
      dut.io.commit.valid.expect(true.B)
      dut.clock.step()

      dut.io.head.bits.decoded.rd.expect(16.U)
      dut.io.headRs1.ready.expect(true.B)
      dut.io.headRs1.value.expect(1515.U)
    }
  }

  it should "treat x0 and unused sources as immediately ready zero values" in {
    simulate(new TinyDependencyBackend(64)) { dut =>
      initializeF2(dut)
      allocateF2(
        dut,
        BigInt("8a000000", 16),
        rd = 17,
        rs1 = 0,
        rs2 = 31,
        usesRs1 = true,
        usesRs2 = false
      )

      dut.io.headDependenciesValid.expect(true.B)
      dut.io.headRs1.ready.expect(true.B)
      dut.io.headRs1.value.expect(0.U)
      dut.io.headRs2.ready.expect(true.B)
      dut.io.headRs2.value.expect(0.U)
      dut.io.headOperandsReady.expect(true.B)
    }
  }
}
