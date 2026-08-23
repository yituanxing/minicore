package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.config.{CoreConfig, CoreProfiles, PageTableGeometry}
import aethercore.core.v2._

/** Test-only top-level observation of actual selective-compute launches.
  *
  * ChiselSim intentionally exposes top-level IO rather than arbitrary child
  * hierarchy. Keep production TinyMemoryBackend unchanged and surface only the
  * accepted selective request here so the end-to-end proof observes a real fire,
  * not merely final in-order retirement.
  */
private class A8ObservableMemoryBackend(
    coreConfig: CoreConfig,
    geometry: PageTableGeometry
) extends TinyMemoryBackend(coreConfig, geometry) {
  val observedSelectiveIssue = IO(Output(new Bundle {
    val fire = Bool()
    val robIndex = UInt(TinyRobGeometry.IndexBits.W)
    val lhs = UInt(coreConfig.isa.xlen.W)
    val rhs = UInt(coreConfig.isa.xlen.W)
  }))

  observedSelectiveIssue.fire := selectiveIssue.io.request.fire
  observedSelectiveIssue.robIndex := selectiveIssue.io.request.bits.robToken.index
  observedSelectiveIssue.lhs := selectiveIssue.io.request.bits.lhs
  observedSelectiveIssue.rhs := selectiveIssue.io.request.bits.rhs
}

