package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, BranchType, MachineExceptionCode}
import aethercore.core.v2._

/**
  * P8 contract for removing the deterministic registered-response bubble from
  * the head-only branch unit without weakening Decoupled lifetime semantics.
  */
class V2P8BranchResponseFlowThroughSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim {

  private def driveRequest(
      dut: V2BranchUnit,
      kind: ControlFlowKind.Type,
      branchType: BranchType.Type,
      pc: BigInt,
      lhs: BigInt,
      rhs: BigInt,
      immediate: BigInt,
      instBytes: Int,
      tokenIndex: Int = 1,
      tokenGeneration: Int = 7
  ): Unit = {
    dut.io.request.bits.robToken.index.poke(tokenIndex.U)
    dut.io.request.bits.robToken.generation.poke(tokenGeneration.U)
    dut.io.request.bits.producerTag.id.poke(tokenIndex.U)
    dut.io.request.bits.producerTag.generation.poke(tokenGeneration.U)
    dut.io.request.bits.valueRef.id.poke(tokenIndex.U)
    dut.io.request.bits.valueRef.generation.poke(tokenGeneration.U)
    dut.io.request.bits.executionClass.poke(ExecutionClass.Branch)
    dut.io.request.bits.aluOp.poke(AluOp.Add)
    dut.io.request.bits.wordOp.poke(false.B)
    dut.io.request.bits.controlFlowKind.poke(kind)
    dut.io.request.bits.branchType.poke(branchType)
    dut.io.request.bits.lhs.poke(lhs.U(64.W))
    dut.io.request.bits.rhs.poke(rhs.U(64.W))
    dut.io.request.bits.pc.poke(pc.U(64.W))
    dut.io.request.bits.instBytes.poke(instBytes.U)
    dut.io.request.bits.immediate.poke(immediate.U(64.W))
  }

  behavior of "AetherCore v2 P8 branch response flow-through"

  it should "publish an accepted branch response in the same cycle without a register bubble" in {
    simulate(new V2BranchUnit(64, hasCompressed = true)) { dut =>
      dut.io.response.ready.poke(true.B)
      dut.io.request.valid.poke(false.B)
      driveRequest(
        dut,
        ControlFlowKind.DirectJump,
        BranchType.None,
        pc = 0x1000,
        lhs = 0,
        rhs = 0,
        immediate = 0x20,
        instBytes = 4
      )

      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      // No clock edge has occurred: this is the performance contract.
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(1.U)
      dut.io.response.bits.robToken.generation.expect(7.U)
      dut.io.response.bits.hasValue.expect(true.B)
      dut.io.response.bits.value.expect(0x1004.U)
      dut.io.response.bits.branchValid.expect(true.B)
      dut.io.response.bits.branchTaken.expect(true.B)
      dut.io.response.bits.branchTarget.expect(0x1020.U)
      dut.io.response.bits.exception.valid.expect(false.B)

      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.response.valid.expect(false.B)
    }
  }

  it should "capture a same-cycle branch response exactly once when downstream backpressures" in {
    simulate(new V2BranchUnit(64, hasCompressed = true)) { dut =>
      dut.io.response.ready.poke(false.B)
      dut.io.request.valid.poke(false.B)
      driveRequest(
        dut,
        ControlFlowKind.Conditional,
        BranchType.Eq,
        pc = 0x2000,
        lhs = 0x55,
        rhs = 0x55,
        immediate = 0x40,
        instBytes = 4,
        tokenIndex = 2,
        tokenGeneration = 9
      )

      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(2.U)
      dut.io.response.bits.robToken.generation.expect(9.U)
      dut.io.response.bits.branchTaken.expect(true.B)
      dut.io.response.bits.branchTarget.expect(0x2040.U)

      // The request is accepted even though the response cannot leave. The
      // fresh response must become a held lifetime on this edge.
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.request.ready.expect(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(2.U)
      dut.io.response.bits.robToken.generation.expect(9.U)
      dut.io.response.bits.branchTaken.expect(true.B)
      dut.io.response.bits.branchTarget.expect(0x2040.U)

      dut.io.response.ready.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.clock.step()
      dut.io.response.valid.expect(false.B)
    }
  }

  it should "expose branch exceptions in the same cycle while preserving alignment semantics" in {
    simulate(new V2BranchUnit(64, hasCompressed = false)) { dut =>
      dut.io.response.ready.poke(true.B)
      dut.io.request.valid.poke(false.B)
      driveRequest(
        dut,
        ControlFlowKind.DirectJump,
        BranchType.None,
        pc = 0x3000,
        lhs = 0,
        rhs = 0,
        immediate = 2,
        instBytes = 4
      )

      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.branchTarget.expect(0x3002.U)
      dut.io.response.bits.exception.valid.expect(true.B)
      dut.io.response.bits.exception.cause.expect(MachineExceptionCode.InstructionAddressMisaligned.U)
      dut.io.response.bits.exception.value.expect(0x3002.U)
    }
  }
}
