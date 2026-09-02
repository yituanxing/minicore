package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.{AetherDirectMappedReadCache, AetherMemOp}

class AetherDirectMappedReadCacheSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "stage-1 AetherMem direct-mapped D-cache"

  private def defaults(dut: AetherDirectMappedReadCache): Unit = {
    dut.io.upstreamRequest.valid.poke(false.B)
    dut.io.upstreamResponse.ready.poke(true.B)
    dut.io.downstreamRequest.ready.poke(true.B)
    dut.io.downstreamResponse.valid.poke(false.B)
    dut.io.downstreamResponse.bits.txnId.poke(0.U)
    dut.io.downstreamResponse.bits.rdata.poke(0.U)
    dut.io.downstreamResponse.bits.fault.poke(false.B)
    dut.io.downstreamResponse.bits.last.poke(true.B)
  }

  private def driveRequest(
      dut: AetherDirectMappedReadCache,
      txn: Int,
      op: AetherMemOp.Type,
      addr: BigInt,
      size: MemSize.Type,
      cacheable: Boolean,
      wdata: BigInt = 0,
      wmask: BigInt = 0,
      atomicOp: AtomicOp.Type = AtomicOp.None
  ): Unit = {
    dut.io.upstreamRequest.valid.poke(true.B)
    dut.io.upstreamRequest.bits.txnId.poke(txn.U)
    dut.io.upstreamRequest.bits.op.poke(op)
    dut.io.upstreamRequest.bits.paddr.poke(addr.U)
    dut.io.upstreamRequest.bits.size.poke(size)
    dut.io.upstreamRequest.bits.wdata.poke(wdata.U)
    dut.io.upstreamRequest.bits.wmask.poke(wmask.U)
    dut.io.upstreamRequest.bits.atomicOp.poke(atomicOp)
    dut.io.upstreamRequest.bits.attributes.cacheable.poke(cacheable.B)
    dut.io.upstreamRequest.bits.attributes.idempotent.poke(cacheable.B)
    dut.io.upstreamRequest.bits.attributes.sideEffecting.poke((!cacheable).B)
    dut.io.upstreamRequest.bits.attributes.ordered.poke((!cacheable).B)
    dut.io.upstreamRequest.bits.attributes.executable.poke(false.B)
    dut.io.upstreamRequest.bits.attributes.supportsAtomic.poke(cacheable.B)
    dut.io.upstreamRequest.bits.attributes.supportsPartial.poke(true.B)
  }

  private def acceptForwarded(dut: AetherDirectMappedReadCache): Unit = {
    dut.io.downstreamRequest.valid.expect(true.B)
    dut.io.upstreamRequest.ready.expect(true.B)
    dut.clock.step()
    dut.io.upstreamRequest.valid.poke(false.B)
  }

  private def returnDownstream(
      dut: AetherDirectMappedReadCache,
      txn: Int,
      data: BigInt,
      fault: Boolean = false
  ): Unit = {
    dut.io.downstreamResponse.valid.poke(true.B)
    dut.io.downstreamResponse.bits.txnId.poke(txn.U)
    dut.io.downstreamResponse.bits.rdata.poke(data.U)
    dut.io.downstreamResponse.bits.fault.poke(fault.B)
    dut.io.downstreamResponse.bits.last.poke(true.B)
    dut.io.upstreamResponse.valid.expect(true.B)
    dut.io.upstreamResponse.bits.txnId.expect(txn.U)
    dut.io.upstreamResponse.bits.rdata.expect(data.U)
    dut.clock.step()
    dut.io.downstreamResponse.valid.poke(false.B)
  }

  it should "ignore stale byte-valid RAM after line invalidation" in {
    simulate(new AetherDirectMappedReadCache(56, 64, 2, entries = 64)) { dut =>
      defaults(dut)

      // First populate a full word so the underlying byte-valid RAM contains
      // multiple valid bits.
      driveRequest(dut, 0, AetherMemOp.Read, 0x2000, MemSize.Word, cacheable = true)
      acceptForwarded(dut)
      returnDownstream(dut, 0, 0x44332211L)

      // A write-through request invalidates ownership of the cached line.
      driveRequest(
        dut, 1, AetherMemOp.Write, 0x2000, MemSize.Word, cacheable = true,
        wdata = 0xdeadbeefL, wmask = 0xf
      )
      acceptForwarded(dut)
      returnDownstream(dut, 1, 0)

      // Refill just one byte. Since lineValid was cleared, the refill must
      // rebuild byte-valid state from zero rather than reusing stale bits.
      driveRequest(dut, 2, AetherMemOp.Read, 0x2001, MemSize.Byte, cacheable = true)
      acceptForwarded(dut)
      returnDownstream(dut, 2, 0xaa)

      // A wider access must still miss; stale pre-invalidation byte-valid bits
      // must never make this look like a complete word hit.
      driveRequest(dut, 3, AetherMemOp.Read, 0x2000, MemSize.Word, cacheable = true)
      dut.io.downstreamRequest.valid.expect(true.B)
      dut.io.upstreamRequest.ready.expect(true.B)
    }
  }

  it should "fill only fetched bytes, hit repeated reads, and bypass/invalidate writers" in {
    simulate(new AetherDirectMappedReadCache(56, 64, 2, entries = 64)) { dut =>
      defaults(dut)

      withClue("narrow miss/fill/hit: ") {
        driveRequest(dut, 0, AetherMemOp.Read, 0x1003, MemSize.Byte, cacheable = true)
        acceptForwarded(dut)
        returnDownstream(dut, 0, 0xab)

        driveRequest(dut, 1, AetherMemOp.Read, 0x1003, MemSize.Byte, cacheable = true)
        dut.io.downstreamRequest.valid.expect(false.B)
        dut.io.upstreamRequest.ready.expect(true.B)
        dut.clock.step()
        dut.io.upstreamRequest.valid.poke(false.B)
        dut.io.upstreamResponse.valid.expect(true.B)
        dut.io.upstreamResponse.bits.txnId.expect(1.U)
        dut.io.upstreamResponse.bits.rdata.expect(0xab.U)
        dut.clock.step()
        dut.io.hitCount.expect(1.U)

        // A wider read cannot consume bytes that the narrow fill never fetched.
        driveRequest(dut, 2, AetherMemOp.Read, 0x1000, MemSize.Word, cacheable = true)
        dut.io.downstreamRequest.valid.expect(true.B)
        dut.clock.step()
        dut.io.upstreamRequest.valid.poke(false.B)
        returnDownstream(dut, 2, 0x44332211L)
      }

      withClue("write-through invalidation: ") {
        driveRequest(
          dut, 3, AetherMemOp.Write, 0x1000, MemSize.Word, cacheable = true,
          wdata = 0xdeadbeefL, wmask = 0xf
        )
        acceptForwarded(dut)
        returnDownstream(dut, 3, 0)

        driveRequest(dut, 0, AetherMemOp.Read, 0x1000, MemSize.Word, cacheable = true)
        dut.io.downstreamRequest.valid.expect(true.B)
        dut.clock.step()
        dut.io.upstreamRequest.valid.poke(false.B)
        returnDownstream(dut, 0, 0xdeadbeefL)
      }

      withClue("atomic and MMIO bypass: ") {
        driveRequest(
          dut, 1, AetherMemOp.Atomic, 0x1000, MemSize.DWord, cacheable = true,
          atomicOp = AtomicOp.Swap
        )
        dut.io.downstreamRequest.valid.expect(true.B)
        dut.clock.step()
        dut.io.upstreamRequest.valid.poke(false.B)
        returnDownstream(dut, 1, 0x55)

        driveRequest(dut, 2, AetherMemOp.Read, 0x10000000, MemSize.Byte, cacheable = false)
        dut.io.downstreamRequest.valid.expect(true.B)
        dut.clock.step()
        dut.io.upstreamRequest.valid.poke(false.B)
        returnDownstream(dut, 2, 0x5a)
        dut.io.bypassCount.peek().litValue should be >= BigInt(2)
      }
    }
  }
}
