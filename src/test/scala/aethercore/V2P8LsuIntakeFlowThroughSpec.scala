package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MemSize, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.PmpConstants
import aethercore.core.v2._
import aethercore.memory.AetherMemOp

/** P8 regression for the one-cycle blocking-LSU intake bubble. */
trait V2P8LsuIntakeFlowThroughChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val geometry = PageTableGeometry.Sv39

  private def initialize(dut: TinyBlockingLsu): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.completion.ready.poke(true.B)
    dut.io.storePermit.valid.poke(false.B)
    dut.io.storePermit.bits.index.poke(0.U)
    dut.io.storePermit.bits.generation.poke(0.U)

    dut.io.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.satpTranslationEnabled.poke(true.B)
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
  }

  private def pokeLoad(
      dut: TinyBlockingLsu,
      address: BigInt,
      index: Int,
      generation: Int
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.robToken.index.poke(index.U)
    dut.io.request.bits.robToken.generation.poke(generation.U)
    dut.io.request.bits.producerTag.id.poke(index.U)
    dut.io.request.bits.producerTag.generation.poke(generation.U)
    dut.io.request.bits.valueRef.id.poke(index.U)
    dut.io.request.bits.valueRef.generation.poke(generation.U)
    dut.io.request.bits.kind.poke(MemoryOperationKind.Load)
    dut.io.request.bits.size.poke(MemSize.Word)
    dut.io.request.bits.unsigned.poke(true.B)
    dut.io.request.bits.atomicOp.poke(AtomicOp.None)
    dut.io.request.bits.base.poke(address.U)
    dut.io.request.bits.offset.poke(0.U)
    dut.io.request.bits.storeData.poke(0.U)
    dut.io.request.bits.rawInst.poke(0x00002003.U)
  }

  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      accessed: Boolean = false
  ): BigInt =
    (ppn << 10) |
      BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0))

  private def vpn(va: BigInt, level: Int): BigInt = {
    val mask = (BigInt(1) << geometry.vpnBitsPerLevel) - 1
    (va >> (geometry.pageOffsetBits + level * geometry.vpnBitsPerLevel)) & mask
  }

  private def pteAddress(tablePpn: BigInt, va: BigInt, level: Int): BigInt =
    (tablePpn << geometry.pageOffsetBits) + vpn(va, level) * geometry.pteBytes

  private def providePte(
      dut: TinyBlockingLsu,
      expectedAddress: BigInt,
      value: BigInt
  ): Unit = {
    var cycles = 0
    while (!dut.io.pteValid.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    withClue("PTW request did not arrive: ") {
      dut.io.pteValid.peek().litToBoolean shouldBe true
    }
    dut.io.pteAddress.expect(expectedAddress.U)
    dut.io.pteData.poke(value.U)
    dut.io.pteReady.poke(true.B)
    dut.clock.step()
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
  }

  private def awaitPhysicalRequest(dut: TinyBlockingLsu): BigInt = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    withClue("translated physical request did not arrive: ") {
      dut.io.memoryRequest.valid.peek().litToBoolean shouldBe true
    }
    dut.io.memoryRequest.bits.txnId.peek().litValue
  }

  behavior of "AetherCore v2 P8 LSU intake flow-through"

  it should "launch a cached Sv39 translation in the same cycle the idle LSU accepts the request" in {
    simulate(new TinyBlockingLsu(geometry)) { dut =>
      initialize(dut)

      val rootPpn = BigInt("10000", 16)
      val level1Ppn = BigInt("11000", 16)
      val level0Ppn = BigInt("12000", 16)
      val leafPpn = BigInt("2345678", 16)
      val va = BigInt("1234567024", 16)
      val translatedPa = (leafPpn << geometry.pageOffsetBits) | (va & 0xfff)
      dut.io.satpRootPpn.poke(rootPpn.U)

      // First access walks Sv39 and fills the TLB through the normal LSU path.
      pokeLoad(dut, va, index = 1, generation = 1)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.busy.expect(true.B)

      providePte(dut, pteAddress(rootPpn, va, level = 2), pte(level1Ppn))
      providePte(dut, pteAddress(level1Ppn, va, level = 1), pte(level0Ppn))
      providePte(
        dut,
        pteAddress(level0Ppn, va, level = 0),
        pte(leafPpn, read = true, accessed = true)
      )

      val firstTxn = awaitPhysicalRequest(dut)
      dut.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
      dut.io.memoryRequest.bits.paddr.expect(translatedPa.U)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(firstTxn.U)
      dut.io.memoryResponse.bits.rdata.poke(0x11223344.U)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.robToken.index.expect(1.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.busy.expect(false.B)

      // This is the P8 contract. With the TLB now hot, merely presenting a
      // request to an idle LSU must expose the physical request combinationally;
      // no clock edge may be required to copy the request through `active` first.
      pokeLoad(dut, va, index = 2, generation = 7)
      dut.io.request.ready.expect(true.B)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
      dut.io.memoryRequest.bits.paddr.expect(translatedPa.U)
      val secondTxn = dut.io.memoryRequest.bits.txnId.peek().litValue

      // Accept both the architectural LSU request and its physical launch on
      // the same edge, then prove the registered active lifetime owns response.
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.memoryRequest.ready.poke(false.B)
      dut.io.busy.expect(true.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(secondTxn.U)
      dut.io.memoryResponse.bits.rdata.poke(0xa5a5a5a5L.U)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.robToken.index.expect(2.U)
      dut.io.completion.bits.robToken.generation.expect(7.U)
      dut.io.completion.bits.hasValue.expect(true.B)
      dut.io.completion.bits.value.expect(0xa5a5a5a5L.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.busy.expect(false.B)
    }
  }
}
