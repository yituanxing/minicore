package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{MachineExceptionCode, MemSize}
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
                      dest: Int = 0, writes: Boolean = false): Unit = {
    val r = d.io.reserve.bits
    r.token.index.poke(idx.U)
    r.token.generation.poke(gen.U)
    r.kind.poke(kind)
    r.size.poke(size)
    r.unsigned.poke(false.B)
    r.destination.index.poke(dest.U)
    r.destination.generation.poke(1.U)
    r.hasDestination.poke(writes.B)
    d.io.reserve.valid.poke(true.B)
    d.io.reserve.ready.expect(true.B)
    d.clock.step()
    d.io.reserve.valid.poke(false.B)
  }

  private def resolve(d: ProductPhysicalLsq, idx: Int, addr: BigInt, data: BigInt = 0,
                      gen: Int = 0, ram: Boolean = true): Unit = {
    val p = d.io.resolve.bits
    p.token.index.poke(idx.U)
    p.token.generation.poke(gen.U)
    p.paddr.poke(addr.U)
    p.storeData.poke(data.U)
    p.attributes.cacheable.poke(ram.B)
    p.attributes.idempotent.poke(ram.B)
    p.attributes.sideEffecting.poke((!ram).B)
    p.attributes.ordered.poke((!ram).B)
    p.attributes.executable.poke(ram.B)
    p.attributes.supportsAtomic.poke(ram.B)
    p.attributes.supportsPartial.poke(true.B)
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
      response(d, txn)
      receive(d, 0)
      head(d, 1)
      d.io.memoryRequest.valid.expect(true.B)
      val loadTxn = d.io.memoryRequest.bits.txnId.peek().litValue
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
      resolve(d, 0, 0x80004003L)
      d.io.memoryRequest.valid.expect(false.B)
      d.clock.step()
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.hasValue.expect(true.B)
      d.io.completion.bits.exception.valid.expect(true.B)
      d.io.completion.bits.exception.cause.expect(MachineExceptionCode.LoadAddressMisaligned.U)
      d.io.completion.bits.exception.value.expect(0x80004003L.U)
      d.io.completion.ready.poke(true.B)
      d.clock.step()
      d.io.outstanding.expect(0.U)
    }
  }

  it should "preserve destination identity on a faulting physical Load for precise ROB trap" in {
    simulate(new ProductPhysicalLsq(ProductCoreProfiles.smallGeometry)) { d =>
      init(d)
      head(d, 0)
      reserve(d, 0, MemoryOperationKind.Load, size = MemSize.Word, dest = 34, writes = true)
      resolve(d, 0, 0x80005000L)
      d.io.memoryRequest.valid.expect(true.B)
      val txn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, txn, fault = true)
      d.io.completion.valid.expect(true.B)
      d.io.completion.bits.hasValue.expect(true.B)
      d.io.completion.bits.exception.valid.expect(true.B)
      d.io.completion.bits.exception.cause.expect(MachineExceptionCode.LoadAccessFault.U)
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
}
