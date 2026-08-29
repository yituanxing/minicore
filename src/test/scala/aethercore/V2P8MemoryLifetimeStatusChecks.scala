package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MemSize, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.PmpConstants
import aethercore.core.v2._

/** P8.4-M1 proof that the LSU exposes facts without changing memory policy. */
trait V2P8MemoryLifetimeStatusChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def initialize(dut: TinyBlockingLsu): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.completion.ready.poke(true.B)
    dut.io.storePermit.valid.poke(false.B)
    dut.io.storePermit.bits.index.poke(0.U)
    dut.io.storePermit.bits.generation.poke(0.U)
    dut.io.reservationClear.foreach(_.poke(false.B))

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
    dut.io.resolvedAttributes.supportsAtomic.poke(true.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def present(
      dut: TinyBlockingLsu,
      kind: MemoryOperationKind.Type,
      atomicOp: AtomicOp.Type,
      base: BigInt,
      size: MemSize.Type = MemSize.Word,
      storeData: BigInt = 0,
      index: Int = 1,
      generation: Int = 0
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
    dut.io.request.bits.unsigned.poke(false.B)
    dut.io.request.bits.atomicOp.poke(atomicOp)
    dut.io.request.bits.base.poke(base.U)
    dut.io.request.bits.offset.poke(0.U)
    dut.io.request.bits.storeData.poke(storeData.U)
    dut.io.request.bits.rawInst.poke(0.U)
    dut.io.request.ready.expect(true.B)
  }

  private def stepUntil(dut: TinyBlockingLsu, maxCycles: Int = 12)(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    condition shouldBe true
  }

  behavior of "AetherCore v2 P8.4-M1 memory lifetime status"

  it should "classify write-like operations on the intake flow-through cycle" in {
    simulate(new TinyBlockingLsu(PageTableGeometry.Sv39, allowAtomics = true)) { dut =>
      initialize(dut)
      dut.io.lifetimeStatus.valid.expect(false.B)
      dut.io.lifetimeStatus.drained.expect(true.B)

      present(dut, MemoryOperationKind.Load, AtomicOp.None, 0x1000)
      dut.io.lifetimeStatus.valid.expect(true.B)
      dut.io.lifetimeStatus.drained.expect(false.B)
      dut.io.lifetimeStatus.writeLike.expect(false.B)
      dut.io.lifetimeStatus.effectiveAddress.expect(0x1000.U)

      present(dut, MemoryOperationKind.Atomic, AtomicOp.Lr, 0x1100, size = MemSize.DWord)
      dut.io.lifetimeStatus.writeLike.expect(false.B)

      present(dut, MemoryOperationKind.Store, AtomicOp.None, 0x1200)
      dut.io.lifetimeStatus.writeLike.expect(true.B)

      present(dut, MemoryOperationKind.Atomic, AtomicOp.Sc, 0x1300, size = MemSize.DWord)
      dut.io.lifetimeStatus.writeLike.expect(true.B)

      present(dut, MemoryOperationKind.Atomic, AtomicOp.Add, 0x1400, size = MemSize.DWord)
      dut.io.lifetimeStatus.writeLike.expect(true.B)

      dut.io.request.valid.poke(false.B)
      dut.io.lifetimeStatus.valid.expect(false.B)
      dut.io.lifetimeStatus.drained.expect(true.B)
    }
  }

  it should "track exact store permission, physical externalization, and held completion lifetime" in {
    simulate(new TinyBlockingLsu(PageTableGeometry.Sv39)) { dut =>
      initialize(dut)
      dut.io.completion.ready.poke(false.B)
      present(
        dut,
        MemoryOperationKind.Store,
        AtomicOp.None,
        0x2000,
        storeData = 0x12345678,
        index = 2,
        generation = 1
      )

      // Lifetime identity/classification is visible on intake. Resolved PA/PMA
      // remain separately valid and follow the existing DataPathAdapter timing.
      dut.io.lifetimeStatus.robToken.index.expect(2.U)
      dut.io.lifetimeStatus.robToken.generation.expect(1.U)
      dut.io.lifetimeStatus.kind.expect(MemoryOperationKind.Store)
      dut.io.lifetimeStatus.size.expect(MemSize.Word)
      dut.io.lifetimeStatus.writeLike.expect(true.B)
      dut.io.lifetimeStatus.writePermitMatched.expect(false.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(false.B)
      dut.io.memoryRequest.valid.expect(false.B)

      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      stepUntil(dut) { dut.io.lifetimeStatus.physicalAddressValid.peek().litValue == 1 }
      dut.io.lifetimeStatus.valid.expect(true.B)
      dut.io.lifetimeStatus.physicalAddress.expect(0x2000.U)
      dut.io.lifetimeStatus.attributesValid.expect(true.B)
      dut.io.lifetimeStatus.attributes.cacheable.expect(true.B)

      dut.io.storePermit.valid.poke(true.B)
      dut.io.storePermit.bits.index.poke(2.U)
      dut.io.storePermit.bits.generation.poke(0.U)
      dut.io.lifetimeStatus.writePermitMatched.expect(false.B)
      dut.io.memoryRequest.valid.expect(false.B)

      dut.io.storePermit.bits.generation.poke(1.U)
      dut.io.lifetimeStatus.writePermitMatched.expect(true.B)
      dut.io.memoryRequest.valid.expect(true.B)
      val txn = dut.io.memoryRequest.bits.txnId.peek().litValue

      // The externalization fact reflects the physical handshake in this cycle,
      // not one register later.
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)
      dut.io.storePermit.valid.poke(false.B)

      dut.io.lifetimeStatus.valid.expect(true.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(true.B)
      dut.io.lifetimeStatus.completionPending.expect(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(txn.U)
      dut.io.memoryResponse.bits.rdata.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.lifetimeStatus.completionPending.expect(true.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(true.B)
      dut.clock.step()

      // Backpressure owns the response and keeps the LSU lifetime alive even
      // though the physical response has already been consumed.
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.lifetimeStatus.valid.expect(true.B)
      dut.io.lifetimeStatus.completionPending.expect(true.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(true.B)

      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.lifetimeStatus.valid.expect(false.B)
      dut.io.lifetimeStatus.drained.expect(true.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(false.B)
      dut.io.lifetimeStatus.completionPending.expect(false.B)
    }
  }

  it should "never report physical externalization for a local alignment fault" in {
    simulate(new TinyBlockingLsu(PageTableGeometry.Sv39)) { dut =>
      initialize(dut)
      dut.io.completion.ready.poke(false.B)
      dut.io.storePermit.valid.poke(true.B)
      dut.io.storePermit.bits.index.poke(3.U)
      dut.io.storePermit.bits.generation.poke(2.U)
      present(
        dut,
        MemoryOperationKind.Store,
        AtomicOp.None,
        0x3002,
        storeData = 0x55,
        index = 3,
        generation = 2
      )

      dut.io.lifetimeStatus.valid.expect(true.B)
      dut.io.lifetimeStatus.writePermitMatched.expect(true.B)
      dut.io.lifetimeStatus.physicalAddressValid.expect(false.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(false.B)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.lifetimeStatus.completionPending.expect(false.B)

      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.exception.valid.expect(true.B)
      dut.io.lifetimeStatus.completionPending.expect(true.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(false.B)
      dut.clock.step()

      dut.io.storePermit.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.completion.valid.expect(true.B)
      dut.clock.step()
      dut.io.lifetimeStatus.valid.expect(false.B)
      dut.io.lifetimeStatus.drained.expect(true.B)
      dut.io.lifetimeStatus.physicalRequestIssued.expect(false.B)
    }
  }
}
