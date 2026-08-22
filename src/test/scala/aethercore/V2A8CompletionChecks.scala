package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, PrivilegeMode}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.PmpConstants
import aethercore.core.v2._

/** Small harness that exposes only the A8 system-completion transport facts. */
private class A8SystemCompletionHarness extends Module {
  val io = IO(new Bundle {
    val ready = Input(Bool())
    val csrReadData = Input(UInt(64.W))
    val headIndex = Input(UInt(2.W))
    val headGeneration = Input(UInt(8.W))
    val valid = Output(Bool())
    val value = Output(UInt(64.W))
    val responseIndex = Output(UInt(2.W))
    val responseGeneration = Output(UInt(8.W))
  })

  private val system = Module(new TinySystemCompletion(CoreProfiles.rv64imCurrent.isa))

  system.io.head.valid := true.B
  system.io.head.bits := 0.U.asTypeOf(system.io.head.bits)
  system.io.head.bits.executionClass := ExecutionClass.System
  system.io.head.bits.producesValue := true.B
  system.io.head.bits.robToken.index := io.headIndex
  system.io.head.bits.robToken.generation := io.headGeneration
  system.io.head.bits.producerTag.id := io.headIndex
  system.io.head.bits.producerTag.generation := io.headGeneration
  system.io.head.bits.valueRef.id := io.headIndex
  system.io.head.bits.valueRef.generation := io.headGeneration
  system.io.head.bits.decoded.rawInst := "h30002073".U
  system.io.head.bits.decoded.rs1 := 0.U
  system.io.head.bits.decoded.system.kind := SystemOperationKind.Csr
  system.io.head.bits.decoded.system.csrOp := CsrOp.Set
  system.io.head.bits.decoded.system.csrAddress := "h300".U
  system.io.head.bits.decoded.system.csrUseImmediate := false.B
  system.io.head.bits.decoded.system.csrImmediate := 0.U
  system.io.head.bits.decoded.exception.valid := false.B

  system.io.headDependenciesValid := true.B
  system.io.headRs1 := 0.U.asTypeOf(system.io.headRs1)
  system.io.headRs1.ready := true.B
  system.io.headOperandsReady := true.B
  system.io.csrReadData := io.csrReadData
  system.io.csrReadImplemented := true.B
  system.io.csrReadWritable := true.B
  system.io.currentPrivilege := PrivilegeMode.Machine.U
  system.io.completion.ready := io.ready

  io.valid := system.io.completion.valid
  io.value := system.io.completion.bits.value
  io.responseIndex := system.io.completion.bits.robToken.index
  io.responseGeneration := system.io.completion.bits.robToken.generation
}

/** Isolate round-robin transport from producer semantics. */
private class A8CompletionArbiterHarness extends Module {
  val io = IO(new Bundle {
    val sourceValid = Input(Vec(3, Bool()))
    val sourceReady = Output(Vec(3, Bool()))
    val outReady = Input(Bool())
    val outValid = Output(Bool())
    val outValue = Output(UInt(64.W))
  })

  private val merge = Module(new TinyCompletionArbiter(64, 3))
  for (index <- 0 until 3) {
    merge.io.in(index).valid := io.sourceValid(index)
    merge.io.in(index).bits := 0.U.asTypeOf(merge.io.in(index).bits)
    merge.io.in(index).bits.robToken.index := index.U
    merge.io.in(index).bits.robToken.generation := 0.U
    merge.io.in(index).bits.producerTag.id := index.U
    merge.io.in(index).bits.producerTag.generation := 0.U
    merge.io.in(index).bits.valueRef.id := index.U
    merge.io.in(index).bits.valueRef.generation := 0.U
    merge.io.in(index).bits.hasValue := true.B
    merge.io.in(index).bits.value := ((index + 1) * 11).U
    io.sourceReady(index) := merge.io.in(index).ready
  }
  merge.io.out.ready := io.outReady
  io.outValid := merge.io.out.valid
  io.outValue := merge.io.out.bits.value
}

