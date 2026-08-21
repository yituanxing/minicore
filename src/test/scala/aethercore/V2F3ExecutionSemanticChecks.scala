package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, BranchType}
import aethercore.core.v2._

trait V2F3ExecutionSemanticChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeRequest(
      bits: ExecutionRequest,
      executionClass: ExecutionClass.Type,
      op: AluOp.Type,
      lhs: BigInt,
      rhs: BigInt,
      wordOp: Boolean = false,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None,
      branchType: BranchType.Type = BranchType.None,
      pc: BigInt = 0,
      immediate: BigInt = 0,
      instBytes: Int = 4
  ): Unit = {
    bits.robToken.index.poke(0.U)
    bits.robToken.generation.poke(0.U)
    bits.producerTag.id.poke(0.U)
    bits.producerTag.generation.poke(0.U)
    bits.valueRef.id.poke(0.U)
    bits.valueRef.generation.poke(0.U)
    bits.executionClass.poke(executionClass)
    bits.aluOp.poke(op)
    bits.wordOp.poke(wordOp.B)
    bits.controlFlowKind.poke(controlFlowKind)
    bits.branchType.poke(branchType)
    bits.lhs.poke(lhs.U(64.W))
    bits.rhs.poke(rhs.U(64.W))
    bits.pc.poke(pc.U(64.W))
    bits.instBytes.poke(instBytes.U)
    bits.immediate.poke(immediate.U(64.W))
  }

  behavior of "AetherCore v2 F3 execution semantic parity"

  it should "preserve simple integer and RV64W arithmetic semantics" in {
    simulate(new V2IntegerUnit(64)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)

      def run(op: AluOp.Type, lhs: BigInt, rhs: BigInt, wordOp: Boolean, expected: BigInt): Unit = {
        pokeRequest(dut.io.request.bits, ExecutionClass.Integer, op, lhs, rhs, wordOp)
        dut.io.request.valid.poke(true.B)
        dut.io.request.ready.expect(true.B)
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.value.expect(expected.U(64.W))
        dut.clock.step()
      }

      run(AluOp.Add, 7, 5, false, 12)
      run(AluOp.Sub, 7, 5, false, 2)
      run(
        AluOp.Sra,
        BigInt("0000000080000000", 16),
        1,
        true,
        BigInt("ffffffffc0000000", 16)
      )
      run(
        AluOp.Slt,
        BigInt("ffffffffffffffff", 16),
        1,
        false,
        1
      )
      run(
        AluOp.Sltu,
        BigInt("ffffffffffffffff", 16),
        1,
        false,
        0
      )
    }
  }

  it should "preserve low and high multiply variants plus MULW" in {
    simulate(new V2MulUnit(64)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)

      def run(op: AluOp.Type, lhs: BigInt, rhs: BigInt, wordOp: Boolean, expected: BigInt): Unit = {
        pokeRequest(dut.io.request.bits, ExecutionClass.MulDiv, op, lhs, rhs, wordOp)
        dut.io.request.valid.poke(true.B)
        dut.io.request.ready.expect(true.B)
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.value.expect(expected.U(64.W))
        dut.clock.step()
      }

      run(
        AluOp.Mul,
        BigInt("ffffffffffffffff", 16),
        3,
        false,
        BigInt("fffffffffffffffd", 16)
      )
      run(
        AluOp.Mulh,
        BigInt("fffffffffffffffe", 16),
        BigInt("8000000000000000", 16),
        false,
        1
      )
      run(
        AluOp.Mulhsu,
        BigInt("fffffffffffffffe", 16),
        BigInt("8000000000000000", 16),
        false,
        BigInt("ffffffffffffffff", 16)
      )
      run(
        AluOp.Mulhu,
        BigInt("ffffffffffffffff", 16),
        2,
        false,
        1
      )
      run(
        AluOp.Mul,
        BigInt("0000000040000000", 16),
        2,
        true,
        BigInt("ffffffff80000000", 16)
      )
    }
  }

  it should "preserve signed unsigned quotient and remainder edge semantics" in {
    simulate(new V2IterativeDivider(64)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)

      def run(op: AluOp.Type, lhs: BigInt, rhs: BigInt, wordOp: Boolean, expected: BigInt): Unit = {
        pokeRequest(dut.io.request.bits, ExecutionClass.MulDiv, op, lhs, rhs, wordOp)
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
        BigInt("ffffffffffffffec", 16),
        3,
        false,
        BigInt("fffffffffffffffa", 16)
      )
      run(
        AluOp.Rem,
        BigInt("ffffffffffffffec", 16),
        3,
        false,
        BigInt("fffffffffffffffe", 16)
      )
      run(
        AluOp.Divu,
        BigInt("ffffffffffffffff", 16),
        2,
        false,
        BigInt("7fffffffffffffff", 16)
      )
      run(
        AluOp.Remu,
        BigInt("ffffffffffffffff", 16),
        2,
        false,
        1
      )
      run(
        AluOp.Rem,
        BigInt("8000000000000005", 16),
        0,
        false,
        BigInt("8000000000000005", 16)
      )
      run(
        AluOp.Rem,
        BigInt("8000000000000000", 16),
        BigInt("ffffffffffffffff", 16),
        false,
        0
      )
      run(
        AluOp.Remu,
        BigInt("0000000080000001", 16),
        0,
        true,
        BigInt("ffffffff80000001", 16)
      )
    }
  }

  it should "trap misalignment only for a taken conditional control transfer" in {
    simulate(new V2BranchUnit(64, hasCompressed = false)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)

      def run(
          lhs: BigInt,
          rhs: BigInt,
          immediate: BigInt,
          expectedTaken: Boolean,
          expectedException: Boolean
      ): Unit = {
        pokeRequest(
          dut.io.request.bits,
          ExecutionClass.Branch,
          AluOp.Add,
          lhs,
          rhs,
          controlFlowKind = ControlFlowKind.Conditional,
          branchType = BranchType.Eq,
          pc = BigInt("1000", 16),
          immediate = immediate
        )
        dut.io.request.valid.poke(true.B)
        dut.io.request.ready.expect(true.B)
        dut.clock.step()
        dut.io.request.valid.poke(false.B)

        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.branchValid.expect(true.B)
        dut.io.response.bits.branchTaken.expect(expectedTaken.B)
        dut.io.response.bits.hasValue.expect(false.B)
        dut.io.response.bits.exception.valid.expect(expectedException.B)
        dut.clock.step()
      }

      run(lhs = 1, rhs = 2, immediate = 2, expectedTaken = false, expectedException = false)
      run(lhs = 3, rhs = 3, immediate = 4, expectedTaken = true, expectedException = false)
      run(lhs = 5, rhs = 5, immediate = 2, expectedTaken = true, expectedException = true)
    }
  }
}
