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

/** Focused F7 proof for LR/SC/AMO at the AetherMem atomic boundary. */
trait V2F7AtomicLsuChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def initialize(dut: TinyBlockingLsu): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.storePermit.valid.poke(false.B)
    dut.io.storePermit.bits.index.poke(0.U)
    dut.io.storePermit.bits.generation.poke(0.U)
    dut.io.reservationClear.get.poke(false.B)

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

  private def dispatchAtomic(
      dut: TinyBlockingLsu,
      op: AtomicOp.Type,
      size: MemSize.Type,
      base: BigInt,
      storeData: BigInt = 0,
      index: Int = 1,
      generation: Int = 0,
      rawInst: BigInt = 0x1000302fL
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.robToken.index.poke(index.U)
    dut.io.request.bits.robToken.generation.poke(generation.U)
    dut.io.request.bits.producerTag.id.poke(index.U)
    dut.io.request.bits.producerTag.generation.poke(generation.U)
    dut.io.request.bits.valueRef.id.poke(index.U)
    dut.io.request.bits.valueRef.generation.poke(generation.U)
    dut.io.request.bits.kind.poke(MemoryOperationKind.Atomic)
    dut.io.request.bits.size.poke(size)
    dut.io.request.bits.unsigned.poke(false.B)
    dut.io.request.bits.atomicOp.poke(op)
    dut.io.request.bits.base.poke(base.U)
    dut.io.request.bits.offset.poke(0.U)
    dut.io.request.bits.storeData.poke(storeData.U)
    dut.io.request.bits.rawInst.poke(rawInst.U)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
    dut.io.busy.expect(true.B)
  }

  private def permit(dut: TinyBlockingLsu, index: Int, generation: Int): Unit = {
    dut.io.storePermit.valid.poke(true.B)
    dut.io.storePermit.bits.index.poke(index.U)
    dut.io.storePermit.bits.generation.poke(generation.U)
  }

  private def clearPermit(dut: TinyBlockingLsu): Unit =
    dut.io.storePermit.valid.poke(false.B)

  private def stepUntil(dut: TinyBlockingLsu, maxCycles: Int = 24)(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    condition shouldBe true
  }

  private def fireRequest(dut: TinyBlockingLsu): BigInt = {
    stepUntil(dut) { dut.io.memoryRequest.valid.peek().litToBoolean }
    val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
    dut.io.memoryRequest.ready.poke(true.B)
    dut.clock.step()
    dut.io.memoryRequest.ready.poke(false.B)
    txn
  }

  private def respond(dut: TinyBlockingLsu, txn: BigInt, rdata: BigInt): Unit = {
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.bits.txnId.poke(txn.U)
    dut.io.memoryResponse.bits.rdata.poke(rdata.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def finishResponse(dut: TinyBlockingLsu): Unit = {
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    clearPermit(dut)
    dut.io.busy.expect(false.B)
  }

  behavior of "AetherCore v2 F7 atomic LSU"

  it should "preserve LR/SC reservation semantics and issue indivisible AMOs through AetherMem" in {
    simulate(new TinyBlockingLsu(PageTableGeometry.Sv39, allowAtomics = true)) { dut =>
      initialize(dut)
      val address = BigInt("000000001000", 16)

      withClue("LR.D establishes a local reservation only after a successful Atomic response: ") {
        dispatchAtomic(dut, AtomicOp.Lr, MemSize.DWord, address, index = 1, generation = 0)
        stepUntil(dut) { dut.io.memoryRequest.valid.peek().litToBoolean }
        dut.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
        dut.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Lr)
        dut.io.memoryRequest.bits.paddr.expect(address.U)
        dut.io.memoryRequest.bits.wmask.expect(0.U)
        val txn = fireRequest(dut)
        respond(dut, txn, BigInt("1122334455667788", 16))
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.exception.valid.expect(false.B)
        dut.io.completion.bits.hasValue.expect(true.B)
        dut.io.completion.bits.value.expect(BigInt("1122334455667788", 16).U)
        dut.io.memoryTrace.valid.expect(true.B)
        dut.io.memoryTrace.bits.write.expect(false.B)
        dut.io.memoryTrace.bits.paddr.expect(address.U)
        finishResponse(dut)
      }

      withClue("SC.D needs exact-generation permit and lets memory remain the final success authority: ") {
        dispatchAtomic(
          dut,
          AtomicOp.Sc,
          MemSize.DWord,
          address,
          storeData = BigInt("8877665544332211", 16),
          index = 2,
          generation = 1
        )
        stepUntil(dut) { dut.io.resolvedPhysicalValid.peek().litToBoolean }
        dut.io.memoryRequest.valid.expect(false.B)

        permit(dut, index = 2, generation = 0)
        dut.io.memoryRequest.valid.expect(false.B)
        permit(dut, index = 2, generation = 1)
        dut.io.memoryRequest.valid.expect(true.B)
        dut.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
        dut.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Sc)
        dut.io.memoryRequest.bits.wdata.expect(BigInt("8877665544332211", 16).U)
        dut.io.memoryRequest.bits.wmask.expect("hff".U)
        val txn = fireRequest(dut)
        respond(dut, txn, 0)
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.hasValue.expect(true.B)
        dut.io.completion.bits.value.expect(0.U)
        dut.io.completion.bits.exception.valid.expect(false.B)
        dut.io.memoryTrace.valid.expect(true.B)
        dut.io.memoryTrace.bits.write.expect(true.B)
        dut.io.memoryTrace.bits.wdata.expect(BigInt("8877665544332211", 16).U)
        dut.io.memoryTrace.bits.wmask.expect("hff".U)
        finishResponse(dut)
      }

      withClue("a second SC without a fresh LR fails locally with rd=1 and no physical transaction: ") {
        dispatchAtomic(dut, AtomicOp.Sc, MemSize.DWord, address, storeData = 0x55, index = 3, generation = 1)
        permit(dut, index = 3, generation = 1)
        stepUntil(dut) { dut.io.completion.valid.peek().litToBoolean }
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.completion.bits.exception.valid.expect(false.B)
        dut.io.completion.bits.hasValue.expect(true.B)
        dut.io.completion.bits.value.expect(1.U)
        dut.io.memoryTrace.valid.expect(false.B)
        dut.clock.step()
        clearPermit(dut)
        dut.io.busy.expect(false.B)
      }

      withClue("an architectural reservation clear suppresses a later SC even after a successful new LR: ") {
        dispatchAtomic(dut, AtomicOp.Lr, MemSize.DWord, address, index = 0, generation = 2)
        val txn = fireRequest(dut)
        respond(dut, txn, 0x44)
        dut.io.completion.valid.expect(true.B)
        finishResponse(dut)

        dut.io.reservationClear.get.poke(true.B)
        dut.clock.step()
        dut.io.reservationClear.get.poke(false.B)

        dispatchAtomic(dut, AtomicOp.Sc, MemSize.DWord, address, storeData = 0x66, index = 1, generation = 2)
        permit(dut, index = 1, generation = 2)
        stepUntil(dut) { dut.io.completion.valid.peek().litToBoolean }
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.completion.bits.value.expect(1.U)
        dut.io.memoryTrace.valid.expect(false.B)
        dut.clock.step()
        clearPermit(dut)
      }

      withClue("AMOADD.W is one Atomic transaction, returns sign-extended old word and traces the masked new word: ") {
        dispatchAtomic(
          dut,
          AtomicOp.Add,
          MemSize.Word,
          address,
          storeData = 2,
          index = 2,
          generation = 3,
          rawInst = 0x00b5202fL
        )
        permit(dut, index = 2, generation = 3)
        stepUntil(dut) { dut.io.memoryRequest.valid.peek().litToBoolean }
        dut.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
        dut.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Add)
        dut.io.memoryRequest.bits.size.expect(MemSize.Word)
        dut.io.memoryRequest.bits.wmask.expect("h0f".U)
        val txn = fireRequest(dut)
        respond(dut, txn, BigInt("00000000ffffffff", 16))
        dut.io.completion.valid.expect(true.B)
        dut.io.completion.bits.hasValue.expect(true.B)
        dut.io.completion.bits.value.expect(BigInt("ffffffffffffffff", 16).U)
        dut.io.memoryTrace.valid.expect(true.B)
        dut.io.memoryTrace.bits.write.expect(true.B)
        dut.io.memoryTrace.bits.wmask.expect("h0f".U)
        (dut.io.memoryTrace.bits.wdata.peek().litValue & BigInt("ffffffff", 16)) shouldBe BigInt(1)
        finishResponse(dut)
      }

      withClue("PMA atomic denial is a store access fault and never leaks an external AMO: ") {
        dut.io.resolvedAttributes.supportsAtomic.poke(false.B)
        dispatchAtomic(dut, AtomicOp.Swap, MemSize.DWord, address, storeData = 0x77, index = 3, generation = 3)
        permit(dut, index = 3, generation = 3)
        stepUntil(dut) { dut.io.completion.valid.peek().litToBoolean }
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.completion.bits.exception.valid.expect(true.B)
        dut.io.completion.bits.exception.cause.expect(MachineExceptionCode.StoreAccessFault.U)
        dut.io.completion.bits.exception.value.expect(address.U)
        dut.io.completion.bits.hasValue.expect(false.B)
        dut.io.memoryTrace.valid.expect(false.B)
        dut.clock.step()
        clearPermit(dut)
        dut.io.busy.expect(false.B)
      }
    }
  }
}
