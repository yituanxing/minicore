package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.v2._

trait V2F3ExecutionChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def initializeBackend(dut: TinyExecutionBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
  }

  private def pokeDispatch(
      dut: TinyExecutionBackend,
      pc: BigInt,
      rd: Int,
      executionClass: ExecutionClass.Type,
      aluOp: AluOp.Type,
      lhsSource: OperandSourceKind.Type,
      rhsSource: OperandSourceKind.Type,
      immediate: BigInt = 0,
      rs1: Int = 0,
      rs2: Int = 0,
      usesRs1: Boolean = false,
      usesRs2: Boolean = false,
      writesRd: Boolean = true,
      wordOp: Boolean = false,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      branchType: BranchType.Type = BranchType.None,
      instBytes: Int = 4
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke(writesRd.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke("h002081b3".U)
    dut.io.dispatch.bits.decoded.rawInst.poke("h002081b3".U)
    dut.io.dispatch.bits.decoded.instBytes.poke(instBytes.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(aluOp)
    dut.io.dispatch.bits.decoded.wordOp.poke(wordOp.B)
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

  private def dispatch(
      dut: TinyExecutionBackend,
      pc: BigInt,
      rd: Int,
      executionClass: ExecutionClass.Type,
      aluOp: AluOp.Type,
      lhsSource: OperandSourceKind.Type,
      rhsSource: OperandSourceKind.Type,
      immediate: BigInt = 0,
      rs1: Int = 0,
      rs2: Int = 0,
      usesRs1: Boolean = false,
      usesRs2: Boolean = false,
      writesRd: Boolean = true,
      wordOp: Boolean = false
  ): Unit = {
    pokeDispatch(
      dut,
      pc,
      rd,
      executionClass,
      aluOp,
      lhsSource,
      rhsSource,
      immediate,
      rs1,
      rs2,
      usesRs1,
      usesRs2,
      writesRd,
      wordOp
    )
    dut.io.dispatch.ready.expect(true.B)
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def collectRegisterCommits(
      dut: TinyExecutionBackend,
      wanted: Int,
      maxCycles: Int
  ): Seq[(Int, BigInt)] = {
    val commits = mutable.ArrayBuffer.empty[(Int, BigInt)]
    var cycles = 0
    while (commits.size < wanted && cycles < maxCycles) {
      if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.rdWrite.peek().litToBoolean) {
        commits += ((dut.io.commit.rd.peek().litValue.toInt, dut.io.commit.rdData.peek().litValue))
      }
      dut.clock.step()
      cycles += 1
    }
    commits.toSeq
  }

  behavior of "AetherCore v2 F3 oldest-only decoupled execution"

  it should "execute a RAW chain automatically through F2 wakeup at both XLENs" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new TinyExecutionBackend(xlen)) { dut =>
        initializeBackend(dut)
        dispatch(
          dut,
          BigInt("90000000", 16),
          rd = 5,
          ExecutionClass.Integer,
          AluOp.Add,
          OperandSourceKind.Zero,
          OperandSourceKind.Immediate,
          immediate = 41
        )
        dispatch(
          dut,
          BigInt("90000004", 16),
          rd = 6,
          ExecutionClass.Integer,
          AluOp.Add,
          OperandSourceKind.Rs1,
          OperandSourceKind.Immediate,
          immediate = 1,
          rs1 = 5,
          usesRs1 = true
        )

        val commits = collectRegisterCommits(dut, wanted = 2, maxCycles = 40)
        commits shouldBe Seq(5 -> BigInt(41), 6 -> BigInt(42))
        dut.io.occupancy.expect(0.U)
      }
    }
  }

  it should "materialize PC and immediate semantics without re-decoding an opcode" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new TinyExecutionBackend(xlen)) { dut =>
        initializeBackend(dut)
        dispatch(
          dut,
          BigInt("91000000", 16),
          rd = 7,
          ExecutionClass.Integer,
          AluOp.Add,
          OperandSourceKind.Pc,
          OperandSourceKind.Immediate,
          immediate = 0x24
        )

        val commits = collectRegisterCommits(dut, wanted = 1, maxCycles = 20)
        commits shouldBe Seq(7 -> (BigInt("91000000", 16) + 0x24))
      }
    }
  }

  it should "remember an issued RobToken until the head identity changes" in {
    simulate(new TinyOldestIssue(64)) { dut =>
      dut.io.head.valid.poke(true.B)
      dut.io.head.bits.executionClass.poke(ExecutionClass.Integer)
      dut.io.head.bits.robToken.index.poke(0.U)
      dut.io.head.bits.robToken.generation.poke(0.U)
      dut.io.head.bits.producerTag.id.poke(0.U)
      dut.io.head.bits.producerTag.generation.poke(0.U)
      dut.io.head.bits.valueRef.id.poke(0.U)
      dut.io.head.bits.valueRef.generation.poke(0.U)
      dut.io.head.bits.decoded.aluOp.poke(AluOp.Add)
      dut.io.head.bits.decoded.wordOp.poke(false.B)
      dut.io.head.bits.decoded.lhsSource.poke(OperandSourceKind.Zero)
      dut.io.head.bits.decoded.rhsSource.poke(OperandSourceKind.Immediate)
      dut.io.head.bits.decoded.pc.poke("h92000000".U)
      dut.io.head.bits.decoded.instBytes.poke(4.U)
      dut.io.head.bits.decoded.immediate.poke(1.U)
      dut.io.head.bits.decoded.controlFlow.kind.poke(ControlFlowKind.None)
      dut.io.head.bits.decoded.controlFlow.branchType.poke(BranchType.None)
      dut.io.headDependenciesValid.poke(true.B)
      dut.io.headOperandsReady.poke(true.B)
      dut.io.headRs1.ready.poke(true.B)
      dut.io.headRs1.value.poke(0.U)
      dut.io.headRs1.producerTag.id.poke(0.U)
      dut.io.headRs1.producerTag.generation.poke(0.U)
      dut.io.headRs2.ready.poke(true.B)
      dut.io.headRs2.value.poke(0.U)
      dut.io.headRs2.producerTag.id.poke(0.U)
      dut.io.headRs2.producerTag.generation.poke(0.U)
      dut.io.request.ready.poke(true.B)

      dut.io.request.valid.expect(true.B)
      dut.clock.step()
      for (_ <- 0 until 6) {
        dut.io.request.valid.expect(false.B)
        dut.clock.step()
      }

      dut.io.head.bits.robToken.index.poke(1.U)
      dut.io.head.bits.producerTag.id.poke(1.U)
      dut.io.head.bits.valueRef.id.poke(1.U)
      dut.io.request.valid.expect(true.B)
    }
  }

  it should "use a genuinely iterative divider and preserve RISC-V edge semantics" in {
    simulate(new V2IterativeDivider(32)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)

      dut.io.request.bits.robToken.index.poke(0.U)
      dut.io.request.bits.robToken.generation.poke(0.U)
      dut.io.request.bits.producerTag.id.poke(0.U)
      dut.io.request.bits.producerTag.generation.poke(0.U)
      dut.io.request.bits.valueRef.id.poke(0.U)
      dut.io.request.bits.valueRef.generation.poke(0.U)
      dut.io.request.bits.executionClass.poke(ExecutionClass.MulDiv)
      dut.io.request.bits.aluOp.poke(AluOp.Divu)
      dut.io.request.bits.wordOp.poke(false.B)
      dut.io.request.bits.controlFlowKind.poke(ControlFlowKind.None)
      dut.io.request.bits.branchType.poke(BranchType.None)
      dut.io.request.bits.lhs.poke(100.U)
      dut.io.request.bits.rhs.poke(7.U)
      dut.io.request.bits.pc.poke(0.U)
      dut.io.request.bits.instBytes.poke(4.U)
      dut.io.request.bits.immediate.poke(0.U)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.response.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step()
        cycles += 1
      }
      cycles should be > 8
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.value.expect(14.U)
    }

    simulate(new V2IterativeDivider(64)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)

      def run(op: AluOp.Type, lhs: BigInt, rhs: BigInt, wordOp: Boolean, expected: BigInt): Unit = {
        dut.io.request.bits.robToken.index.poke(0.U)
        dut.io.request.bits.robToken.generation.poke(0.U)
        dut.io.request.bits.producerTag.id.poke(0.U)
        dut.io.request.bits.producerTag.generation.poke(0.U)
        dut.io.request.bits.valueRef.id.poke(0.U)
        dut.io.request.bits.valueRef.generation.poke(0.U)
        dut.io.request.bits.executionClass.poke(ExecutionClass.MulDiv)
        dut.io.request.bits.aluOp.poke(op)
        dut.io.request.bits.wordOp.poke(wordOp.B)
        dut.io.request.bits.controlFlowKind.poke(ControlFlowKind.None)
        dut.io.request.bits.branchType.poke(BranchType.None)
        dut.io.request.bits.lhs.poke(lhs.U(64.W))
        dut.io.request.bits.rhs.poke(rhs.U(64.W))
        dut.io.request.bits.pc.poke(0.U)
        dut.io.request.bits.instBytes.poke(4.U)
        dut.io.request.bits.immediate.poke(0.U)
        dut.io.request.valid.poke(true.B)
        dut.io.request.ready.expect(true.B)
        dut.clock.step()
        dut.io.request.valid.poke(false.B)

        var cycles = 0
        while (!dut.io.response.valid.peek().litToBoolean && cycles < 72) {
          dut.clock.step()
          cycles += 1
        }
        cycles should be > 8
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.value.expect(expected.U(64.W))
        dut.clock.step()
      }

      run(
        AluOp.Div,
        BigInt("00000000fffffff9", 16),
        2,
        wordOp = true,
        BigInt("fffffffffffffffd", 16)
      )
      run(
        AluOp.Div,
        BigInt("fffffffffffffff9", 16),
        0,
        wordOp = false,
        BigInt("ffffffffffffffff", 16)
      )
      run(
        AluOp.Div,
        BigInt("8000000000000000", 16),
        BigInt("ffffffffffffffff", 16),
        wordOp = false,
        BigInt("8000000000000000", 16)
      )
    }
  }

  it should "preserve MULW sign extension behind the decoupled multiplier" in {
    simulate(new V2MulUnit(64)) { dut =>
      dut.io.response.ready.poke(true.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.robToken.index.poke(0.U)
      dut.io.request.bits.robToken.generation.poke(0.U)
      dut.io.request.bits.producerTag.id.poke(0.U)
      dut.io.request.bits.producerTag.generation.poke(0.U)
      dut.io.request.bits.valueRef.id.poke(0.U)
      dut.io.request.bits.valueRef.generation.poke(0.U)
      dut.io.request.bits.executionClass.poke(ExecutionClass.MulDiv)
      dut.io.request.bits.aluOp.poke(AluOp.Mul)
      dut.io.request.bits.wordOp.poke(true.B)
      dut.io.request.bits.controlFlowKind.poke(ControlFlowKind.None)
      dut.io.request.bits.branchType.poke(BranchType.None)
      dut.io.request.bits.lhs.poke("h0000000040000000".U)
      dut.io.request.bits.rhs.poke(2.U)
      dut.io.request.bits.pc.poke(0.U)
      dut.io.request.bits.instBytes.poke(4.U)
      dut.io.request.bits.immediate.poke(0.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.value.expect("hffffffff80000000".U)
    }
  }

  it should "compute compressed jump links, JALR masking and architectural alignment" in {
    simulate(new V2BranchUnit(32, hasCompressed = true)) { dut =>
      dut.io.response.ready.poke(true.B)

      def run(
          kind: ControlFlowKind.Type,
          pc: BigInt,
          lhs: BigInt,
          immediate: BigInt,
          instBytes: Int,
          expectedTarget: BigInt,
          expectedLink: BigInt
      ): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.robToken.index.poke(0.U)
        dut.io.request.bits.robToken.generation.poke(0.U)
        dut.io.request.bits.producerTag.id.poke(0.U)
        dut.io.request.bits.producerTag.generation.poke(0.U)
        dut.io.request.bits.valueRef.id.poke(0.U)
        dut.io.request.bits.valueRef.generation.poke(0.U)
        dut.io.request.bits.executionClass.poke(ExecutionClass.Branch)
        dut.io.request.bits.aluOp.poke(AluOp.Add)
        dut.io.request.bits.wordOp.poke(false.B)
        dut.io.request.bits.controlFlowKind.poke(kind)
        dut.io.request.bits.branchType.poke(BranchType.None)
        dut.io.request.bits.lhs.poke(lhs.U(32.W))
        dut.io.request.bits.rhs.poke(0.U)
        dut.io.request.bits.pc.poke(pc.U(32.W))
        dut.io.request.bits.instBytes.poke(instBytes.U)
        dut.io.request.bits.immediate.poke(immediate.U(32.W))
        dut.clock.step()
        dut.io.request.valid.poke(false.B)

        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.branchTaken.expect(true.B)
        dut.io.response.bits.branchTarget.expect(expectedTarget.U(32.W))
        dut.io.response.bits.value.expect(expectedLink.U(32.W))
        dut.io.response.bits.exception.valid.expect(false.B)
        dut.clock.step()
      }

      run(
        ControlFlowKind.DirectJump,
        pc = 0x1000,
        lhs = 0,
        immediate = 6,
        instBytes = 2,
        expectedTarget = 0x1006,
        expectedLink = 0x1002
      )
      run(
        ControlFlowKind.IndirectJump,
        pc = 0x2000,
        lhs = 0x1003,
        immediate = 0,
        instBytes = 4,
        expectedTarget = 0x1002,
        expectedLink = 0x2004
      )
    }

    simulate(new V2BranchUnit(32, hasCompressed = false)) { dut =>
      dut.io.response.ready.poke(true.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.robToken.index.poke(0.U)
      dut.io.request.bits.robToken.generation.poke(0.U)
      dut.io.request.bits.producerTag.id.poke(0.U)
      dut.io.request.bits.producerTag.generation.poke(0.U)
      dut.io.request.bits.valueRef.id.poke(0.U)
      dut.io.request.bits.valueRef.generation.poke(0.U)
      dut.io.request.bits.executionClass.poke(ExecutionClass.Branch)
      dut.io.request.bits.aluOp.poke(AluOp.Add)
      dut.io.request.bits.wordOp.poke(false.B)
      dut.io.request.bits.controlFlowKind.poke(ControlFlowKind.DirectJump)
      dut.io.request.bits.branchType.poke(BranchType.None)
      dut.io.request.bits.lhs.poke(0.U)
      dut.io.request.bits.rhs.poke(0.U)
      dut.io.request.bits.pc.poke("h1000".U)
      dut.io.request.bits.instBytes.poke(4.U)
      dut.io.request.bits.immediate.poke(2.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.branchTarget.expect("h1002".U)
      dut.io.response.bits.exception.valid.expect(true.B)
      dut.io.response.bits.exception.cause.expect(0.U)
      dut.io.response.bits.exception.value.expect("h1002".U)
    }
  }
}
