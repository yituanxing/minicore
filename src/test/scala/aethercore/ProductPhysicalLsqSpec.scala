package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize}
import aethercore.core.product._
import aethercore.core.semantic.MemoryOperationKind
import aethercore.memory.AetherMemOp

/** Dynamic physical LSQ protocols, not a source-only ownership check. */
class ProductPhysicalLsqSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product physical LQ2/SQ2 and separate physical transaction IDs"

  private def init(d: ProductPhysicalLsq): Unit = {
    d.io.reserve.valid.poke(false.B)
    d.io.reserve.bits.poke(0.U.asTypeOf(d.io.reserve.bits))
    d.io.resolve.valid.poke(false.B)
    d.io.resolve.bits.poke(0.U.asTypeOf(d.io.resolve.bits))
    d.io.robHead.valid.poke(false.B)
    d.io.robHead.bits.poke(0.U.asTypeOf(d.io.robHead.bits))
    d.io.kill.valid.poke(false.B)
    d.io.kill.bits.poke(0.U.asTypeOf(d.io.kill.bits))
    d.io.memoryRequest.ready.poke(true.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.io.completion.ready.poke(false.B)
    d.clock.step()
  }

  private def head(d: ProductPhysicalLsq, idx: Int, gen: Int = 0): Unit = {
    d.io.robHead.valid.poke(true.B)
    d.io.robHead.bits.index.poke(idx.U)
    d.io.robHead.bits.generation.poke(gen.U)
  }

  private def reserve(d: ProductPhysicalLsq, idx: Int, kind: MemoryOperationKind.Type,
                      size: MemSize.Type = MemSize.DWord, gen: Int = 0,
                      dest: Int = 0, writes: Boolean = false,
                      atomic: AtomicOp.Type = AtomicOp.None): Unit = {
    val r = d.io.reserve.bits
    r.token.index.poke(idx.U)
    r.token.generation.poke(gen.U)
    r.kind.poke(kind)
    r.size.poke(size)
    r.unsigned.poke(false.B)
    r.atomicOp.poke(atomic)
    r.destination.index.poke(dest.U)
    r.destination.generation.poke(1.U)
    r.hasDestination.poke(writes.B)
    d.io.reserve.valid.poke(true.B)
    d.io.reserve.ready.expect(true.B)
    d.clock.step()
    d.io.reserve.valid.poke(false.B)
  }

  private def resolve(d: ProductPhysicalLsq, idx: Int, addr: BigInt, data: BigInt = 0,
                      gen: Int = 0, ram: Boolean = true,
                      partial: Boolean = true, vaddr: Option[BigInt] = None): Unit = {
    val p = d.io.resolve.bits
    p.token.index.poke(idx.U)
    p.token.generation.poke(gen.U)
    p.virtualAddress.poke(vaddr.getOrElse(addr).U)
    p.paddr.poke(addr.U)
    p.storeData.poke(data.U)
    p.attributes.cacheable.poke(ram.B)
    p.attributes.idempotent.poke(ram.B)
    p.attributes.sideEffecting.poke((!ram).B)
    p.attributes.ordered.poke((!ram).B)
    p.attributes.executable.poke(ram.B)
    p.attributes.supportsAtomic.poke(ram.B)
    p.attributes.supportsPartial.poke(partial.B)
    p.fault.valid.poke(false.B)
    p.fault.cause.poke(0.U)
    p.fault.value.poke(0.U)
    d.io.resolve.valid.poke(true.B)
    d.io.resolve.ready.expect(true.B)
    d.clock.step()
    d.io.resolve.valid.poke(false.B)
  }

  private def response(d: ProductPhysicalLsq, tx: BigInt, value: BigInt = 0,
                       fault: Boolean = false): Unit = {
    d.io.memoryResponse.valid.poke(true.B)
    d.io.memoryResponse.bits.txnId.poke(tx.U)
    d.io.memoryResponse.bits.rdata.poke(value.U)
    d.io.memoryResponse.bits.fault.poke(fault.B)
    d.io.memoryResponse.bits.last.poke(true.B)
    d.io.memoryResponse.ready.expect(true.B)
    d.clock.step()
    d.io.memoryResponse.valid.poke(false.B)
  }

  private def receive(d: ProductPhysicalLsq, idx: Int, result: BigInt = 0,
                      writes: Boolean = false): Unit = {
    d.io.completion.valid.expect(true.B)
    d.io.completion.bits.token.index.expect(idx.U)
    d.io.completion.bits.hasValue.expect(writes.B)
    if (writes) d.io.completion.bits.value.expect(result.U)
    d.io.completion.bits.exception.valid.expect(false.B)
    d.io.completion.ready.poke(true.B)
    d.clock.step()
    d.io.completion.ready.poke(false.B)
  }

  it should "reuse compact six-entry physical slots across ROB wrap while preserving LQ and SQ quotas" in {
    val geometry = ProductCoreProfiles.smallGeometry.copy(
      robEntries = 16, loadQueueEntries = 3, storeQueueEntries = 3)
    simulate(new ProductPhysicalLsq(geometry)) { d =>
      init(d)
      head(d, 15)
      reserve(d, 0, MemoryOperationKind.Store)
      reserve(d, 1, MemoryOperationKind.Load, dest = 33, writes = true)
      resolve(d, 1, 0x80002000L)
      d.io.memoryRequest.valid.expect(false.B)
      resolve(d, 0, 0x80002000L, data = BigInt("cafebabedeadbeef", 16))
      d.io.forwarded.expect(true.B)
      d.clock.step()
      receive(d, 1, BigInt("cafebabedeadbeef", 16), writes = true)
      head(d, 0)
      d.io.memoryRequest.valid.expect(true.B)
      val oldTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, oldTxn)
      receive(d, 0)

      Seq(2, 3, 4).foreach(i => reserve(d, i, MemoryOperationKind.Load, writes = true))
      Seq(5, 6, 7).foreach(i => reserve(d, i, MemoryOperationKind.Store))
      d.io.loadCount.expect(3.U)
      d.io.storeCount.expect(3.U)
      d.io.reserve.bits.token.index.poke(8.U)
      d.io.reserve.bits.token.generation.poke(0.U)
      d.io.reserve.bits.kind.poke(MemoryOperationKind.Store)
      d.io.reserve.valid.poke(true.B)
      d.io.reserve.ready.expect(false.B)
      d.io.reserve.valid.poke(false.B)

      d.io.kill.valid.poke(true.B)
      d.io.kill.bits.index.poke(5.U)
      d.io.kill.bits.generation.poke(0.U)
      d.clock.step()
      d.io.kill.valid.poke(false.B)
      d.io.storeCount.expect(2.U)
      reserve(d, 8, MemoryOperationKind.Store)
      d.io.storeCount.expect(3.U)
      head(d, 2)
      resolve(d, 2, 0x80003000L)
      d.io.memoryRequest.valid.expect(true.B)
      val liveTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, liveTxn, value = 0x55L)
      receive(d, 2, 0x55L, writes = true)
    }
  }

  it should "reserve older unknown Store before younger Load and forward completed full-cover data" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 7) // older instruction is ahead of Store0, so Store0 is not head
      reserve(d, 0, MemoryOperationKind.Store)
      reserve(d, 1, MemoryOperationKind.Load, dest = 33, writes = true)
      resolve(d, 1, 0x80000000L)
      d.io.memoryRequest.valid.expect(false.B) // unresolved older Store blocks
      d.io.completion.valid.expect(false.B)

      resolve(d, 0, 0x80000000L, data = BigInt("123456789abcdef0", 16))
      d.io.memoryRequest.valid.expect(false.B) // Store still not commit-permitted
      d.io.forwarded.expect(true.B)
      d.clock.step()
      receive(d, 1, BigInt("123456789abcdef0", 16), writes = true)
      d.io.storeCount.expect(1.U)
      head(d, 0)
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Write)
      d.io.memoryRequest.bits.wdata.expect(BigInt("123456789abcdef0", 16).U)
      d.io.memoryRequest.bits.wmask.expect(255.U)
      val txn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, txn)
      receive(d, 0)
      d.io.loadCount.expect(0.U)
      d.io.storeCount.expect(0.U)
      d.io.outstanding.expect(0.U)
    }
  }

  it should "lease external txn ID across squash and drain old response after ROB index reuse" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 0)
      reserve(d, 0, MemoryOperationKind.Load, gen = 0, dest = 32, writes = true)
      resolve(d, 0, 0x80001000L)
      d.io.memoryRequest.valid.expect(true.B)
      val oldTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      d.io.outstanding.expect(1.U)

      d.io.kill.valid.poke(true.B)
      d.io.kill.bits.index.poke(0.U)
      d.io.kill.bits.generation.poke(0.U)
      d.clock.step()
      d.io.kill.valid.poke(false.B)
      // Even if the ROB's generation counter eventually wraps, an identical
      // lifetime may not be reserved while its old physical txn ID is live.
      d.io.reserve.bits.token.index.poke(0.U)
      d.io.reserve.bits.token.generation.poke(0.U)
      d.io.reserve.bits.kind.poke(MemoryOperationKind.Load)
      d.io.reserve.valid.poke(true.B)
      d.io.reserve.ready.expect(false.B)
      d.io.reserve.valid.poke(false.B)
      head(d, 0, 1)
      reserve(d, 0, MemoryOperationKind.Load, gen = 1, dest = 34, writes = true)
      // Old translation response must drain without poisoning the reused slot.
      val old = d.io.resolve.bits
      old.token.index.poke(0.U)
      old.token.generation.poke(0.U)
      d.io.resolve.valid.poke(true.B)
      d.io.resolve.ready.expect(true.B)
      d.io.droppedStaleResolution.expect(true.B)
      d.clock.step()
      d.io.resolve.valid.poke(false.B)
      resolve(d, 0, 0x80002000L, gen = 1)
      d.io.memoryRequest.valid.expect(true.B)
      val newTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      newTxn should not be oldTxn
      d.clock.step()
      d.io.outstanding.expect(2.U)
      d.io.memoryResponse.valid.poke(true.B)
      d.io.memoryResponse.bits.txnId.poke(oldTxn.U)
      d.io.memoryResponse.bits.rdata.poke(999.U)
      d.io.memoryResponse.bits.fault.poke(false.B)
      d.io.memoryResponse.bits.last.poke(true.B)
      d.io.drainedKilledResponse.expect(true.B)
      d.clock.step()
      d.io.memoryResponse.valid.poke(false.B)
      d.io.completion.valid.expect(false.B)
      response(d, newTxn, 17)
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.token.generation.expect(1.U)
      receive(d, 0, 17, writes = true)
      d.io.outstanding.expect(0.U)
    }
  }

  it should "serialize a non-idempotent MMIO Load at exact ROB head" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 7)
      reserve(d, 0, MemoryOperationKind.Load, gen = 0, dest = 33, writes = true)
      resolve(d, 0, 0x10000000L, ram = false)
      d.io.memoryRequest.valid.expect(false.B)
      head(d, 0)
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
      val txn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, txn, 0xabcd)
      receive(d, 0, 0xabcd, writes = true)
    }
  }

  it should "stall overlapping partial Store forwarding rather than return stale data" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 7)
      reserve(d, 0, MemoryOperationKind.Store, size = MemSize.Byte)
      reserve(d, 1, MemoryOperationKind.Load, size = MemSize.Word, dest = 32, writes = true)
      resolve(d, 0, 0x80003000L, data = 0x5aL)
      resolve(d, 1, 0x80003000L)
      d.io.forwarded.expect(false.B)
      d.io.memoryRequest.valid.expect(false.B)
      head(d, 0)
      d.io.memoryRequest.valid.expect(true.B)
      val txn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      // After Store ACK, the younger safe RAM Load may issue even before
      // Store retirement. Hold ready until the test observes that request.
      d.io.memoryRequest.ready.poke(false.B)
      response(d, txn)
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
      receive(d, 0)
      head(d, 1)
      d.io.memoryRequest.valid.expect(true.B)
      val loadTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.io.memoryRequest.ready.poke(true.B)
      d.clock.step()
      response(d, loadTxn, 0x12345aL)
      receive(d, 1, 0x12345aL, writes = true)
    }
  }

  it should "turn unaligned resolved accesses into local precise faults" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 0)
      reserve(d, 0, MemoryOperationKind.Load, size = MemSize.Word, dest = 34, writes = true)
      resolve(d, 0, 0x80004003L, vaddr = Some(0x40004003L))
      d.io.memoryRequest.valid.expect(false.B)
      d.clock.step()
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.hasValue.expect(true.B)
      d.io.completion.bits.exception.valid.expect(true.B)
      d.io.completion.bits.exception.cause.expect(MachineExceptionCode.LoadAddressMisaligned.U)
      d.io.completion.bits.exception.value.expect(0x40004003L.U)
      d.io.completion.ready.poke(true.B)
      d.clock.step()
      d.io.outstanding.expect(0.U)
    }
  }

  it should "refuse a narrow Store when the resolved PMA region disallows partial writes" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 0)
      reserve(d, 0, MemoryOperationKind.Store, size = MemSize.Byte)
      resolve(d, 0, 0x10000000L, data = 0x5aL, ram = false,
        partial = false, vaddr = Some(0x40000000L))
      d.io.memoryRequest.valid.expect(false.B)
      d.clock.step()
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.token.index.expect(0.U)
      d.io.completion.bits.exception.valid.expect(true.B)
      d.io.completion.bits.exception.cause.expect(MachineExceptionCode.StoreAccessFault.U)
      d.io.completion.bits.exception.value.expect(0x40000000L.U)
      d.io.outstanding.expect(0.U)
      d.io.completion.ready.poke(true.B)
      d.clock.step()
      d.io.storeCount.expect(0.U)
    }
  }

  it should "preserve destination identity on a faulting physical Load for precise ROB trap" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 0)
      reserve(d, 0, MemoryOperationKind.Load, size = MemSize.Word, dest = 34, writes = true)
      resolve(d, 0, 0x80005000L, vaddr = Some(0x40005000L))
      d.io.memoryRequest.valid.expect(true.B)
      val txn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, txn, fault = true)
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.hasValue.expect(true.B)
      d.io.completion.bits.exception.valid.expect(true.B)
      d.io.completion.bits.exception.cause.expect(MachineExceptionCode.LoadAccessFault.U)
      d.io.completion.bits.exception.value.expect(0x40005000L.U)
      d.io.completion.ready.poke(true.B)
      d.clock.step()
      d.io.outstanding.expect(0.U)
    }
  }

  it should "enforce independent LQ and SQ capacities at reservation" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      reserve(d, 0, MemoryOperationKind.Load)
      reserve(d, 1, MemoryOperationKind.Load)
      d.io.loadCount.expect(2.U)
      d.io.reserve.bits.kind.poke(MemoryOperationKind.Load)
      d.io.reserve.bits.token.index.poke(2.U)
      d.io.reserve.valid.poke(true.B)
      d.io.reserve.ready.expect(false.B)
      d.io.reserve.valid.poke(false.B)
      reserve(d, 3, MemoryOperationKind.Store)
      reserve(d, 4, MemoryOperationKind.Store)
      d.io.storeCount.expect(2.U)
      d.io.reserve.bits.kind.poke(MemoryOperationKind.Store)
      d.io.reserve.bits.token.index.poke(5.U)
      d.io.reserve.valid.poke(true.B)
      d.io.reserve.ready.expect(false.B)
      d.io.reserve.valid.poke(false.B)
      d.io.outstanding.expect(0.U)
    }
  }

  it should "hold younger RAM loads behind an older Atomic until its exact-head external response" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 7)
      reserve(d, 0, MemoryOperationKind.Atomic, size = MemSize.Word,
        dest = 34, writes = true, atomic = AtomicOp.Swap)
      reserve(d, 1, MemoryOperationKind.Load, size = MemSize.Word,
        dest = 35, writes = true)
      resolve(d, 0, 0x80006000L, data = 1)
      resolve(d, 1, 0x80008000L)
      d.io.storeCount.expect(1.U)
      d.io.loadCount.expect(1.U)
      d.io.memoryRequest.valid.expect(false.B)
      head(d, 0)
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
      d.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Swap)
      d.io.memoryRequest.bits.wmask.expect("hf".U)
      val atomicTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      d.io.memoryRequest.valid.expect(false.B) // younger Load remains held
      response(d, atomicTxn, BigInt("80000001", 16))
      d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
      val loadTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      receive(d, 0, BigInt("ffffffff80000001", 16), writes = true)
      head(d, 1)
      d.io.memoryRequest.ready.poke(true.B)
      d.clock.step()
      response(d, loadTxn, 0x55)
      receive(d, 1, 0x55, writes = true)
      d.io.storeCount.expect(0.U)
      d.io.loadCount.expect(0.U)
      d.io.outstanding.expect(0.U)
    }
  }

  it should "drain a killed Atomic transaction and keep its new ROB generation separate" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 0)
      reserve(d, 0, MemoryOperationKind.Atomic, size = MemSize.DWord,
        dest = 33, writes = true, atomic = AtomicOp.Add)
      resolve(d, 0, 0x80006000L, data = 2)
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
      d.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Add)
      d.io.memoryRequest.bits.wmask.expect("hff".U)
      val oldTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()

      d.io.kill.valid.poke(true.B)
      d.io.kill.bits.index.poke(0.U)
      d.io.kill.bits.generation.poke(0.U)
      d.clock.step()
      d.io.kill.valid.poke(false.B)
      head(d, 0, 1)
      reserve(d, 0, MemoryOperationKind.Atomic, size = MemSize.DWord,
        gen = 1, dest = 34, writes = true, atomic = AtomicOp.Swap)
      resolve(d, 0, 0x80006008L, data = 3, gen = 1)
      d.io.memoryRequest.valid.expect(true.B)
      val newTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      newTxn should not be oldTxn
      d.clock.step()

      d.io.memoryResponse.valid.poke(true.B)
      d.io.memoryResponse.bits.txnId.poke(oldTxn.U)
      d.io.memoryResponse.bits.rdata.poke(0x99.U)
      d.io.memoryResponse.bits.fault.poke(false.B)
      d.io.memoryResponse.bits.last.poke(true.B)
      d.io.drainedKilledResponse.expect(true.B)
      d.clock.step()
      d.io.memoryResponse.valid.poke(false.B)
      d.io.completion.valid.expect(false.B)
      response(d, newTxn, BigInt("abcdef0123456789", 16))
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.token.generation.expect(1.U)
      receive(d, 0, BigInt("abcdef0123456789", 16), writes = true)
      d.io.outstanding.expect(0.U)
    }
  }

  it should "deny Atomic MMIO locally with original virtual fault address" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 0)
      reserve(d, 0, MemoryOperationKind.Atomic, size = MemSize.Word,
        dest = 34, writes = true, atomic = AtomicOp.Sc)
      resolve(d, 0, 0x10000000L, data = 2, ram = false,
        vaddr = Some(0x40000000L))
      d.io.memoryRequest.valid.expect(false.B)
      d.clock.step()
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.hasValue.expect(true.B)
      d.io.completion.bits.exception.valid.expect(true.B)
      d.io.completion.bits.exception.cause.expect(MachineExceptionCode.StoreAccessFault.U)
      d.io.completion.bits.exception.value.expect(0x40000000L.U)
      d.io.completion.ready.poke(true.B)
      d.clock.step()
      d.io.storeCount.expect(0.U)
      d.io.outstanding.expect(0.U)
    }
  }
}
