package aethercore

import aethercore.soc.{AetherProductAxiColdBootIsolation, AetherProductDdrLocalAxiWindow, Axi4Burst}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AetherProductAxiColdBootIsolationSpec
    extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product FPGA cold boot five-channel AXI isolation"

  it should "block every AXI handshake until CPU release, then preserve IDs and data" in {
    simulate(new AetherProductAxiColdBootIsolation(32, 64, 5)) { dut =>
      dut.io.released.poke(false.B)
      dut.io.cpu.aw.valid.poke(true.B)
      dut.io.cpu.aw.bits.poke(0.U.asTypeOf(dut.io.cpu.aw.bits))
      dut.io.cpu.aw.bits.addr.poke("h80001000".U)
      dut.io.cpu.aw.bits.id.poke(13.U)
      dut.io.cpu.w.valid.poke(true.B)
      dut.io.cpu.w.bits.poke(0.U.asTypeOf(dut.io.cpu.w.bits))
      dut.io.cpu.w.bits.data.poke("h0123456789abcdef".U)
      dut.io.cpu.w.bits.strb.poke("hff".U)
      dut.io.cpu.w.bits.last.poke(true.B)
      dut.io.cpu.b.ready.poke(true.B)
      dut.io.cpu.ar.valid.poke(true.B)
      dut.io.cpu.ar.bits.poke(0.U.asTypeOf(dut.io.cpu.ar.bits))
      dut.io.cpu.ar.bits.addr.poke("h80002000".U)
      dut.io.cpu.ar.bits.id.poke(19.U)
      dut.io.cpu.r.ready.poke(true.B)

      dut.io.ddr.aw.ready.poke(true.B)
      dut.io.ddr.w.ready.poke(true.B)
      dut.io.ddr.b.valid.poke(true.B)
      dut.io.ddr.b.bits.poke(0.U.asTypeOf(dut.io.ddr.b.bits))
      dut.io.ddr.b.bits.id.poke(13.U)
      dut.io.ddr.ar.ready.poke(true.B)
      dut.io.ddr.r.valid.poke(true.B)
      dut.io.ddr.r.bits.poke(0.U.asTypeOf(dut.io.ddr.r.bits))
      dut.io.ddr.r.bits.id.poke(19.U)
      dut.io.ddr.r.bits.data.poke("hffeeddccbbaa9988".U)

      dut.io.ddr.aw.valid.expect(false.B)
      dut.io.ddr.w.valid.expect(false.B)
      dut.io.ddr.ar.valid.expect(false.B)
      dut.io.ddr.b.ready.expect(false.B)
      dut.io.ddr.r.ready.expect(false.B)
      dut.io.cpu.aw.ready.expect(false.B)
      dut.io.cpu.w.ready.expect(false.B)
      dut.io.cpu.ar.ready.expect(false.B)
      dut.io.cpu.b.valid.expect(false.B)
      dut.io.cpu.r.valid.expect(false.B)
      dut.clock.step()

      dut.io.released.poke(true.B)
      dut.io.ddr.aw.valid.expect(true.B)
      dut.io.ddr.aw.bits.addr.expect("h80001000".U)
      dut.io.ddr.aw.bits.id.expect(13.U)
      dut.io.ddr.w.valid.expect(true.B)
      dut.io.ddr.w.bits.data.expect("h0123456789abcdef".U)
      dut.io.ddr.ar.valid.expect(true.B)
      dut.io.ddr.ar.bits.addr.expect("h80002000".U)
      dut.io.ddr.ar.bits.id.expect(19.U)
      dut.io.ddr.b.ready.expect(true.B)
      dut.io.ddr.r.ready.expect(true.B)
      dut.io.cpu.aw.ready.expect(true.B)
      dut.io.cpu.w.ready.expect(true.B)
      dut.io.cpu.ar.ready.expect(true.B)
      dut.io.cpu.b.valid.expect(true.B)
      dut.io.cpu.b.bits.id.expect(13.U)
      dut.io.cpu.r.valid.expect(true.B)
      dut.io.cpu.r.bits.id.expect(19.U)
      dut.io.cpu.r.bits.data.expect("hffeeddccbbaa9988".U)

      // A PLL/DDR fault clamps both directions without waiting for a clock.
      dut.io.released.poke(false.B)
      dut.io.ddr.aw.valid.expect(false.B)
      dut.io.ddr.w.valid.expect(false.B)
      dut.io.ddr.ar.valid.expect(false.B)
      dut.io.ddr.b.ready.expect(false.B)
      dut.io.ddr.r.ready.expect(false.B)
    }
  }

  it should "block a byte write whose WSTRB targets another DDR byte lane" in {
    // This selected G2/FPGA suite also checks the downstream DDR window: the
    // CPU cannot rely on the vendor SmartConnect to repair malformed strobes.
    simulate(new AetherProductDdrLocalAxiWindow(32, 28, 4)) { dut =>
      dut.io.physical.aw.valid.poke(true.B)
      dut.io.physical.aw.bits.poke(0.U.asTypeOf(dut.io.physical.aw.bits))
      dut.io.physical.aw.bits.addr.poke("h80000003".U)
      dut.io.physical.aw.bits.size.poke(0.U)
      dut.io.physical.aw.bits.burst.poke(Axi4Burst.Incr)
      dut.io.physical.w.valid.poke(false.B)
      dut.io.physical.w.bits.poke(0.U.asTypeOf(dut.io.physical.w.bits))
      dut.io.physical.w.bits.last.poke(true.B)
      dut.io.physical.b.ready.poke(true.B)
      dut.io.physical.ar.valid.poke(false.B)
      dut.io.physical.ar.bits.poke(0.U.asTypeOf(dut.io.physical.ar.bits))
      dut.io.physical.r.ready.poke(true.B)
      dut.io.local.aw.ready.poke(true.B)
      dut.io.local.w.ready.poke(true.B)
      dut.io.local.b.valid.poke(false.B)
      dut.io.local.b.bits.poke(0.U.asTypeOf(dut.io.local.b.bits))
      dut.io.local.ar.ready.poke(true.B)
      dut.io.local.r.valid.poke(false.B)
      dut.io.local.r.bits.poke(0.U.asTypeOf(dut.io.local.r.bits))

      dut.io.local.aw.valid.expect(true.B)
      dut.clock.step() // AW accepted; lane mask must survive this edge.
      dut.io.physical.aw.valid.poke(false.B)
      dut.io.physical.w.valid.poke(true.B)
      dut.io.physical.w.bits.strb.poke("h01".U) // Only lane 3 is legal.
      dut.io.local.w.valid.expect(false.B)
      dut.io.physical.w.ready.expect(false.B)
      dut.clock.step()
      dut.io.stickyFatal.expect(true.B)
    }
  }
}
