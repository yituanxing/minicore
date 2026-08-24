package aethercore

import aethercore.common.{AtomicOp, MemSize, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.PmpConstants
import aethercore.core.v2._
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Focused safety checks for the pre-head wrapper around the qualified LSU. */
trait V2P8PreHeadLoadLsuChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def initialize(dut: TinyPreHeadLoadLsu): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.requestPreHead.poke(false.B)
    dut.io.headPermit.valid.poke(false.B)
    dut.io.headPermit.bits.index.poke(0.U)
    dut.io.headPermit.bits.generation.poke(0.U)
    dut.io.completion.ready.poke(true.B)

    dut.io.storePermit.valid.poke(false.B)
    dut.io.storePermit.bits.index.poke(0.U)
    dut.io.storePermit.bits.generation.poke(0.U)

    dut.io.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.satpTranslationEnabled.poke(false.B)
    dut.io.satpRootPpn.poke(0.U)
    dut.io.supervisorSum.poke(false.B)
    dut.io.supervisorMxr.poke(false.B)
    dut.io.translationFlush.poke(false.B)

    dut.io.pmpEnabled.poke(false.B)
    for (i <- 0 until PmpConstants.MaxEntries) {
      dut.io.pmpConfig(i).poke(0.U)
      dut.io.pmpAddress(i).poke(0.U)
    }

    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
    dut.io.pteFault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(true.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(true.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)

    pokeLoad(dut, index = 0, address = 0)
  }

  private def pokeLoad(dut: TinyPreHeadLoadLsu, index: Int, address: BigInt): Unit = {
    dut.io.request.bits.robToken.index.poke(index.U)
    dut.io.request.bits.robToken.generation.poke(0.U)
    dut.io.request.bits.producerTag.id.poke(index.U)
    dut.io.request.bits.producerTag.generation.poke(0.U)
    dut.io.request.bits.valueRef.id.poke(index.U)
    dut.io.request.bits.valueRef.generation.poke(0.U)
    dut.io.request.bits.kind.poke(MemoryOperationKind.Load)
    dut.io.request.bits.size.poke(MemSize.Word)
    dut.io.request.bits.unsigned.poke(false.B)
    dut.io.request.bits.atomicOp.poke(AtomicOp.None)
    dut.io.request.bits.base.poke(address.U)
    dut.io.request.bits.offset.poke(0.U)
    dut.io.request.bits.storeData.poke(0.U)
    dut.io.request.bits.rawInst.poke(0x00002003.U)
  }

  private def acceptPreHeadLoad(dut: TinyPreHeadLoadLsu, index: Int, address: BigInt): Unit = {
    pokeLoad(dut, index, address)
    dut.io.requestPreHead.poke(true.B)
    dut.io.request.valid.poke(true.B)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
    dut.io.requestPreHead.poke(false.B)
  }

  behavior of "AetherCore v2 pre-head Load LSU safety wrapper"

  it should "allow an idempotent non-device physical read before head" in {
    simulate(new TinyPreHeadLoadLsu(PageTableGeometry.Sv32, paddrBits = 34)) { dut =>
      initialize(dut)
      acceptPreHeadLoad(dut, index = 1, address = 0x1000)

      // Bare translation reaches the physical request while the lifetime is still younger.
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.op.expect(aethercore.memory.AetherMemOp.Read)
      dut.io.memoryRequest.bits.paddr.expect(0x1000.U)
      dut.io.pteValid.expect(false.B)
    }
  }

  it should "hold a side-effecting or ordered read until its exact head token arrives" in {
    simulate(new TinyPreHeadLoadLsu(PageTableGeometry.Sv32, paddrBits = 34)) { dut =>
      initialize(dut)
      dut.io.resolvedAttributes.cacheable.poke(false.B)
      dut.io.resolvedAttributes.idempotent.poke(false.B)
      dut.io.resolvedAttributes.sideEffecting.poke(true.B)
      dut.io.resolvedAttributes.ordered.poke(true.B)
      acceptPreHeadLoad(dut, index = 2, address = 0x10000000L)

      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.busy.expect(true.B)

      // The same held lifetime becomes ordinary at the precise head boundary.
      dut.io.headPermit.valid.poke(true.B)
      dut.io.headPermit.bits.index.poke(2.U)
      dut.io.headPermit.bits.generation.poke(0.U)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.paddr.expect(0x10000000L.U)
    }
  }

  it should "suppress page-table memory traffic for a speculative miss until head" in {
    simulate(new TinyPreHeadLoadLsu(PageTableGeometry.Sv32, paddrBits = 34)) { dut =>
      initialize(dut)
      dut.io.satpTranslationEnabled.poke(true.B)
      dut.io.satpRootPpn.poke(0x200.U)
      acceptPreHeadLoad(dut, index = 3, address = 0x40403020L)

      // The qualified inner walker may hold internal miss state, but no PTE
      // transaction is visible outside the safety wrapper while speculative.
      dut.io.pteValid.expect(false.B)
      dut.clock.step()
      dut.io.pteValid.expect(false.B)
      dut.io.memoryRequest.valid.expect(false.B)

      dut.io.headPermit.valid.poke(true.B)
      dut.io.headPermit.bits.index.poke(3.U)
      dut.io.headPermit.bits.generation.poke(0.U)
      dut.io.pteValid.expect(true.B)
    }
  }
}
