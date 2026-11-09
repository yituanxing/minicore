package aethercore

import aethercore.soc.{AetherProductBoardReadyQualifier, AetherProductDdrLocalAxiWindow, Axi4Burst, Axi4Resp}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The FPGA DDR handoff must be correct before adding any vendor-specific PHY. */
class AetherProductDdrLocalAxiWindowSpec
    extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product FPGA DDR physical-to-local AXI seam"

  private val RamBase = BigInt("80000000", 16)

  private def initialize(dut: AetherProductDdrLocalAxiWindow): Unit = {
    dut.io.physical.aw.valid.poke(false.B)
    dut.io.physical.aw.bits.id.poke(0.U)
    dut.io.physical.aw.bits.addr.poke(RamBase.U)
    dut.io.physical.aw.bits.len.poke(0.U)
    dut.io.physical.aw.bits.size.poke(3.U)
    dut.io.physical.aw.bits.burst.poke(Axi4Burst.Incr)
    dut.io.physical.aw.bits.lock.poke(false.B)
    dut.io.physical.aw.bits.cache.poke(0.U)
    dut.io.physical.aw.bits.prot.poke(0.U)
    dut.io.physical.aw.bits.qos.poke(0.U)

    dut.io.physical.w.valid.poke(false.B)
    dut.io.physical.w.bits.data.poke(0.U)
    dut.io.physical.w.bits.strb.poke("hff".U)
    dut.io.physical.w.bits.last.poke(true.B)
    dut.io.physical.b.ready.poke(true.B)

    dut.io.physical.ar.valid.poke(false.B)
    dut.io.physical.ar.bits.id.poke(0.U)
    dut.io.physical.ar.bits.addr.poke(RamBase.U)
    dut.io.physical.ar.bits.len.poke(0.U)
    dut.io.physical.ar.bits.size.poke(3.U)
    dut.io.physical.ar.bits.burst.poke(Axi4Burst.Incr)
    dut.io.physical.ar.bits.lock.poke(false.B)
    dut.io.physical.ar.bits.cache.poke(0.U)
    dut.io.physical.ar.bits.prot.poke(0.U)
    dut.io.physical.ar.bits.qos.poke(0.U)
    dut.io.physical.r.ready.poke(true.B)

    dut.io.local.aw.ready.poke(true.B)
    dut.io.local.w.ready.poke(true.B)
    dut.io.local.b.valid.poke(false.B)
    dut.io.local.b.bits.id.poke(0.U)
    dut.io.local.b.bits.resp.poke(Axi4Resp.Okay)
    dut.io.local.ar.ready.poke(true.B)
    dut.io.local.r.valid.poke(false.B)
    dut.io.local.r.bits.id.poke(0.U)
    dut.io.local.r.bits.data.poke(0.U)
    dut.io.local.r.bits.resp.poke(Axi4Resp.Okay)
    dut.io.local.r.bits.last.poke(true.B)
  }

  private def presentRead(dut: AetherProductDdrLocalAxiWindow, id: Int, address: BigInt): Unit = {
    dut.io.physical.ar.valid.poke(true.B)
    dut.io.physical.ar.bits.id.poke(id.U)
    dut.io.physical.ar.bits.addr.poke(address.U)
  }

  it should "translate the complete 256MiB window without aliasing and retire out-of-order IDs" in {
    simulate(new AetherProductDdrLocalAxiWindow(32, 28, 4)) { dut =>
      initialize(dut)

      presentRead(dut, 1, RamBase)
      dut.io.local.ar.valid.expect(true.B)
      dut.io.local.ar.bits.addr.expect(0.U)
      dut.clock.step()

      presentRead(dut, 2, BigInt("8ffffff8", 16))
      dut.io.local.ar.valid.expect(true.B)
      dut.io.local.ar.bits.addr.expect(BigInt("0ffffff8", 16).U)
      dut.clock.step()

      // A duplicate ID is backpressured, not treated as a board fault.
      presentRead(dut, 1, RamBase + 8)
      dut.io.physical.ar.ready.expect(false.B)
      dut.io.local.ar.valid.expect(false.B)
      dut.io.stickyFatal.expect(false.B)
      dut.io.physical.ar.valid.poke(false.B)

      dut.io.local.r.valid.poke(true.B)
      dut.io.local.r.bits.id.poke(2.U)
      dut.io.local.r.bits.data.poke("h123456789abcdef0".U)
      dut.io.physical.r.valid.expect(true.B)
      dut.io.physical.r.bits.id.expect(2.U)
      dut.clock.step()

      dut.io.local.r.bits.id.poke(1.U)
      dut.io.physical.r.valid.expect(true.B)
      dut.clock.step()
      dut.io.local.r.valid.poke(false.B)

      presentRead(dut, 1, RamBase + 8)
      dut.io.physical.ar.ready.expect(true.B)
      dut.io.local.ar.bits.addr.expect(8.U)
      dut.io.stickyFatal.expect(false.B)
    }
  }

  it should "preserve narrow byte size, byte address and high AXI ID without aliasing" in {
    simulate(new AetherProductDdrLocalAxiWindow(32, 28, 4)) { dut =>
      initialize(dut)
      presentRead(dut, 15, RamBase + 3)
      dut.io.physical.ar.bits.size.poke(0.U)
      dut.io.local.ar.valid.expect(true.B)
      dut.io.local.ar.bits.id.expect(15.U)
      dut.io.local.ar.bits.addr.expect(3.U)
      dut.io.local.ar.bits.size.expect(0.U)
      dut.clock.step()
      dut.io.physical.ar.valid.poke(false.B)

      dut.io.local.r.valid.poke(true.B)
      dut.io.local.r.bits.id.poke(15.U)
      dut.io.physical.r.valid.expect(true.B)
      dut.io.physical.r.bits.id.expect(15.U)
      dut.clock.step()
      dut.io.local.r.valid.poke(false.B)

      // A word at a byte-misaligned address must not silently be rounded
      // down and written to a different DDR location by the board.
      presentRead(dut, 1, RamBase + 3)
      dut.io.physical.ar.bits.size.poke(2.U)
      dut.io.local.ar.valid.expect(false.B)
      dut.io.physical.ar.ready.expect(false.B)
      dut.clock.step()
      dut.io.stickyFatal.expect(true.B)
    }
  }

  it should "allow W before AW and preserve write identity through B" in {
    simulate(new AetherProductDdrLocalAxiWindow(32, 28, 4)) { dut =>
      initialize(dut)
      dut.io.physical.aw.valid.poke(true.B)
      dut.io.physical.aw.bits.id.poke(3.U)
      dut.io.physical.aw.bits.addr.poke((RamBase + 24).U)
      dut.io.physical.w.valid.poke(true.B)
      dut.io.physical.w.bits.data.poke("hdeadbeefcafebabe".U)
      dut.io.local.aw.ready.poke(false.B)

      dut.io.local.w.valid.expect(true.B)
      dut.io.physical.w.ready.expect(true.B)
      dut.io.physical.aw.ready.expect(false.B)
      dut.io.local.aw.bits.addr.expect(24.U)
      dut.clock.step()

      dut.io.physical.w.valid.poke(false.B)
      dut.io.local.w.valid.expect(false.B)
      dut.io.local.aw.ready.poke(true.B)
      dut.io.physical.aw.ready.expect(true.B)
      dut.clock.step()

      dut.io.physical.aw.valid.poke(false.B)
      dut.io.local.b.valid.poke(true.B)
      dut.io.local.b.bits.id.poke(3.U)
      dut.io.physical.b.valid.expect(true.B)
      dut.io.physical.b.bits.id.expect(3.U)
      dut.clock.step()
      dut.io.local.b.valid.poke(false.B)
      dut.io.stickyFatal.expect(false.B)
    }
  }

  it should "accept only the addressed byte lane for a narrow W-before-AW write" in {
    simulate(new AetherProductDdrLocalAxiWindow(32, 28, 4)) { dut =>
      initialize(dut)
      dut.io.physical.w.valid.poke(true.B)
      dut.io.physical.w.bits.strb.poke("h08".U)
      // Independent WVALID may wait for a legal AW without becoming fatal.
      dut.io.local.w.valid.expect(false.B)
      dut.clock.step()
      dut.io.stickyFatal.expect(false.B)

      dut.io.physical.aw.valid.poke(true.B)
      dut.io.physical.aw.bits.id.poke(3.U)
      dut.io.physical.aw.bits.addr.poke((RamBase + 3).U)
      dut.io.physical.aw.bits.size.poke(0.U)
      dut.io.local.aw.ready.poke(false.B)
      dut.io.local.w.valid.expect(true.B)
      dut.io.local.w.bits.strb.expect("h08".U)
      dut.io.physical.w.ready.expect(true.B)
      dut.clock.step()

      dut.io.physical.w.valid.poke(false.B)
      dut.io.local.aw.ready.poke(true.B)
      dut.io.local.aw.bits.addr.expect(3.U)
      dut.clock.step()
      dut.io.physical.aw.valid.poke(false.B)
      dut.io.local.b.valid.poke(true.B)
      dut.io.local.b.bits.id.poke(3.U)
      dut.io.physical.b.valid.expect(true.B)
      dut.clock.step()
      dut.io.stickyFatal.expect(false.B)
    }
  }

  it should "block an illegal WSTRB both before and after an accepted narrow AW" in {
    for (awFirst <- Seq(false, true)) {
      simulate(new AetherProductDdrLocalAxiWindow(32, 28, 4)) { dut =>
        initialize(dut)
        dut.io.physical.aw.valid.poke(true.B)
        dut.io.physical.aw.bits.id.poke(5.U)
        dut.io.physical.aw.bits.addr.poke((RamBase + 3).U)
        dut.io.physical.aw.bits.size.poke(0.U)
        dut.io.local.aw.ready.poke(awFirst.B)
        if (awFirst) {
          dut.clock.step()
          dut.io.physical.aw.valid.poke(false.B)
        }
        dut.io.physical.w.valid.poke(true.B)
        dut.io.physical.w.bits.strb.poke("h01".U)
        dut.io.local.w.valid.expect(false.B)
        dut.io.physical.w.ready.expect(false.B)
        dut.clock.step()
        dut.io.stickyFatal.expect(true.B)
        dut.io.local.w.valid.expect(false.B)
      }
    }
  }

  it should "make out-of-range and malformed DDR transactions sticky-fatal" in {
    simulate(new AetherProductDdrLocalAxiWindow(32, 28, 4)) { dut =>
      initialize(dut)
      dut.io.physical.aw.valid.poke(true.B)
      dut.io.physical.aw.bits.addr.poke(BigInt("90000000", 16).U)
      dut.io.physical.w.valid.poke(true.B)
      dut.io.local.aw.valid.expect(false.B)
      dut.io.local.w.valid.expect(false.B)
      dut.io.physical.aw.ready.expect(false.B)
      dut.clock.step()
      dut.io.stickyFatal.expect(true.B)
      dut.io.physical.aw.bits.addr.poke(RamBase.U)
      dut.io.local.aw.valid.expect(false.B)
    }
  }

  it should "reject a DDR read response with an unissued ID" in {
    simulate(new AetherProductDdrLocalAxiWindow(32, 28, 4)) { dut =>
      initialize(dut)
      presentRead(dut, 1, RamBase)
      dut.clock.step()
      dut.io.physical.ar.valid.poke(false.B)
      dut.io.local.r.valid.poke(true.B)
      dut.io.local.r.bits.id.poke(2.U)
      dut.io.physical.r.valid.expect(false.B)
      dut.io.local.r.ready.expect(true.B)
      dut.clock.step()
      dut.io.stickyFatal.expect(true.B)
    }
  }
}

