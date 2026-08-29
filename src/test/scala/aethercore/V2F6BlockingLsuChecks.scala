package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.PmpConstants
import aethercore.core.v2._
import aethercore.memory.AetherMemOp

/** Focused F6 checks for the correctness-first one-outstanding LSU. */
trait V2F6BlockingLsuChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeEnvironment(dut: TinyBlockingLsu, privilege: Int = PrivilegeMode.Machine): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.completion.ready.poke(true.B)
    dut.io.storePermit.valid.poke(false.B)
    dut.io.storePermit.bits.index.poke(0.U)
    dut.io.storePermit.bits.generation.poke(0.U)

    dut.io.effectivePrivilege.poke(privilege.U)
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

    // Hold the physical request until each check has inspected it.
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def dispatch(
      dut: TinyBlockingLsu,
      kind: MemoryOperationKind.Type,
      size: MemSize.Type,
      unsigned: Boolean,
      base: BigInt,
      offset: BigInt = 0,
      storeData: BigInt = 0,
      index: Int = 1,
      generation: Int = 2,
      atomicOp: AtomicOp.Type = AtomicOp.None,
      rawInst: BigInt = 0x00002003
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.robToken.index.poke(index.U)
    dut.io.request.bits.robToken.generation.poke(generation.U)
    dut.io.request.bits.producerTag.id.poke(index.U)
    dut.io.request.bits.producerTag.generation.poke(generation.U)
    dut.io.request.bits.valueRef.id.poke(index.U)
    dut.io.request.bits.valueRef.generation.poke(generation.U)
    dut.io.request.bits.kind.poke(kind)
    dut.io.request.bits.size.poke(size)
    dut.io.request.bits.unsigned.poke(unsigned.B)
    dut.io.request.bits.atomicOp.poke(atomicOp)
    dut.io.request.bits.base.poke(base.U)
    dut.io.request.bits.offset.poke(offset.U)
    dut.io.request.bits.storeData.poke(storeData.U)
    dut.io.request.bits.rawInst.poke(rawInst.U)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
    dut.io.busy.expect(true.B)
  }

  private def stepUntil(dut: TinyBlockingLsu, maxCycles: Int = 12)(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    condition shouldBe true
  }

  private def firePhysicalRequest(dut: TinyBlockingLsu): BigInt = {
    stepUntil(dut) { dut.io.memoryRequest.valid.peek().litValue == 1 }
    val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
    dut.io.memoryRequest.ready.poke(true.B)
    dut.clock.step()
    dut.io.memoryRequest.ready.poke(false.B)
    txn
  }

  behavior of "AetherCore v2 F6 correctness-first blocking LSU"

  it should "preserve RV32 Sv32 physical-address geometry independently of XLEN" in {
    simulate(new TinyBlockingLsu(PageTableGeometry.Sv32)) { dut =>
      dut.io.resolvedPhysicalAddress.getWidth shouldBe 34
      dut.io.memoryRequest.bits.paddr.getWidth shouldBe 34
      dut.io.memoryTrace.bits.paddr.getWidth shouldBe 34
    }
  }

  it should "exercise the RV64 Sv39 blocking LSU contract in one simulator lifecycle" in {
    simulate(new TinyBlockingLsu(PageTableGeometry.Sv39)) { dut =>
      withClue("PA56 geometry: ") {
        dut.io.resolvedPhysicalAddress.getWidth shouldBe 56
        dut.io.memoryRequest.bits.paddr.getWidth shouldBe 56
        dut.io.memoryTrace.bits.paddr.getWidth shouldBe 56
      }

      withClue("signed load and stale transaction rejection: ") {
        pokeEnvironment(dut)
        dispatch(
          dut,
          kind = MemoryOperationKind.Load,
          size = MemSize.Byte,
          unsigned = false,
          base = 0x1000
        )

        val txn = firePhysicalRequest(dut)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.bits.txnId.poke(((txn + 1) & 3).U)
        dut.io.memoryResponse.bits.rdata.poke(0x80.U)
        dut.io.completion.valid.expect(false.B)
        dut.clock.step()

        dut.io.memoryResponse.bits.txnId.poke(txn.U)
        dut.io.memoryResponse.bits.rdata.poke(0x80.U)
        dut.io.memoryResponse.bits.last.poke(true.B)
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.robToken.index.expect(1.U)
        dut.io.completion.bits.robToken.generation.expect(2.U)
        dut.io.completion.bits.hasValue.expect(true.B)
        dut.io.completion.bits.value.expect("hffffffffffffff80".U)
        dut.io.completion.bits.exception.valid.expect(false.B)
        dut.io.memoryTrace.valid.expect(true.B)
        dut.io.memoryTrace.bits.write.expect(false.B)
        dut.io.memoryTrace.bits.paddr.expect(0x1000.U)
        dut.clock.step()
        dut.io.memoryResponse.valid.poke(false.B)
        dut.io.busy.expect(false.B)
      }

      withClue("exact-generation store permit: ") {
        pokeEnvironment(dut)
        dispatch(
          dut,
          kind = MemoryOperationKind.Store,
          size = MemSize.Word,
          unsigned = false,
          base = 0x2000,
          storeData = 0x12345678,
          index = 2,
          generation = 1,
          rawInst = 0x00802023
        )

        stepUntil(dut) { dut.io.resolvedPhysicalValid.peek().litValue == 1 }
        dut.io.memoryRequest.valid.expect(false.B)

        dut.io.storePermit.valid.poke(true.B)
        dut.io.storePermit.bits.index.poke(2.U)
        dut.io.storePermit.bits.generation.poke(0.U)
        dut.io.memoryRequest.valid.expect(false.B)

        dut.io.storePermit.bits.generation.poke(1.U)
        dut.io.memoryRequest.valid.expect(true.B)
        dut.io.memoryRequest.bits.op.expect(AetherMemOp.Write)
        dut.io.memoryRequest.bits.paddr.expect(0x2000.U)
        dut.io.memoryRequest.bits.wdata.expect(0x12345678.U)
        dut.io.memoryRequest.bits.wmask.expect("hf".U)
        val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
        dut.io.memoryRequest.ready.poke(true.B)
        dut.clock.step()
        dut.io.memoryRequest.ready.poke(false.B)

        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.bits.txnId.poke(txn.U)
        dut.io.memoryResponse.bits.rdata.poke(0.U)
        dut.io.memoryResponse.bits.last.poke(true.B)
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.hasValue.expect(false.B)
        dut.io.completion.bits.exception.valid.expect(false.B)
        dut.io.memoryTrace.valid.expect(true.B)
        dut.io.memoryTrace.bits.robToken.index.expect(2.U)
        dut.io.memoryTrace.bits.robToken.generation.expect(1.U)
        dut.io.memoryTrace.bits.write.expect(true.B)
        dut.io.memoryTrace.bits.wmask.expect("hf".U)
        dut.clock.step()
        dut.io.memoryResponse.valid.poke(false.B)
        dut.io.busy.expect(false.B)
      }

      withClue("local alignment exception: ") {
        pokeEnvironment(dut)
        dispatch(
          dut,
          kind = MemoryOperationKind.Store,
          size = MemSize.Word,
          unsigned = false,
          base = 0x3002,
          storeData = 0x55
        )

        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.exception.valid.expect(true.B)
        dut.io.completion.bits.exception.cause.expect(MachineExceptionCode.StoreAddressMisaligned.U)
        dut.io.completion.bits.exception.value.expect(0x3002.U)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.pteValid.expect(false.B)
        dut.clock.step()
        dut.io.busy.expect(false.B)
      }

      withClue("PMP denial without externalization: ") {
        pokeEnvironment(dut, privilege = PrivilegeMode.Supervisor)
        dut.io.pmpEnabled.poke(true.B)
        // All PMP entries are OFF. An unmatched S-mode access is denied.
        dispatch(
          dut,
          kind = MemoryOperationKind.Load,
          size = MemSize.Word,
          unsigned = true,
          base = 0x4000
        )

        stepUntil(dut) { dut.io.completion.valid.peek().litValue == 1 }
        dut.io.completion.bits.exception.valid.expect(true.B)
        dut.io.completion.bits.exception.cause.expect(MachineExceptionCode.LoadAccessFault.U)
        dut.io.completion.bits.exception.value.expect(0x4000.U)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.clock.step()
        dut.io.busy.expect(false.B)
      }

      withClue("unsupported atomic fail-closed behavior: ") {
        pokeEnvironment(dut)
        dispatch(
          dut,
          kind = MemoryOperationKind.Atomic,
          size = MemSize.DWord,
          unsigned = false,
          base = 0x5000,
          atomicOp = AtomicOp.Add,
          rawInst = 0x00b5302f
        )

        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.exception.valid.expect(true.B)
        dut.io.completion.bits.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
        dut.io.completion.bits.exception.value.expect(0x00b5302f.U)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.clock.step()
        dut.io.busy.expect(false.B)
      }
    }
  }
}
