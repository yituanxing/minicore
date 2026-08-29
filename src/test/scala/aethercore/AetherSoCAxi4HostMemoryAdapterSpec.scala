package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.soc.{Axi4Burst, Axi4Resp}
import aethercore.sim.AetherSoCAxi4HostMemoryAdapter

class AetherSoCAxi4HostMemoryAdapterSpec
    extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherSoCAxi4HostMemoryAdapter"

  private def initialize(dut: AetherSoCAxi4HostMemoryAdapter): Unit = {
    dut.io.axi.aw.valid.poke(false.B)
    dut.io.axi.aw.bits.id.poke(0.U)
    dut.io.axi.aw.bits.addr.poke(0.U)
    dut.io.axi.aw.bits.len.poke(0.U)
    dut.io.axi.aw.bits.size.poke(3.U)
    dut.io.axi.aw.bits.burst.poke(Axi4Burst.Incr)
    dut.io.axi.aw.bits.lock.poke(false.B)
    dut.io.axi.aw.bits.cache.poke(0.U)
    dut.io.axi.aw.bits.prot.poke(0.U)
    dut.io.axi.aw.bits.qos.poke(0.U)

    dut.io.axi.w.valid.poke(false.B)
    dut.io.axi.w.bits.data.poke(0.U)
    dut.io.axi.w.bits.strb.poke(0.U)
    dut.io.axi.w.bits.last.poke(true.B)
    dut.io.axi.b.ready.poke(true.B)

    dut.io.axi.ar.valid.poke(false.B)
    dut.io.axi.ar.bits.id.poke(0.U)
    dut.io.axi.ar.bits.addr.poke(0.U)
    dut.io.axi.ar.bits.len.poke(0.U)
    dut.io.axi.ar.bits.size.poke(3.U)
    dut.io.axi.ar.bits.burst.poke(Axi4Burst.Incr)
    dut.io.axi.ar.bits.lock.poke(false.B)
    dut.io.axi.ar.bits.cache.poke(0.U)
    dut.io.axi.ar.bits.prot.poke(0.U)
    dut.io.axi.ar.bits.qos.poke(0.U)
    dut.io.axi.r.ready.poke(true.B)

    dut.io.imemInst.poke(0.U)
    dut.io.imemFault.poke(false.B)
    dut.io.ptwReady.poke(false.B)
    dut.io.ptwRdata.poke(0.U)
    dut.io.ptwFault.poke(false.B)
    dut.io.memReady.poke(false.B)
    dut.io.memRdata.poke(0.U)
    dut.io.memFault.poke(false.B)
  }

  private def presentRead(
      dut: AetherSoCAxi4HostMemoryAdapter,
      id: Int,
      address: BigInt,
      size: Int = 3
  ): Unit = {
    dut.io.axi.ar.valid.poke(true.B)
    dut.io.axi.ar.bits.id.poke(id.U)
    dut.io.axi.ar.bits.addr.poke(address.U)
    dut.io.axi.ar.bits.len.poke(0.U)
    dut.io.axi.ar.bits.size.poke(size.U)
    dut.io.axi.ar.bits.burst.poke(Axi4Burst.Incr)
  }

  it should "keep data PTW and instruction reads independently outstanding" in {
    simulate(new AetherSoCAxi4HostMemoryAdapter()) { dut =>
      initialize(dut)

      // ID[3:2] source tags: 0=data, 1=PTW, 2=instruction.
      presentRead(dut, id = 1, address = BigInt("80000000", 16))
      dut.io.axi.ar.ready.expect(true.B)
      dut.clock.step()

      // A second data-source read is blocked while its source slot is occupied.
      presentRead(dut, id = 2, address = BigInt("80000008", 16))
      dut.io.axi.ar.ready.expect(false.B)

      // PTW is independent and can enter immediately.
      presentRead(dut, id = 5, address = BigInt("80001000", 16))
      dut.io.axi.ar.ready.expect(true.B)
      dut.clock.step()

      // Instruction is independent as well.
      presentRead(dut, id = 9, address = BigInt("80002000", 16), size = 2)
      dut.io.axi.ar.ready.expect(true.B)
      dut.clock.step()
      dut.io.axi.ar.valid.poke(false.B)

      dut.io.memValid.expect(true.B)
      dut.io.memWrite.expect(false.B)
      dut.io.ptwValid.expect(true.B)
      dut.io.imemValid.expect(true.B)

      // Instruction is terminal immediately and may return ahead of older IDs.
      dut.io.imemInst.poke("h11223344".U)
      dut.io.axi.r.valid.expect(true.B)
      dut.io.axi.r.bits.id.expect(9.U)
      dut.io.axi.r.bits.resp.expect(Axi4Resp.Okay)
      dut.clock.step()

      // Then make PTW terminal.
      dut.io.ptwRdata.poke("h5566778899aabbcc".U)
      dut.io.ptwReady.poke(true.B)
      dut.io.axi.r.valid.expect(true.B)
      dut.io.axi.r.bits.id.expect(5.U)
      dut.clock.step()
      dut.io.ptwReady.poke(false.B)

      // Finally retire the oldest data read.
      dut.io.memRdata.poke("hdeadbeefcafef00d".U)
      dut.io.memReady.poke(true.B)
      dut.io.axi.r.valid.expect(true.B)
      dut.io.axi.r.bits.id.expect(1.U)
      dut.io.axi.r.bits.data.expect("hdeadbeefcafef00d".U)
      dut.clock.step()
      dut.io.memReady.poke(false.B)

      dut.io.memValid.expect(false.B)
      dut.io.ptwValid.expect(false.B)
      dut.io.imemValid.expect(false.B)
    }
  }

  it should "preserve lane placement for an out-of-order narrow response" in {
    simulate(new AetherSoCAxi4HostMemoryAdapter()) { dut =>
      initialize(dut)

      // Data byte at lane 3 stays lane-aligned on AXI R.
      presentRead(dut, id = 1, address = BigInt("80000003", 16), size = 0)
      dut.io.axi.ar.ready.expect(true.B)
      dut.clock.step()
      dut.io.axi.ar.valid.poke(false.B)

      dut.io.memRdata.poke("hab".U)
      dut.io.memReady.poke(true.B)
      dut.io.axi.r.valid.expect(true.B)
      dut.io.axi.r.bits.id.expect(1.U)
      dut.io.axi.r.bits.data.expect(BigInt("00000000ab000000", 16).U)
      dut.clock.step()
    }
  }
}
