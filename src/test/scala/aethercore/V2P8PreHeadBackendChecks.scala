package aethercore

import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2._
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** End-to-end proof that the production candidate launches conservative younger
  * Loads while PMA and exact-head state still control externalization.
  */
trait V2P8PreHeadBackendChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val config = CoreProfiles.rv32imasuSv32PmpSoftware

  private def initialize(dut: TinyPreHeadMemoryBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.time.foreach(_.poke(0.U))
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
    dut.io.pteFault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(false.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(true.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    // Hold the physical request at the public seam so timing is observable.
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def pokeDispatch(
      dut: TinyPreHeadMemoryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      aluOp: AluOp.Type,
      rd: Int,
      immediate: BigInt,
      memoryKind: MemoryOperationKind.Type = MemoryOperationKind.None
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke((rd != 0).B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke(0x13.U)
    dut.io.dispatch.bits.decoded.rawInst.poke(0x13.U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(aluOp)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.lhsSource.poke(OperandSourceKind.Zero)
    dut.io.dispatch.bits.decoded.rhsSource.poke(OperandSourceKind.Immediate)
    dut.io.dispatch.bits.decoded.rs1.poke(0.U)
    dut.io.dispatch.bits.decoded.rs2.poke(0.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(false.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(false.B)
    dut.io.dispatch.bits.decoded.writesRd.poke((rd != 0).B)
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(BranchType.None)
    dut.io.dispatch.bits.decoded.memory.kind.poke(memoryKind)
    dut.io.dispatch.bits.decoded.memory.size.poke(MemSize.Word)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(true.B)
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

  private def dispatch(dut: TinyPreHeadMemoryBackend)(poke: => Unit): Unit = {
    poke
    var cycles = 0
    while (!dut.io.dispatch.ready.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    withClue("dispatch never became ready: ") {
      dut.io.dispatch.ready.peek().litToBoolean shouldBe true
    }
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def dispatchDivThenLoad(dut: TinyPreHeadMemoryBackend, basePc: BigInt): Unit = {
    // Iterative DIVU keeps age0 live long enough to expose the age1 Load.
    dispatch(dut) {
      pokeDispatch(dut, basePc, ExecutionClass.MulDiv, AluOp.Divu,
        rd = 1, immediate = 7)
    }
    dispatch(dut) {
      pokeDispatch(dut, basePc + 4, ExecutionClass.Memory, AluOp.Add,
        rd = 2, immediate = 0x1000, memoryKind = MemoryOperationKind.Load)
    }
  }

  behavior of "AetherCore v2 production pre-head Load backend"

  it should "externalize a replay-safe younger Load before the older DIV retires" in {
    simulate(new TinyPreHeadMemoryBackend(config, PageTableGeometry.Sv32)) { dut =>
      initialize(dut)
      dispatchDivThenLoad(dut, BigInt("80020000", 16))

      var sawRequest = false
      var cycles = 0
      while (!sawRequest && cycles < 16) {
        dut.io.commit.valid.expect(false.B)
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          dut.io.memoryRequest.bits.op.expect(aethercore.memory.AetherMemOp.Read)
          dut.io.memoryRequest.bits.paddr.expect(0x1000.U)
          sawRequest = true
        }
        if (!sawRequest) dut.clock.step()
        cycles += 1
      }
      withClue("younger replay-safe Load never reached memory before DIV retirement: ") {
        sawRequest shouldBe true
      }
      dut.io.lsuBusy.expect(true.B)
    }
  }

  it should "hold a side-effecting younger Load until the exact-head boundary" in {
    simulate(new TinyPreHeadMemoryBackend(config, PageTableGeometry.Sv32)) { dut =>
      initialize(dut)
      dut.io.resolvedAttributes.cacheable.poke(false.B)
      dut.io.resolvedAttributes.idempotent.poke(false.B)
      dut.io.resolvedAttributes.sideEffecting.poke(true.B)
      dut.io.resolvedAttributes.ordered.poke(true.B)
      dispatchDivThenLoad(dut, BigInt("80021000", 16))

      var sawOlderCommit = false
      var sawRequest = false
      var cycles = 0
      while (!sawRequest && cycles < 128) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          withClue("side-effecting Load externalized before older DIV retired: ") {
            sawOlderCommit shouldBe true
          }
          dut.io.memoryRequest.bits.paddr.expect(0x1000.U)
          sawRequest = true
        }
        if (dut.io.commit.valid.peek().litToBoolean) {
          dut.io.commit.pc.expect(BigInt("80021000", 16).U)
          sawOlderCommit = true
        }
        if (!sawRequest) dut.clock.step()
        cycles += 1
      }
      withClue("older DIV never retired: ") { sawOlderCommit shouldBe true }
      withClue("held side-effecting Load did not release at exact head: ") {
        sawRequest shouldBe true
      }
    }
  }
}
