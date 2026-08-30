package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.PmpConstants
import aethercore.core.v2._

/** Focused proof that speculative Load success may complete pre-head while faults may not. */
trait V2P8PreHeadLoadExceptionChecks {
  this: AnyFlatSpec with Matchers with ChiselSim =>

  private def pokeEnvironment(dut: TinyDualReplaySafeLoadUnit): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.completion.ready.poke(true.B)

    dut.io.head.valid.poke(false.B)
    dut.io.head.bits.index.poke(0.U)
    dut.io.head.bits.generation.poke(0.U)

    dut.io.effectivePrivilege.poke(PrivilegeMode.Machine.U)
    dut.io.satpTranslationEnabled.poke(false.B)
    dut.io.satpRootPpn.poke(0.U)
    dut.io.supervisorSum.poke(false.B)
    dut.io.supervisorMxr.poke(false.B)
    dut.io.translationFlush.poke(false.B)

    dut.io.pmpEnabled.poke(false.B)
    dut.io.auxPmpValid.poke(false.B)
    dut.io.auxPmpAddress.poke(0.U)
    dut.io.auxPmpSize.poke(MemSize.Word)
    dut.io.auxPmpWrite.poke(false.B)
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

  private def dispatchLoad(
      dut: TinyDualReplaySafeLoadUnit,
      base: BigInt,
      size: MemSize.Type = MemSize.Word,
      index: Int = 1,
      generation: Int = 2
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.robToken.index.poke(index.U)
    dut.io.request.bits.robToken.generation.poke(generation.U)
    dut.io.request.bits.producerTag.id.poke(index.U)
    dut.io.request.bits.producerTag.generation.poke(generation.U)
    dut.io.request.bits.valueRef.id.poke(index.U)
    dut.io.request.bits.valueRef.generation.poke(generation.U)
    dut.io.request.bits.kind.poke(MemoryOperationKind.Load)
    dut.io.request.bits.size.poke(size)
    dut.io.request.bits.unsigned.poke(true.B)
    dut.io.request.bits.atomicOp.poke(AtomicOp.None)
    dut.io.request.bits.base.poke(base.U)
    dut.io.request.bits.offset.poke(0.U)
    dut.io.request.bits.storeData.poke(0.U)
    dut.io.request.bits.rawInst.poke(0x00002003.U)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  private def stepUntil(maxCycles: Int = 16)(condition: => Boolean)(step: => Unit): Unit = {
    var cycles = 0
    while (!condition && cycles < maxCycles) {
      step
      cycles += 1
    }
    condition shouldBe true
  }

  behavior of "AetherCore v2 dual replay-safe Load exception ownership"

  it should "hold a misaligned pre-head Load fault until that exact token becomes head" in {
    simulate(new TinyDualReplaySafeLoadUnit(PageTableGeometry.Sv39)) { dut =>
      pokeEnvironment(dut)
      dispatchLoad(dut, base = 0x3002, index = 1, generation = 7)

      // TinyBlockingLsu creates the local alignment exception immediately after
      // intake. The dual-Load wrapper must retain it internally while non-head.
      dut.io.busy.expect(true.B)
      dut.io.completion.valid.expect(false.B)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      dut.io.busy.expect(true.B)
      dut.io.completion.valid.expect(false.B)

      // Once the same architectural lifetime becomes exact head, release the
      // held synchronous exception unchanged.
      dut.io.head.valid.poke(true.B)
      dut.io.head.bits.index.poke(1.U)
      dut.io.head.bits.generation.poke(7.U)

      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.robToken.index.expect(1.U)
      dut.io.completion.bits.robToken.generation.expect(7.U)
      dut.io.completion.bits.exception.valid.expect(true.B)
      dut.io.completion.bits.exception.cause.expect(
        MachineExceptionCode.LoadAddressMisaligned.U
      )
      dut.io.completion.bits.exception.value.expect(0x3002.U)
      dut.io.memoryRequest.valid.expect(false.B)

      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  it should "convert shared-PMP denial into an exact-head LoadAccessFault without external memory" in {
    simulate(new TinyDualReplaySafeLoadUnit(PageTableGeometry.Sv39)) { dut =>
      pokeEnvironment(dut)

      // S-mode with PMP enabled and every entry OFF has no matching permission:
      // the shared checker must deny the aligned ordinary Load locally.
      dut.io.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      dut.io.pmpEnabled.poke(true.B)
      dispatchLoad(dut, base = 0x5000, index = 3, generation = 11)

      // Keep the token speculative first. The wrapper may resolve/PMP-check it,
      // but denial must never become an external physical request and the
      // synchronous fault must remain owned until exact architectural head.
      for (_ <- 0 until 4) {
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.completion.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.busy.expect(true.B)

      dut.io.head.valid.poke(true.B)
      dut.io.head.bits.index.poke(3.U)
      dut.io.head.bits.generation.poke(11.U)

      stepUntil() {
        dut.io.completion.valid.peek().litValue == 1
      } {
        dut.io.memoryRequest.valid.expect(false.B)
        dut.clock.step()
      }

      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.completion.bits.robToken.index.expect(3.U)
      dut.io.completion.bits.robToken.generation.expect(11.U)
      dut.io.completion.bits.exception.valid.expect(true.B)
      dut.io.completion.bits.exception.cause.expect(MachineExceptionCode.LoadAccessFault.U)
      dut.io.completion.bits.exception.value.expect(0x5000.U)

      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  it should "preserve successful replay-safe pre-head Load completion" in {
    simulate(new TinyDualReplaySafeLoadUnit(PageTableGeometry.Sv39)) { dut =>
      pokeEnvironment(dut)
      dispatchLoad(dut, base = 0x4000, index = 2, generation = 9)

      // Keep the token non-head throughout. A safe physical read is still
      // allowed to externalize and complete before retirement.
      stepUntil() {
        dut.io.memoryRequest.valid.peek().litValue == 1
      } {
        dut.clock.step()
      }

      val externalTxn = dut.io.memoryRequest.bits.txnId.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(externalTxn.U)
      dut.io.memoryResponse.bits.rdata.poke("h1122334455667788".U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)

      stepUntil() {
        dut.io.completion.valid.peek().litValue == 1
      } {
        dut.clock.step()
      }

      dut.io.head.valid.expect(false.B)
      dut.io.completion.bits.robToken.index.expect(2.U)
      dut.io.completion.bits.robToken.generation.expect(9.U)
      dut.io.completion.bits.exception.valid.expect(false.B)
      dut.io.completion.bits.hasValue.expect(true.B)
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.busy.expect(false.B)
    }
  }
}