/** End-to-end A8 proof after selective compute is wired into TinyMemoryBackend. */
trait V2A8ProductionSelectiveIssueChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val config = CoreProfiles.rv32imasuSv32PmpSoftware

  private def initialize(dut: TinyMemoryBackend): Unit = {
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
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def pokeDispatch(
      dut: TinyMemoryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      op: AluOp.Type = AluOp.Add,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      immediate: BigInt = 0,
      memoryKind: MemoryOperationKind.Type = MemoryOperationKind.None,
      memorySize: MemSize.Type = MemSize.Word,
      memoryUnsigned: Boolean = false
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke((rd != 0).B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke(0x13.U)
    dut.io.dispatch.bits.decoded.rawInst.poke(0x13.U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(op)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.lhsSource.poke(
      if (usesRs1) OperandSourceKind.Rs1 else OperandSourceKind.Zero
    )
    dut.io.dispatch.bits.decoded.rhsSource.poke(OperandSourceKind.Immediate)
    dut.io.dispatch.bits.decoded.rs1.poke(rs1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(0.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(usesRs1.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(false.B)
    dut.io.dispatch.bits.decoded.writesRd.poke((rd != 0).B)
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(BranchType.None)
    dut.io.dispatch.bits.decoded.memory.kind.poke(memoryKind)
    dut.io.dispatch.bits.decoded.memory.size.poke(memorySize)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(memoryUnsigned.B)
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

  private def dispatch(dut: TinyMemoryBackend)(poke: => Unit): Unit = {
    poke
    var cycles = 0
    while (!dut.io.dispatch.ready.peek().litToBoolean && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    withClue("dispatch never became ready: ") {
      dut.io.dispatch.ready.peek().litToBoolean shouldBe true
    }
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def appendCommit(
      dut: TinyMemoryBackend,
      commits: scala.collection.mutable.ArrayBuffer[(BigInt, Int, BigInt)]
  ): Unit = {
    if (dut.io.commit.valid.peek().litToBoolean) {
      commits += ((
        dut.io.commit.pc.peek().litValue,
        dut.io.commit.rd.peek().litValue.toInt,
        dut.io.commit.rdData.peek().litValue
      ))
    }
  }

  private def collectCommits(
      dut: TinyMemoryBackend,
      count: Int,
      maxCycles: Int = 160
  ): Seq[(BigInt, Int, BigInt)] = {
    val commits = scala.collection.mutable.ArrayBuffer.empty[(BigInt, Int, BigInt)]
    var cycles = 0
    while (commits.size < count && cycles < maxCycles) {
      appendCommit(dut, commits)
      dut.clock.step()
      cycles += 1
    }
    withClue(s"expected $count commits but saw ${commits.size}: ") {
      commits.size shouldBe count
    }
    commits.toSeq
  }

  behavior of "AetherCore v2 A8 production selective issue"

  it should "bypass a blocked older compute consumer while preserving in-order Commit" in {
    simulate(new A8ObservableMemoryBackend(config, PageTableGeometry.Sv32)) { dut =>
      initialize(dut)
      val pPc = BigInt("80010000", 16)
      val cPc = pPc + 4
      val iPc = pPc + 8

      // P: long DIVU, writes x1. Zero/7 is intentionally simple but still uses
      // the real iterative divider. C waits on x1; I is independent.
      dispatch(dut) {
        pokeDispatch(dut, pPc, ExecutionClass.MulDiv, AluOp.Divu, rd = 1, immediate = 7)
      }
      dispatch(dut) {
        pokeDispatch(dut, cPc, ExecutionClass.Integer, rd = 2, rs1 = 1, usesRs1 = true, immediate = 1)
      }
      dispatch(dut) {
        pokeDispatch(dut, iPc, ExecutionClass.Integer, rd = 3, immediate = 33)
      }

      // P was launched while C/I were being allocated. C is still blocked by
      // P, so the real production selector must actually launch I next.
      var cycles = 0
      var sawIndependent = false
      while (!sawIndependent && cycles < 12) {
        if (dut.observedSelectiveIssue.fire.peek().litToBoolean &&
            dut.observedSelectiveIssue.robIndex.peek().litValue == 2) {
          sawIndependent = true
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("younger independent Integer never issued around blocked consumer: ") {
        sawIndependent shouldBe true
      }

      // Nothing can retire while the oldest iterative DIV is still running.
      dut.io.commit.valid.expect(false.B)

      // From here continuously observe accepted selective launches and Commit.
      // P may become retireable in the same cycle that C wakes, so collecting
      // only after observing C would miss a one-cycle architectural pulse.
      val commits = scala.collection.mutable.ArrayBuffer.empty[(BigInt, Int, BigInt)]
      var sawConsumer = false
      cycles = 0
      while ((commits.size < 3 || !sawConsumer) && cycles < 96) {
        if (dut.observedSelectiveIssue.fire.peek().litToBoolean &&
            dut.observedSelectiveIssue.robIndex.peek().litValue == 1) {
          dut.observedSelectiveIssue.lhs.expect(0.U)
          dut.observedSelectiveIssue.rhs.expect(1.U)
          sawConsumer = true
        }
        appendCommit(dut, commits)
        dut.clock.step()
        cycles += 1
      }

      withClue("woken older consumer never returned to selective issue: ") {
        sawConsumer shouldBe true
      }
      withClue(s"expected three ordered commits but saw ${commits.size}: ") {
        commits.size shouldBe 3
      }
      commits.map(_._1).toSeq shouldBe Seq(pPc, cPc, iPc)
      commits.map(_._2).toSeq shouldBe Seq(1, 2, 3)
      commits.map(_._3).toSeq shouldBe Seq(BigInt(0), BigInt(1), BigInt(33))
    }
  }

  it should "overlap younger compute after the head load has launched into the LSU" in {
    simulate(new A8ObservableMemoryBackend(config, PageTableGeometry.Sv32)) { dut =>
      initialize(dut)
      val loadPc = BigInt("80011000", 16)
      val intPc = loadPc + 4

      dispatch(dut) {
        pokeDispatch(
          dut,
          loadPc,
          ExecutionClass.Memory,
          rd = 1,
          immediate = 0x1000,
          memoryKind = MemoryOperationKind.Load,
          memorySize = MemSize.Word,
          memoryUnsigned = true
        )
      }
      dispatch(dut) {
        pokeDispatch(dut, intPc, ExecutionClass.Integer, rd = 2, immediate = 55)
      }

      // By now the exact-head load has already crossed the LSU request seam;
      // its physical AetherMem request may still be waiting. This distinction is
      // intentional: LSU launch gets first use of its cycle, then subsequent
      // cycles may overlap younger side-effect-free compute with translation or
      // memory latency.
      dut.io.lsuBusy.expect(true.B)
      dut.io.commit.valid.expect(false.B)

      var sawInteger = false
      var sawPhysicalRequest = false
      var txn = BigInt(0)
      var cycles = 0
      while ((!sawInteger || !sawPhysicalRequest) && cycles < 24) {
        if (dut.observedSelectiveIssue.fire.peek().litToBoolean &&
            dut.observedSelectiveIssue.robIndex.peek().litValue == 1) {
          sawInteger = true
        }
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          sawPhysicalRequest = true
          txn = dut.io.memoryRequest.bits.txnId.peek().litValue
        }
        dut.io.commit.valid.expect(false.B)
        dut.clock.step()
        cycles += 1
      }
      withClue("younger Integer did not overlap the launched head load: ") {
        sawInteger shouldBe true
      }
      withClue("head load did not reach the physical memory request seam: ") {
        sawPhysicalRequest shouldBe true
      }

      // The physical request was intentionally held by ready=false. Accept it
      // only after proving the younger compute had an overlap opportunity.
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.txnId.expect(txn.U)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      // Complete the oldest load. The already-completed younger Integer still
      // cannot retire before it.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(txn.U)
      dut.io.memoryResponse.bits.rdata.poke(0x12345678.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      val commits = collectCommits(dut, 2)
      commits.map(_._1) shouldBe Seq(loadPc, intPc)
      commits.map(_._2) shouldBe Seq(1, 2)
      commits.head._3 shouldBe BigInt("12345678", 16)
      commits(1)._3 shouldBe BigInt(55)
    }
  }
}
