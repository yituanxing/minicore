package aethercore.core.v2

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}

/** Direct P8.2 dependency-state release probe.
  *
  * This deliberately bypasses TinyRob, V2Commit and RegisterFile. It reproduces
  * the exact surviving-Branch / killed-WAW state, then compares the first
  * post-rebuild allocation with and without an unrelated older retirement.
  */
class V2P82DependencyStateReleaseSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private val IndexBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val Entries = TinyRobGeometry.Entries

  private def pokeUop(
      uop: BackendUop,
      xlen: Int,
      index: Int,
      generation: Int,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None
  ): Unit = {
    uop.decoded.pc.poke(pc.U)
    uop.decoded.inst.poke("h00000013".U)
    uop.decoded.rawInst.poke("h00000013".U)
    uop.decoded.instBytes.poke(4.U)
    uop.decoded.aluOp.poke(AluOp.Add)
    uop.decoded.wordOp.poke(false.B)
    uop.decoded.lhsSource.poke(if (usesRs1) OperandSourceKind.Rs1 else OperandSourceKind.Zero)
    uop.decoded.rhsSource.poke(OperandSourceKind.Zero)
    uop.decoded.rs1.poke(rs1.U)
    uop.decoded.rs2.poke(0.U)
    uop.decoded.rd.poke(rd.U)
    uop.decoded.usesRs1.poke(usesRs1.B)
    uop.decoded.usesRs2.poke(false.B)
    uop.decoded.writesRd.poke(writesRd.B)
    uop.decoded.immediate.poke(0.U)
    uop.decoded.controlFlow.kind.poke(controlFlowKind)
    uop.decoded.controlFlow.branchType.poke(BranchType.None)
    uop.decoded.memory.kind.poke(MemoryOperationKind.None)
    uop.decoded.memory.size.poke(MemSize.Word)
    uop.decoded.memory.unsigned.poke(false.B)
    uop.decoded.memory.atomicOp.poke(AtomicOp.None)
    uop.decoded.memory.acquire.poke(false.B)
    uop.decoded.memory.release.poke(false.B)
    uop.decoded.system.kind.poke(SystemOperationKind.None)
    uop.decoded.system.csrOp.poke(CsrOp.None)
    uop.decoded.system.csrAddress.poke(0.U)
    uop.decoded.system.csrUseImmediate.poke(false.B)
    uop.decoded.system.csrImmediate.poke(0.U)
    uop.decoded.system.xret.poke(XRetOp.None)
    uop.decoded.ordering.poke(OrderingClass.Normal)
    uop.decoded.exception.valid.poke(false.B)
    uop.decoded.exception.cause.poke(0.U)
    uop.decoded.exception.value.poke(0.U)
    uop.executionClass.poke(executionClass)
    uop.robToken.index.poke(index.U)
    uop.robToken.generation.poke(generation.U)
    uop.producerTag.id.poke(index.U)
    uop.producerTag.generation.poke(generation.U)
    uop.valueRef.id.poke(index.U)
    uop.valueRef.generation.poke(generation.U)
    uop.producesValue.poke(producesValue.B)
  }

  private def pokeResponse(
      response: ExecutionResponse,
      index: Int,
      generation: Int,
      hasValue: Boolean,
      value: BigInt,
      branch: Boolean = false,
      taken: Boolean = false,
      target: BigInt = 0
  ): Unit = {
    response.robToken.index.poke(index.U)
    response.robToken.generation.poke(generation.U)
    response.producerTag.id.poke(index.U)
    response.producerTag.generation.poke(generation.U)
    response.valueRef.id.poke(index.U)
    response.valueRef.generation.poke(generation.U)
    response.hasValue.poke(hasValue.B)
    response.value.poke(value.U)
    response.branchValid.poke(branch.B)
    response.branchTaken.poke(taken.B)
    response.branchTarget.poke(target.U)
    response.exception.valid.poke(false.B)
    response.exception.cause.poke(0.U)
    response.exception.value.poke(0.U)
    response.privileged.csrWriteValid.poke(false.B)
    response.privileged.csrAddress.poke(0.U)
    response.privileged.csrWriteData.poke(0.U)
    response.privileged.trapReturn.poke(false.B)
    response.privileged.trapReturnSupervisor.poke(false.B)
  }

  private def initialize(dut: TinyDependencyState, xlen: Int): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.recovery.valid.poke(false.B)
    dut.io.privilegedRecovery.valid.poke(false.B)
    dut.io.retire.valid.poke(false.B)
    dut.io.head.valid.poke(false.B)
    dut.io.committedRs1.poke(0.U)
    dut.io.committedRs2.poke(0.U)
    dut.io.recoverySurvivorCount.poke(0.U)
    for (age <- 0 until Entries) {
      dut.io.recoveryWindow(age).valid.poke(false.B)
      dut.io.recoveryWindow(age).complete.poke(false.B)
      pokeUop(dut.io.recoveryWindow(age).uop, xlen, age, 0, 0, ExecutionClass.None)
    }
  }

  private def allocate(
      dut: TinyDependencyState,
      xlen: Int,
      index: Int,
      generation: Int,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None
  ): Unit = {
    pokeUop(
      dut.io.allocate.bits,
      xlen,
      index,
      generation,
      pc,
      executionClass,
      rd,
      rs1,
      usesRs1,
      writesRd,
      producesValue,
      controlFlowKind
    )
    dut.io.allocate.valid.poke(true.B)
    dut.clock.step()
    dut.io.allocate.valid.poke(false.B)
  }

  private def driveWindow(
      dut: TinyDependencyState,
      xlen: Int,
      olderComplete: Boolean,
      branchComplete: Boolean
  ): Unit = {
    val base = BigInt("b1000000", 16)
    dut.io.recoveryWindow(0).valid.poke(true.B)
    dut.io.recoveryWindow(0).complete.poke(olderComplete.B)
    pokeUop(dut.io.recoveryWindow(0).uop, xlen, 0, 0, base, ExecutionClass.Integer)

    dut.io.recoveryWindow(1).valid.poke(true.B)
    dut.io.recoveryWindow(1).complete.poke(branchComplete.B)
    pokeUop(
      dut.io.recoveryWindow(1).uop,
      xlen,
      1,
      0,
      base + 4,
      ExecutionClass.Branch,
      rd = 5,
      writesRd = true,
      producesValue = true,
      controlFlowKind = ControlFlowKind.DirectJump
    )

    dut.io.recoveryWindow(2).valid.poke(true.B)
    dut.io.recoveryWindow(2).complete.poke(false.B)
    pokeUop(
      dut.io.recoveryWindow(2).uop,
      xlen,
      2,
      0,
      base + 8,
      ExecutionClass.Integer,
      rd = 5,
      writesRd = true,
      producesValue = true
    )

    dut.io.recoveryWindow(3).valid.poke(false.B)
    dut.io.recoveryWindow(3).complete.poke(false.B)
  }

  private def runRelease(retireOlder: Boolean): BigInt = {
    val xlen = 32
    val base = BigInt("b1000000", 16)
    val link = base + 8
    val target = base + 0x80
    var observed = BigInt(0)

    simulate(new TinyDependencyState(xlen)) { dut =>
      initialize(dut, xlen)

      allocate(dut, xlen, 0, 0, base, ExecutionClass.Integer)
      allocate(
        dut,
        xlen,
        1,
        0,
        base + 4,
        ExecutionClass.Branch,
        rd = 5,
        writesRd = true,
        producesValue = true,
        controlFlowKind = ControlFlowKind.DirectJump
      )
      allocate(
        dut,
        xlen,
        2,
        0,
        base + 8,
        ExecutionClass.Integer,
        rd = 5,
        writesRd = true,
        producesValue = true
      )

      driveWindow(dut, xlen, olderComplete = true, branchComplete = false)
      dut.io.recoverySurvivorCount.poke(2.U)
      pokeResponse(dut.io.completion.bits, 1, 0, hasValue = true, link, branch = true, taken = true, target)
      pokeResponse(dut.io.recovery.bits, 1, 0, hasValue = true, link, branch = true, taken = true, target)
      dut.io.completion.valid.poke(true.B)
      dut.io.recovery.valid.poke(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.recovery.valid.poke(false.B)
      dut.io.recoveryBusy.expect(true.B)

      driveWindow(dut, xlen, olderComplete = true, branchComplete = true)
      dut.clock.step()
      dut.io.recoveryBusy.expect(false.B)

      if (retireOlder) {
        dut.io.retire.valid.poke(true.B)
        pokeUop(dut.io.retire.bits.uop, xlen, 0, 0, base, ExecutionClass.Integer)
        dut.io.retire.bits.resultValid.poke(false.B)
        dut.io.retire.bits.result.poke(0.U)
        dut.io.retire.bits.exception.valid.poke(false.B)
        dut.io.retire.bits.exception.cause.poke(0.U)
        dut.io.retire.bits.exception.value.poke(0.U)
        dut.io.retire.bits.privileged.csrWriteValid.poke(false.B)
        dut.io.retire.bits.privileged.csrAddress.poke(0.U)
        dut.io.retire.bits.privileged.csrWriteData.poke(0.U)
        dut.io.retire.bits.privileged.trapReturn.poke(false.B)
        dut.io.retire.bits.privileged.trapReturnSupervisor.poke(false.B)
      }

      pokeUop(
        dut.io.allocate.bits,
        xlen,
        2,
        1,
        target,
        ExecutionClass.Integer,
        rd = 6,
        rs1 = 5,
        usesRs1 = true,
        writesRd = true,
        producesValue = true
      )
      dut.io.allocate.valid.poke(true.B)
      dut.clock.step()
      dut.io.allocate.valid.poke(false.B)
      dut.io.retire.valid.poke(false.B)

      dut.io.slotView(2).valid.expect(true.B)
      dut.io.slotView(2).rs1.producerTag.id.expect(1.U)
      dut.io.slotView(2).rs1.producerTag.generation.expect(0.U)
      dut.io.slotView(2).rs1.ready.expect(true.B)
      observed = dut.io.slotView(2).rs1.value.peek().litValue
    }

    observed
  }

  behavior of "AetherCore v2 P8.2 dependency-state recovery release"

  it should "retain the recovering Branch value across the direct release boundary" in {
    runRelease(retireOlder = false) shouldBe BigInt("b1000008", 16)
    runRelease(retireOlder = true) shouldBe BigInt("b1000008", 16)
  }
}
