package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.soc.{AetherMemToAxi4Bridge, Axi4Resp}

class AetherMemToAxi4BridgeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherMemToAxi4Bridge"

  private def initialize(dut: AetherMemToAxi4Bridge): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.txnId.poke(0.U)
    dut.io.request.bits.op.poke(AetherMemOp.Read)
    dut.io.request.bits.paddr.poke(0.U)
    dut.io.request.bits.size.poke(MemSize.DWord)
    dut.io.request.bits.wdata.poke(0.U)
    dut.io.request.bits.wmask.poke("hff".U)
    dut.io.request.bits.atomicOp.poke(AtomicOp.None)
    dut.io.request.bits.attributes.cacheable.poke(true.B)
    dut.io.request.bits.attributes.idempotent.poke(true.B)
    dut.io.request.bits.attributes.sideEffecting.poke(false.B)
    dut.io.request.bits.attributes.ordered.poke(false.B)
    dut.io.request.bits.attributes.executable.poke(true.B)
    dut.io.request.bits.attributes.supportsAtomic.poke(true.B)
    dut.io.request.bits.attributes.supportsPartial.poke(true.B)
    dut.io.response.ready.poke(true.B)

    dut.io.axi.aw.ready.poke(true.B)
    dut.io.axi.w.ready.poke(true.B)
    dut.io.axi.b.valid.poke(false.B)
    dut.io.axi.b.bits.id.poke(0.U)
    dut.io.axi.b.bits.resp.poke(Axi4Resp.Okay)
    dut.io.axi.ar.ready.poke(true.B)
    dut.io.axi.r.valid.poke(false.B)
    dut.io.axi.r.bits.id.poke(0.U)
    dut.io.axi.r.bits.data.poke(0.U)
    dut.io.axi.r.bits.resp.poke(Axi4Resp.Okay)
    dut.io.axi.r.bits.last.poke(true.B)
  }

  private def issue(
      dut: AetherMemToAxi4Bridge,
      txnId: Int,
      op: AetherMemOp.Type,
      address: BigInt,
      size: MemSize.Type,
      data: BigInt = 0,
      mask: BigInt = 0xff,
      atomic: AtomicOp.Type = AtomicOp.None
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.txnId.poke(txnId.U)
    dut.io.request.bits.op.poke(op)
    dut.io.request.bits.paddr.poke(address.U)
    dut.io.request.bits.size.poke(size)
    dut.io.request.bits.wdata.poke(data.U)
    dut.io.request.bits.wmask.poke(mask.U)
    dut.io.request.bits.atomicOp.poke(atomic)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)

    // Captured request dispatches one cycle before entering an AXI channel state.
    dut.clock.step()
  }

  it should "preserve byte addresses while shifting narrow read data to AetherMem lane zero" in {
    simulate(new AetherMemToAxi4Bridge()) { dut =>
      initialize(dut)

      val address = BigInt("80000003", 16)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.txnId.poke(3.U)
      dut.io.request.bits.op.poke(AetherMemOp.Read)
      dut.io.request.bits.paddr.poke(address.U)
      dut.io.request.bits.size.poke(MemSize.Byte)

      dut.io.request.ready.expect(true.B)
      dut.io.axi.ar.valid.expect(true.B)
      dut.io.axi.ar.bits.id.expect(3.U)
      dut.io.axi.ar.bits.addr.expect(address.U)
      dut.io.axi.ar.bits.len.expect(0.U)
      dut.io.axi.ar.bits.size.expect(0.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.axi.r.valid.poke(true.B)
      dut.io.axi.r.bits.id.poke(3.U)
      dut.io.axi.r.bits.data.poke(BigInt("00000000ab000000", 16).U)
      dut.io.axi.r.bits.resp.poke(Axi4Resp.Okay)
      dut.io.axi.r.bits.last.poke(true.B)

      dut.io.axi.r.ready.expect(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(3.U)
      dut.io.response.bits.rdata.expect(0xab.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false.B)

      dut.io.request.ready.expect(true.B)
    }
  }

  it should "shift narrow writes onto the addressed AXI byte lanes and wait for B" in {
    simulate(new AetherMemToAxi4Bridge()) { dut =>
      initialize(dut)

      val address = BigInt("80000005", 16)
      issue(
        dut,
        txnId = 2,
        op = AetherMemOp.Write,
        address = address,
        size = MemSize.Half,
        data = 0xbeef,
        mask = 0x3
      )

      dut.io.axi.aw.valid.expect(true.B)
      dut.io.axi.aw.bits.id.expect(2.U)
      dut.io.axi.aw.bits.addr.expect(address.U)
      dut.io.axi.aw.bits.len.expect(0.U)
      dut.io.axi.aw.bits.size.expect(1.U)

      dut.io.axi.w.valid.expect(true.B)
      dut.io.axi.w.bits.data.expect(BigInt("00beef0000000000", 16).U)
      dut.io.axi.w.bits.strb.expect("h60".U)
      dut.io.axi.w.bits.last.expect(true.B)
      dut.clock.step()

      dut.io.axi.b.valid.poke(true.B)
      dut.io.axi.b.bits.id.poke(2.U)
      dut.io.axi.b.bits.resp.poke(Axi4Resp.Okay)
      dut.io.axi.b.ready.expect(true.B)
      dut.clock.step()
      dut.io.axi.b.valid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(2.U)
      dut.io.response.bits.rdata.expect(0.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.clock.step()
      dut.io.request.ready.expect(true.B)
    }
  }

  it should "convert AXI read response errors into AetherMem faults" in {
    simulate(new AetherMemToAxi4Bridge()) { dut =>
      initialize(dut)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.txnId.poke(1.U)
      dut.io.request.bits.op.poke(AetherMemOp.Read)
      dut.io.request.bits.paddr.poke(BigInt("80001000", 16).U)
      dut.io.request.bits.size.poke(MemSize.DWord)
      dut.io.request.ready.expect(true.B)
      dut.io.axi.ar.valid.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.axi.r.valid.poke(true.B)
      dut.io.axi.r.bits.id.poke(1.U)
      dut.io.axi.r.bits.data.poke("h1122334455667788".U)
      dut.io.axi.r.bits.resp.poke(Axi4Resp.SlvErr)
      dut.io.axi.r.bits.last.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(1.U)
      dut.io.response.bits.fault.expect(true.B)
      dut.clock.step()
    }
  }

  it should "allow multiple normal reads outstanding and accept out-of-order AXI responses" in {
    simulate(new AetherMemToAxi4Bridge()) { dut =>
      initialize(dut)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.op.poke(AetherMemOp.Read)
      dut.io.request.bits.txnId.poke(1.U)
      dut.io.request.bits.paddr.poke(BigInt("80000001", 16).U)
      dut.io.request.bits.size.poke(MemSize.Byte)
      dut.io.request.ready.expect(true.B)
      dut.io.axi.ar.valid.expect(true.B)
      dut.io.axi.ar.bits.id.expect(1.U)
      dut.clock.step()

      dut.io.request.bits.txnId.poke(2.U)
      dut.io.request.bits.paddr.poke(BigInt("80000006", 16).U)
      dut.io.request.bits.size.poke(MemSize.Half)
      dut.io.request.ready.expect(true.B)
      dut.io.axi.ar.valid.expect(true.B)
      dut.io.axi.ar.bits.id.expect(2.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      // Return ID 2 before ID 1. Its halfword lives in byte lanes 6..7.
      dut.io.axi.r.valid.poke(true.B)
      dut.io.axi.r.bits.id.poke(2.U)
      dut.io.axi.r.bits.data.poke(BigInt("beef000000000000", 16).U)
      dut.io.axi.r.bits.resp.poke(Axi4Resp.Okay)
      dut.io.axi.r.bits.last.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(2.U)
      dut.io.response.bits.rdata.expect(0xbeef.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.clock.step()

      dut.io.axi.r.bits.id.poke(1.U)
      dut.io.axi.r.bits.data.poke(BigInt("000000000000aa00", 16).U)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(1.U)
      dut.io.response.bits.rdata.expect(0xaa.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false.B)

      dut.io.request.ready.expect(true.B)
    }
  }

  it should "hold writes behind the concurrent read drain barrier" in {
    simulate(new AetherMemToAxi4Bridge()) { dut =>
      initialize(dut)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.op.poke(AetherMemOp.Read)
      dut.io.request.bits.txnId.poke(1.U)
      dut.io.request.bits.paddr.poke(BigInt("80002000", 16).U)
      dut.io.request.bits.size.poke(MemSize.DWord)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      // Present a write while the read is still outstanding: it must stall.
      dut.io.request.bits.op.poke(AetherMemOp.Write)
      dut.io.request.bits.txnId.poke(3.U)
      dut.io.request.bits.paddr.poke(BigInt("80003000", 16).U)
      dut.io.request.bits.wdata.poke("hdeadbeef".U)
      dut.io.request.bits.wmask.poke("hff".U)
      dut.io.request.ready.expect(false.B)
      dut.io.axi.aw.valid.expect(false.B)

      // Drain the read.
      dut.io.axi.r.valid.poke(true.B)
      dut.io.axi.r.bits.id.poke(1.U)
      dut.io.axi.r.bits.data.poke(0.U)
      dut.io.axi.r.bits.resp.poke(Axi4Resp.Okay)
      dut.io.axi.r.bits.last.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false.B)

      // The held write may enter only after the read lifetime is gone.
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.clock.step()

      dut.io.axi.aw.valid.expect(true.B)
      dut.io.axi.w.valid.expect(true.B)
    }
  }

}
