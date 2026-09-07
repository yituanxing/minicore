package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.sim.AetherSoCUnifiedHostMemoryAdapter

class AetherSoCUnifiedHostMemoryAdapterSpec
    extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherSoCUnifiedHostMemoryAdapter"

  private def defaults(dut: AetherSoCUnifiedHostMemoryAdapter): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.response.ready.poke(true.B)

    dut.io.imemInst.poke(0.U)
    dut.io.imemFault.poke(false.B)
    dut.io.ptwReady.poke(false.B)
    dut.io.ptwRdata.poke(0.U)
    dut.io.ptwFault.poke(false.B)
    dut.io.memReady.poke(false.B)
    dut.io.memRdata.poke(0.U)
    dut.io.memFault.poke(false.B)
  }

  private def driveRequest(
      dut: AetherSoCUnifiedHostMemoryAdapter,
      txn: Int,
      op: AetherMemOp.Type,
      addr: BigInt,
      size: MemSize.Type,
      cacheable: Boolean = true,
      idempotent: Boolean = true,
      sideEffecting: Boolean = false,
      ordered: Boolean = false,
      wdata: BigInt = 0,
      wmask: BigInt = 0,
      atomicOp: AtomicOp.Type = AtomicOp.None
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.txnId.poke(txn.U)
    dut.io.request.bits.op.poke(op)
    dut.io.request.bits.paddr.poke(addr.U)
    dut.io.request.bits.size.poke(size)
    dut.io.request.bits.wdata.poke(wdata.U)
    dut.io.request.bits.wmask.poke(wmask.U)
    dut.io.request.bits.atomicOp.poke(atomicOp)
    dut.io.request.bits.attributes.cacheable.poke(cacheable.B)
    dut.io.request.bits.attributes.idempotent.poke(idempotent.B)
    dut.io.request.bits.attributes.sideEffecting.poke(sideEffecting.B)
    dut.io.request.bits.attributes.ordered.poke(ordered.B)
    dut.io.request.bits.attributes.executable.poke(false.B)
    dut.io.request.bits.attributes.supportsAtomic.poke(true.B)
    dut.io.request.bits.attributes.supportsPartial.poke(true.B)
  }

  it should "retain independent ordinary Data read lifetimes by local txnId" in {
    simulate(new AetherSoCUnifiedHostMemoryAdapter()) { dut =>
      defaults(dut)

      driveRequest(dut, txn = 0, AetherMemOp.Read, BigInt("80000000", 16), MemSize.DWord)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      // Keep the first host read live. A different local Data txnId must still
      // be accepted, matching the production AXI bridge's concurrent-read rule.
      driveRequest(dut, txn = 1, AetherMemOp.Read, BigInt("80000008", 16), MemSize.DWord)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      // Reuse of a live local ID is rejected.
      driveRequest(dut, txn = 0, AetherMemOp.Read, BigInt("80000010", 16), MemSize.DWord)
      dut.io.request.ready.expect(false.B)
      dut.io.request.valid.poke(false.B)

      dut.io.memValid.expect(true.B)
      dut.io.memWrite.expect(false.B)
      dut.io.memAddr.expect(BigInt("80000000", 16).U)

      // The historical host port still services one read per cycle; txn
      // identity is preserved on each AetherMem response.
      dut.io.memRdata.poke("h1111222233334444".U)
      dut.io.memReady.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(0.U)
      dut.io.response.bits.rdata.expect("h1111222233334444".U)
      dut.clock.step()

      dut.io.memAddr.expect(BigInt("80000008", 16).U)
      dut.io.memRdata.poke("haaaabbbbccccdddd".U)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(1.U)
      dut.io.response.bits.rdata.expect("haaaabbbbccccdddd".U)
      dut.clock.step()

      dut.io.memReady.poke(false.B)
      dut.io.memValid.expect(false.B)
    }
  }

  it should "serialize writes and atomics only after all normal reads drain" in {
    simulate(new AetherSoCUnifiedHostMemoryAdapter()) { dut =>
      defaults(dut)

      // Hold a normal Data read live.
      driveRequest(dut, txn = 0, AetherMemOp.Read, BigInt("80000100", 16), MemSize.DWord)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      // A write must not enter while any normal read is outstanding.
      driveRequest(
        dut, txn = 2, AetherMemOp.Write, BigInt("80000200", 16), MemSize.Word,
        wdata = BigInt("deadbeef", 16), wmask = 0xf
      )
      dut.io.request.ready.expect(false.B)

      // Drain the read.
      dut.io.request.valid.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.clock.step()
      dut.io.memReady.poke(false.B)

      // The write may now enter and owns the Data boundary exclusively.
      driveRequest(
        dut, txn = 2, AetherMemOp.Write, BigInt("80000200", 16), MemSize.Word,
        wdata = BigInt("deadbeef", 16), wmask = 0xf
      )
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      // Ordinary Data and PTW reads are blocked while the serialized request is
      // live, matching AetherMemToAxi4Bridge's drain-and-own policy.
      driveRequest(dut, txn = 1, AetherMemOp.Read, BigInt("80000300", 16), MemSize.DWord)
      dut.io.request.ready.expect(false.B)

      driveRequest(dut, txn = 4, AetherMemOp.Read, BigInt("80001000", 16), MemSize.DWord)
      dut.io.request.ready.expect(false.B)
      dut.io.request.valid.poke(false.B)

      dut.io.memValid.expect(true.B)
      dut.io.memWrite.expect(true.B)
      dut.io.memAddr.expect(BigInt("80000200", 16).U)
      dut.io.memWdata.expect(BigInt("deadbeef", 16).U)

      dut.io.memReady.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(2.U)
      dut.clock.step()
      dut.io.memReady.poke(false.B)

      // Once the serialized response drains, a normal read can enter again.
      driveRequest(dut, txn = 1, AetherMemOp.Read, BigInt("80000300", 16), MemSize.DWord)
      dut.io.request.ready.expect(true.B)
    }
  }
}
