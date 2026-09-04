package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

private class V2F2StaleRenameHarness extends Module {
  private val Xlen = 64
  private val IdentityBits = 2
  private val GenerationBits = 8

  val io = IO(new Bundle {
    val allocate = Input(Bool())
    val robIndex = Input(UInt(IdentityBits.W))
    val robGeneration = Input(UInt(GenerationBits.W))
    val producerId = Input(UInt(IdentityBits.W))
    val producerGeneration = Input(UInt(GenerationBits.W))
    val rd = Input(UInt(5.W))
    val rs1 = Input(UInt(5.W))
    val usesRs1 = Input(Bool())
    val producesValue = Input(Bool())
    val committedRs1 = Input(UInt(Xlen.W))

    val slot2Rs1Ready = Output(Bool())
    val slot2Rs1Value = Output(UInt(Xlen.W))
  })

  private val state = Module(new TinyDependencyState(Xlen))
  private val allocated =
    WireDefault(0.U.asTypeOf(new BackendUop(Xlen, IdentityBits, GenerationBits)))
  allocated.executionClass := ExecutionClass.Integer
  allocated.robToken.index := io.robIndex
  allocated.robToken.generation := io.robGeneration
  allocated.producerTag.id := io.producerId
  allocated.producerTag.generation := io.producerGeneration
  allocated.valueRef.id := io.robIndex
  allocated.valueRef.generation := io.robGeneration
  allocated.producesValue := io.producesValue
  allocated.decoded.rd := io.rd
  allocated.decoded.rs1 := io.rs1
  allocated.decoded.usesRs1 := io.usesRs1
  allocated.decoded.usesRs2 := false.B
  allocated.decoded.writesRd := io.producesValue

  state.io.allocate.valid := io.allocate
  state.io.allocate.bits := allocated
  state.io.committedRs1 := io.committedRs1
  state.io.committedRs2 := 0.U

  state.io.completion.valid := false.B
  state.io.completion.bits := 0.U.asTypeOf(
    new ExecutionResponse(Xlen, IdentityBits, GenerationBits)
  )
  state.io.recovery.valid := false.B
  state.io.recovery.bits := 0.U.asTypeOf(
    new ExecutionResponse(Xlen, IdentityBits, GenerationBits)
  )
  state.io.privilegedRecovery.valid := false.B
  state.io.privilegedRecovery.bits := 0.U.asTypeOf(
    new ExecutionResponse(Xlen, IdentityBits, GenerationBits)
  )
  state.io.retire.valid := false.B
  state.io.retire.bits := 0.U.asTypeOf(new RobRetirement(Xlen))
  state.io.head.valid := false.B
  state.io.head.bits := 0.U.asTypeOf(
    new BackendUop(Xlen, IdentityBits, GenerationBits)
  )

  io.slot2Rs1Ready := state.io.slotView(2).rs1.ready
  io.slot2Rs1Value := state.io.slotView(2).rs1.value
}

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
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
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

  it should "ignore a stale rename tag reused by a producer for another architectural register" in {
    simulate(new V2F2StaleRenameHarness) { dut =>
      dut.io.allocate.poke(false.B)
      dut.io.robIndex.poke(0.U)
      dut.io.robGeneration.poke(0.U)
      dut.io.producerId.poke(0.U)
      dut.io.producerGeneration.poke(37.U)
      dut.io.rd.poke(0.U)
      dut.io.rs1.poke(0.U)
      dut.io.usesRs1.poke(false.B)
      dut.io.producesValue.poke(false.B)
      dut.io.committedRs1.poke(555.U)

      // First lifetime installs x5 -> ProducerTag(0, 37).
      dut.io.allocate.poke(true.B)
      dut.io.robIndex.poke(0.U)
      dut.io.rd.poke(5.U)
      dut.io.producesValue.poke(true.B)
      dut.clock.step()

      // Reuse exactly the same bounded ProducerTag for a different rd.  This
      // models eventual generation wrap without executing hundreds of ROB
      // lifetimes just to reach the same identity numerically.
      dut.io.robIndex.poke(1.U)
      dut.io.rd.poke(6.U)
      dut.clock.step()

      // x5 still contains the old RAM payload, but the live producer now owns
      // x6. A new x5 consumer must therefore ignore the stale mapping and use
      // the committed architectural register-file value.
      dut.io.robIndex.poke(2.U)
      dut.io.producerId.poke(2.U)
      dut.io.producerGeneration.poke(9.U)
      dut.io.rd.poke(0.U)
      dut.io.rs1.poke(5.U)
      dut.io.usesRs1.poke(true.B)
      dut.io.producesValue.poke(false.B)
      dut.clock.step()
      dut.io.allocate.poke(false.B)

      dut.io.slot2Rs1Ready.expect(true.B)
      dut.io.slot2Rs1Value.expect(555.U)
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