class AetherProductBoardReadyQualifierSpec
    extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product FPGA PLL and DDR readiness qualifier"

  it should "require three healthy clocks and requalify after DDR calibration loss" in {
    simulate(new AetherProductBoardReadyQualifier) { dut =>
      dut.io.rawClockLocked.poke(false.B)
      dut.io.rawDdrCalibrated.poke(false.B)
      dut.clock.step(3)
      dut.io.clockLocked.expect(false.B)
      dut.io.ddrCalibrated.expect(false.B)

      dut.io.rawClockLocked.poke(true.B)
      dut.io.rawDdrCalibrated.poke(true.B)
      dut.io.clockLocked.expect(false.B)
      dut.io.ddrCalibrated.expect(false.B)
      dut.clock.step(2)
      dut.io.clockLocked.expect(false.B)
      dut.io.ddrCalibrated.expect(false.B)
      dut.clock.step()
      dut.io.clockLocked.expect(true.B)
      dut.io.ddrCalibrated.expect(true.B)

      dut.io.rawDdrCalibrated.poke(false.B)
      dut.io.ddrCalibrated.expect(false.B)
      dut.io.clockLocked.expect(true.B)
      dut.clock.step()
      dut.io.rawDdrCalibrated.poke(true.B)
      dut.io.ddrCalibrated.expect(false.B)
      dut.clock.step(3)
      dut.io.ddrCalibrated.expect(true.B)

      dut.io.rawClockLocked.poke(false.B)
      dut.io.clockLocked.expect(false.B)
      dut.io.ddrCalibrated.expect(false.B)
    }
  }
}
