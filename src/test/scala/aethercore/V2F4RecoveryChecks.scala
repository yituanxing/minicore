package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

trait V2F4RecoveryChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeRecoveryDispatch(
      dut: TinyRecoveryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      aluOp: AluOp.Type,
      lhsSource: OperandSourceKind.Type,
      rhsSource: OperandSourceKind.Type,
      rd: Int = 0,
      rs1: Int = 0,
      rs2: Int = 0,
      usesRs1: Boolean = false,
      usesRs2: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      immediate: BigInt = 0,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      branchType: BranchType.Type = BranchType.None
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke(producesValue.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke("h00000013".U)
    dut.io.dispatch.bits.decoded.rawInst.poke("h00000013".U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(aluOp)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.lhsSource.poke(lhsSource)
    dut.io.dispatch.bits.decoded.rhsSource.poke(rhsSource)
    dut.io.dispatch.bits.decoded.rs1.poke(rs1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(rs2.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(usesRs1.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(usesRs2.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(writesRd.B)
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(controlFlowKind)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(branchType)
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

  private def dispatchRecovery(
      dut: TinyRecoveryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      aluOp: AluOp.Type,
      lhsSource: OperandSourceKind.Type,
      rhsSource: OperandSourceKind.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      immediate: BigInt = 0,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      branchType: BranchType.Type = BranchType.None
  ): Unit = {
    pokeRecoveryDispatch(
      dut,
      pc,
      executionClass,
      aluOp,
      lhsSource,
      rhsSource,
      rd = rd,
      rs1 = rs1,
      usesRs1 = usesRs1,
      writesRd = writesRd,
      producesValue = producesValue,
      immediate = immediate,
      controlFlowKind = controlFlowKind,
      branchType = branchType
    )
    dut.io.dispatch.ready.expect(true.B)
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def pokeDependencyDispatch(
      dut: TinyDependencyBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      controlFlowKind: ControlFlowKind.Type,
      rd: Int = 0,
      writesRd: Boolean = false,
      producesValue: Boolean = false
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke(producesValue.B)
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
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(false.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(false.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(writesRd.B)
    dut.io.dispatch.bits.decoded.immediate.poke(0.U)
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

  behavior of "AetherCore v2 F4 validated branch recovery"

  it should "squash younger WAW state and redirect exactly once at both XLENs" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new TinyRecoveryBackend(xlen)) { dut =>
        dut.io.dispatch.valid.poke(false.B)

        val branchPc = BigInt("a0000000", 16)
        val target = branchPc + 0x40
        val link = branchPc + 4

        dispatchRecovery(
          dut,
          branchPc,
          ExecutionClass.Branch,
          AluOp.Add,
          OperandSourceKind.Zero,
          OperandSourceKind.Zero,
          rd = 5,
          writesRd = true,
          producesValue = true,
          immediate = 0x40,
          controlFlowKind = ControlFlowKind.DirectJump
        )

        // Allocate a younger WAW while the head branch issues.
        pokeRecoveryDispatch(
          dut,
          branchPc + 4,
          ExecutionClass.Integer,
          AluOp.Add,
          OperandSourceKind.Zero,
          OperandSourceKind.Immediate,
          rd = 5,
          writesRd = true,
          producesValue = true,
          immediate = 99
        )
        dut.io.dispatch.ready.expect(true.B)
        dut.clock.step()

        // The registered branch response now reaches the ROB. Recovery must
        // reject a simultaneous speculative dispatch and emit one typed redirect.
        pokeRecoveryDispatch(
          dut,
          branchPc + 8,
          ExecutionClass.Integer,
          AluOp.Add,
          OperandSourceKind.Zero,
          OperandSourceKind.Immediate,
          rd = 9,
          writesRd = true,
          producesValue = true,
          immediate = 7
        )
        dut.io.redirect.valid.expect(true.B)
        dut.io.redirect.bits.target.expect(target.U)
        dut.io.dispatch.ready.expect(false.B)
        dut.clock.step()

        dut.io.dispatch.valid.poke(false.B)
        dut.io.redirect.valid.expect(false.B)
        dut.io.occupancy.expect(1.U)
        dut.io.commit.valid.expect(true.B)
        dut.io.commit.rd.expect(5.U)
        dut.io.commit.rdData.expect(link.U)

        // Before the surviving branch retires, allocate a consumer. The F4
        // RAT rebuild must point it back at the branch producer, not the killed
        // younger WAW producer.
        pokeRecoveryDispatch(
          dut,
          branchPc + 0x40,
          ExecutionClass.Integer,
          AluOp.Add,
          OperandSourceKind.Rs1,
          OperandSourceKind.Immediate,
          rd = 6,
          rs1 = 5,
          usesRs1 = true,
          writesRd = true,
          producesValue = true,
          immediate = 0
        )
        dut.io.dispatch.ready.expect(true.B)
        dut.clock.step()
        dut.io.dispatch.valid.poke(false.B)

        var sawConsumer = false
        var cycles = 0
        while (!sawConsumer && cycles < 20) {
          if (dut.io.commit.valid.peek().litToBoolean &&
              dut.io.commit.rdWrite.peek().litToBoolean &&
              dut.io.commit.rd.peek().litValue == 6) {
            dut.io.commit.rdData.expect(link.U)
            sawConsumer = true
          }
          dut.clock.step()
          cycles += 1
        }
        sawConsumer shouldBe true
        dut.io.occupancy.expect(0.U)
      }
    }
  }

  it should "leave younger work intact for a not-taken conditional branch" in {
    simulate(new TinyRecoveryBackend(64)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val pc = BigInt("a1000000", 16)

      dispatchRecovery(
        dut,
        pc,
        ExecutionClass.Branch,
        AluOp.Add,
        OperandSourceKind.Zero,
        OperandSourceKind.Zero,
        controlFlowKind = ControlFlowKind.Conditional,
        branchType = BranchType.Ne,
        immediate = 0x20
      )
      pokeRecoveryDispatch(
        dut,
        pc + 4,
        ExecutionClass.Integer,
        AluOp.Add,
        OperandSourceKind.Zero,
        OperandSourceKind.Immediate,
        rd = 7,
        writesRd = true,
        producesValue = true,
        immediate = 77
      )
      dut.io.dispatch.ready.expect(true.B)
      dut.clock.step()
      dut.io.dispatch.valid.poke(false.B)

      dut.io.redirect.valid.expect(false.B)
      dut.clock.step()
      dut.io.occupancy.expect(1.U)

      var sawYounger = false
      var cycles = 0
      while (!sawYounger && cycles < 16) {
        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.rd.peek().litValue == 7) {
          dut.io.commit.rdData.expect(77.U)
          sawYounger = true
        }
        dut.clock.step()
        cycles += 1
      }
      sawYounger shouldBe true
    }
  }

  it should "reject stale branch completion before recovery side effects" in {
    simulate(new TinyDependencyBackend(64)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)

      pokeDependencyDispatch(
        dut,
        BigInt("a2000000", 16),
        ExecutionClass.Branch,
        ControlFlowKind.DirectJump
      )
      dut.io.dispatch.ready.expect(true.B)
      val tokenIndex = dut.io.allocated.bits.robToken.index.peek().litValue
      val tokenGeneration = dut.io.allocated.bits.robToken.generation.peek().litValue
      val producerId = dut.io.allocated.bits.producerTag.id.peek().litValue
      val producerGeneration = dut.io.allocated.bits.producerTag.generation.peek().litValue
      val valueId = dut.io.allocated.bits.valueRef.id.peek().litValue
      val valueGeneration = dut.io.allocated.bits.valueRef.generation.peek().litValue
      dut.clock.step()

      pokeDependencyDispatch(
        dut,
        BigInt("a2000004", 16),
        ExecutionClass.Integer,
        ControlFlowKind.None,
        rd = 3,
        writesRd = true,
        producesValue = true
      )
      dut.io.dispatch.ready.expect(true.B)
      dut.clock.step()
      dut.io.dispatch.valid.poke(false.B)
      dut.io.occupancy.expect(2.U)

      dut.io.completion.valid.poke(true.B)
      dut.io.completion.bits.robToken.index.poke(tokenIndex.U)
      dut.io.completion.bits.robToken.generation.poke(((tokenGeneration + 1) & 3).U)
      dut.io.completion.bits.producerTag.id.poke(producerId.U)
      dut.io.completion.bits.producerTag.generation.poke(producerGeneration.U)
      dut.io.completion.bits.valueRef.id.poke(valueId.U)
      dut.io.completion.bits.valueRef.generation.poke(valueGeneration.U)
      dut.io.completion.bits.hasValue.poke(false.B)
      dut.io.completion.bits.value.poke(0.U)
      dut.io.completion.bits.branchValid.poke(true.B)
      dut.io.completion.bits.branchTaken.poke(true.B)
      dut.io.completion.bits.branchTarget.poke("ha2000040".U)
      dut.io.completion.bits.exception.valid.poke(false.B)
      dut.io.completion.bits.exception.cause.poke(0.U)
      dut.io.completion.bits.exception.value.poke(0.U)

      dut.io.acceptedRecovery.valid.expect(false.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.occupancy.expect(2.U)
    }
  }

  it should "keep exceptional branch completion out of normal recovery" in {
    simulate(new TinyDependencyBackend(32)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)

      pokeDependencyDispatch(
        dut,
        BigInt("a3000000", 16),
        ExecutionClass.Branch,
        ControlFlowKind.DirectJump
      )
      val tokenIndex = dut.io.allocated.bits.robToken.index.peek().litValue
      val tokenGeneration = dut.io.allocated.bits.robToken.generation.peek().litValue
      val producerId = dut.io.allocated.bits.producerTag.id.peek().litValue
      val producerGeneration = dut.io.allocated.bits.producerTag.generation.peek().litValue
      val valueId = dut.io.allocated.bits.valueRef.id.peek().litValue
      val valueGeneration = dut.io.allocated.bits.valueRef.generation.peek().litValue
      dut.clock.step()
      dut.io.dispatch.valid.poke(false.B)

      dut.io.completion.valid.poke(true.B)
      dut.io.completion.bits.robToken.index.poke(tokenIndex.U)
      dut.io.completion.bits.robToken.generation.poke(tokenGeneration.U)
      dut.io.completion.bits.producerTag.id.poke(producerId.U)
      dut.io.completion.bits.producerTag.generation.poke(producerGeneration.U)
      dut.io.completion.bits.valueRef.id.poke(valueId.U)
      dut.io.completion.bits.valueRef.generation.poke(valueGeneration.U)
      dut.io.completion.bits.hasValue.poke(false.B)
      dut.io.completion.bits.value.poke(0.U)
      dut.io.completion.bits.branchValid.poke(true.B)
      dut.io.completion.bits.branchTaken.poke(true.B)
      dut.io.completion.bits.branchTarget.poke("ha3000002".U)
      dut.io.completion.bits.exception.valid.poke(true.B)
      dut.io.completion.bits.exception.cause.poke(0.U)
      dut.io.completion.bits.exception.value.poke("ha3000002".U)

      dut.io.acceptedRecovery.valid.expect(false.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.occupancy.expect(1.U)
      dut.io.commit.exception.expect(true.B)
    }
  }
}
