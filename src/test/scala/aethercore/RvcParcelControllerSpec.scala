package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.RvcParcelController

class RvcParcelControllerSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "XLEN-aware RvcParcelController"

  private val pc = BigInt("0000000080000ffe", 16)

  private def initialize(dut: RvcParcelController): Unit = {
    dut.io.instructionPc.poke(pc.U)
    dut.io.kill.poke(false.B)
    dut.io.advance.poke(true.B)
    dut.io.parcelResponseValid.poke(true.B)
    dut.io.parcelBits.poke(0.U)
    dut.io.parcelPageFault.poke(false.B)
    dut.io.parcelAccessFault.poke(false.B)
  }

  it should "complete an RV64C instruction in one parcel and preserve instBytes=2" in {
    simulate(new RvcParcelController(64)) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke("h3575".U) // C.ADDIW a0, a0, -3

      dut.io.parcelRequestAddress.expect(pc.U)
      dut.io.instructionValid.expect(true.B)
      dut.io.instruction.expect("hffd5051b".U)
      dut.io.rawInstruction.expect("h00003575".U)
      dut.io.instructionBytes.expect(2.U)
      dut.io.faultAddress.expect(pc.U)
    }
  }

  it should "assemble an uncompressed RV64 instruction from PC and PC+2 parcels" in {
    simulate(new RvcParcelController(64)) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke("h051b".U)
      dut.io.instructionValid.expect(false.B)
      dut.clock.step()

      dut.io.parcelRequestAddress.expect((pc + 2).U)
      dut.io.parcelBits.poke("hffd5".U)
      dut.io.instructionValid.expect(true.B)
      dut.io.instruction.expect("hffd5051b".U)
      dut.io.rawInstruction.expect("hffd5051b".U)
      dut.io.instructionBytes.expect(4.U)
      dut.io.faultAddress.expect((pc + 2).U)
    }
  }

  it should "report a second-parcel RV64 fetch fault at PC+2" in {
    simulate(new RvcParcelController(64)) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke("h051b".U)
      dut.clock.step()

      dut.io.parcelBits.poke(0.U)
      dut.io.parcelPageFault.poke(true.B)
      dut.io.instructionValid.expect(true.B)
      dut.io.instructionBytes.expect(4.U)
      dut.io.faultAddress.expect((pc + 2).U)
      dut.io.pageFault.expect(true.B)
    }
  }
}