trait V2A8CompletionChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  behavior of "AetherCore v2 A8 completion ownership"

  it should "hold a system completion bit-stable under backpressure and emit one observed lifetime once" in {
    simulate(new A8SystemCompletionHarness) { dut =>
      dut.io.ready.poke(false.B)
      dut.io.csrReadData.poke(0x111.U)
      dut.io.headIndex.poke(1.U)
      dut.io.headGeneration.poke(7.U)

      dut.io.valid.expect(true.B)
      dut.io.value.expect(0x111.U)
      dut.io.responseIndex.expect(1.U)
      dut.io.responseGeneration.expect(7.U)

      // The first blocked cycle captures the exact semantic result.
      dut.clock.step()
      dut.io.csrReadData.poke(0x222.U)
      for (_ <- 0 until 3) {
        dut.io.valid.expect(true.B)
        dut.io.value.expect(0x111.U)
        dut.io.responseIndex.expect(1.U)
        dut.io.responseGeneration.expect(7.U)
        dut.clock.step()
      }

      // Accept it. The unchanged head must not regenerate a duplicate response.
      dut.io.ready.poke(true.B)
      dut.io.valid.expect(true.B)
      dut.io.value.expect(0x111.U)
      dut.clock.step()
      dut.io.ready.poke(false.B)
      dut.io.valid.expect(false.B)

      // A genuinely new observed lifetime can immediately produce a new value.
      dut.io.headGeneration.poke(8.U)
      dut.io.valid.expect(true.B)
      dut.io.value.expect(0x222.U)
      dut.io.responseGeneration.expect(8.U)
    }
  }

  it should "give every simultaneously pending completion source bounded round-robin service" in {
    simulate(new A8CompletionArbiterHarness) { dut =>
      for (index <- 0 until 3) dut.io.sourceValid(index).poke(true.B)
      dut.io.outReady.poke(false.B)

      // Backpressure must leave the selected response stable.
      dut.io.outValid.expect(true.B)
      val held = dut.io.outValue.peek().litValue
      for (_ <- 0 until 2) {
        dut.clock.step()
        dut.io.outValid.expect(true.B)
        dut.io.outValue.expect(held.U)
      }

      dut.io.outReady.poke(true.B)
      var pending = Set(BigInt(11), BigInt(22), BigInt(33))
      var cycles = 0
      while (pending.nonEmpty && cycles < 6) {
        dut.io.outValid.expect(true.B)
        val value = dut.io.outValue.peek().litValue
        withClue(s"unexpected or duplicate completion value $value: ") {
          pending.contains(value) shouldBe true
        }
        val source = (value / 11).toInt - 1
        dut.io.sourceReady(source).expect(true.B)
        dut.clock.step()
        dut.io.sourceValid(source).poke(false.B)
        pending -= value
        cycles += 1
      }

      pending shouldBe empty
      cycles should be <= 3
    }
  }

  it should "retain an LSU terminal response and its busy lifetime until completion fire" in {
    simulate(new TinyBlockingLsu(PageTableGeometry.Sv39)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.storePermit.valid.poke(false.B)
      dut.io.storePermit.bits.index.poke(0.U)
      dut.io.storePermit.bits.generation.poke(0.U)

      dut.io.effectivePrivilege.poke(PrivilegeMode.Machine.U)
      dut.io.satpTranslationEnabled.poke(false.B)
      dut.io.satpRootPpn.poke(0.U)
      dut.io.supervisorSum.poke(false.B)
      dut.io.supervisorMxr.poke(false.B)
      dut.io.translationFlush.poke(false.B)
      dut.io.pmpEnabled.poke(false.B)
      for (index <- 0 until PmpConstants.MaxEntries) {
        dut.io.pmpConfig(index).poke(0.U)
        dut.io.pmpAddress(index).poke(0.U)
      }
      dut.io.pteReady.poke(false.B)
      dut.io.pteData.poke(0.U)
      dut.io.pteFault.poke(false.B)
      dut.io.resolvedAttributes.cacheable.poke(true.B)
      dut.io.resolvedAttributes.idempotent.poke(true.B)
      dut.io.resolvedAttributes.sideEffecting.poke(false.B)
      dut.io.resolvedAttributes.ordered.poke(false.B)
      dut.io.resolvedAttributes.executable.poke(false.B)
      dut.io.resolvedAttributes.supportsAtomic.poke(false.B)
      dut.io.resolvedAttributes.supportsPartial.poke(true.B)
      dut.io.memoryRequest.ready.poke(false.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.txnId.poke(0.U)
      dut.io.memoryResponse.bits.rdata.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.robToken.index.poke(2.U)
      dut.io.request.bits.robToken.generation.poke(9.U)
      dut.io.request.bits.producerTag.id.poke(2.U)
      dut.io.request.bits.producerTag.generation.poke(9.U)
      dut.io.request.bits.valueRef.id.poke(2.U)
      dut.io.request.bits.valueRef.generation.poke(9.U)
      dut.io.request.bits.kind.poke(MemoryOperationKind.Load)
      dut.io.request.bits.size.poke(MemSize.DWord)
      dut.io.request.bits.unsigned.poke(false.B)
      dut.io.request.bits.atomicOp.poke(AtomicOp.None)
      dut.io.request.bits.base.poke(0x1000.U)
      dut.io.request.bits.offset.poke(0.U)
      dut.io.request.bits.storeData.poke(0.U)
      dut.io.request.bits.rawInst.poke(0x00003003.U)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      var requestCycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && requestCycles < 16) {
        dut.clock.step()
        requestCycles += 1
      }
      dut.io.memoryRequest.valid.expect(true.B)
      val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(txn.U)
      dut.io.memoryResponse.bits.rdata.poke(0x1234.U)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.robToken.index.expect(2.U)
      dut.io.completion.bits.robToken.generation.expect(9.U)
      dut.io.completion.bits.value.expect(0x1234.U)
      dut.clock.step()

      // The physical response is gone and its source data changes, but the
      // held architectural completion must remain exactly the same.
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.rdata.poke(0xdead.U)
      for (_ <- 0 until 3) {
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.robToken.index.expect(2.U)
        dut.io.completion.bits.robToken.generation.expect(9.U)
        dut.io.completion.bits.value.expect(0x1234.U)
        dut.io.busy.expect(true.B)
        dut.io.request.ready.expect(false.B)
        dut.io.memoryResponse.ready.expect(false.B)
        dut.clock.step()
      }

      dut.io.completion.ready.poke(true.B)
      dut.io.completion.valid.expect(true.B)
      dut.clock.step()
      dut.io.busy.expect(false.B)
      dut.io.request.ready.expect(true.B)
    }
  }

  it should "serve a pending divide despite a continuously refillable integer completion" in {
    simulate(new TinyExecutionCluster(32, hasCompressed = false)) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(false.B)

      def pokeRequest(
          executionClass: ExecutionClass.Type,
          op: AluOp.Type,
          index: Int,
          generation: Int,
          lhs: BigInt,
          rhs: BigInt
      ): Unit = {
        dut.io.request.bits.robToken.index.poke(index.U)
        dut.io.request.bits.robToken.generation.poke(generation.U)
        dut.io.request.bits.producerTag.id.poke(index.U)
        dut.io.request.bits.producerTag.generation.poke(generation.U)
        dut.io.request.bits.valueRef.id.poke(index.U)
        dut.io.request.bits.valueRef.generation.poke(generation.U)
        dut.io.request.bits.executionClass.poke(executionClass)
        dut.io.request.bits.aluOp.poke(op)
        dut.io.request.bits.wordOp.poke(false.B)
        dut.io.request.bits.controlFlowKind.poke(ControlFlowKind.None)
        dut.io.request.bits.branchType.poke(BranchType.None)
        dut.io.request.bits.lhs.poke(lhs.U)
        dut.io.request.bits.rhs.poke(rhs.U)
        dut.io.request.bits.pc.poke(0.U)
        dut.io.request.bits.instBytes.poke(4.U)
        dut.io.request.bits.immediate.poke(0.U)
      }

      // Start a long-latency divide first.
      pokeRequest(ExecutionClass.MulDiv, AluOp.Divu, index = 3, generation = 1, lhs = 100, rhs = 7)
      dut.io.request.valid.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      // Then create a one-cycle integer response and hold the shared response
      // port closed while the divider finishes in the background.
      pokeRequest(ExecutionClass.Integer, AluOp.Add, index = 0, generation = 1, lhs = 10, rhs = 1)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      for (_ <- 0 until 40) dut.clock.step()

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(0.U)
      dut.io.response.bits.value.expect(11.U)

      // Hold a replacement integer request valid. When the first integer
      // response fires it can refill the integer unit in the same cycle. A
      // fixed-priority merge would then select integer again forever under a
      // sustained stream; round-robin must rotate to the already-pending DIV.
      pokeRequest(ExecutionClass.Integer, AluOp.Add, index = 1, generation = 2, lhs = 20, rhs = 2)
      dut.io.request.valid.poke(true.B)
      dut.io.response.ready.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.io.response.bits.robToken.index.expect(0.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(3.U)
      dut.io.response.bits.robToken.generation.expect(1.U)
      dut.io.response.bits.value.expect(14.U)
      dut.clock.step()

      // The replacement integer response remains available after the divider
      // has received bounded service.
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.robToken.index.expect(1.U)
      dut.io.response.bits.robToken.generation.expect(2.U)
      dut.io.response.bits.value.expect(22.U)
    }
  }
}
