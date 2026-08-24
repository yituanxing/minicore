package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.{Rv32CParcelController, RvcParcelController}

class Rv32CParcelControllerSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "XLEN-aware RVC parcel control"

  private val pc = BigInt("80000ffe", 16)
  private val rv64Pc = BigInt("0000000080000ffe", 16)

  private def initialize(dut: RvcParcelController, startPc: BigInt = pc): Unit = {
    dut.io.instructionPc.poke(startPc.U)
    dut.io.kill.poke(false.B)
    dut.io.advance.poke(true.B)
    dut.io.parcelResponseValid.poke(true.B)
    dut.io.parcelBits.poke(0.U)
    dut.io.parcelPageFault.poke(false.B)
    dut.io.parcelAccessFault.poke(false.B)
  }

  it should "complete a compressed instruction after exactly one parcel" in {
    simulate(new Rv32CParcelController) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke("h12e5".U)

      dut.io.parcelRequestAddress.expect(pc.U)
      dut.io.parcelResponseReady.expect(true.B)
      dut.io.instructionValid.expect(true.B)
      dut.io.instruction.expect("hff928293".U)
      dut.io.rawInstruction.expect("h000012e5".U)
      dut.io.instructionBytes.expect(2.U)
      dut.io.faultAddress.expect(pc.U)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)
    }
  }

  it should "assemble a 32-bit instruction from two independently accepted parcels" in {
    simulate(new Rv32CParcelController) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke("h8313".U)

      dut.io.parcelRequestAddress.expect(pc.U)
      dut.io.instructionValid.expect(false.B)
      dut.io.parcelResponseReady.expect(true.B)
      dut.clock.step()

      dut.io.parcelRequestAddress.expect((pc + 2).U)
      dut.io.parcelBits.poke("h00a2".U)
      dut.io.instructionValid.expect(true.B)
      dut.io.instruction.expect("h00a28313".U)
      dut.io.rawInstruction.expect("h00a28313".U)
      dut.io.instructionBytes.expect(4.U)
      dut.io.faultAddress.expect((pc + 2).U)

      dut.clock.step()
      dut.io.parcelRequestAddress.expect(pc.U)
    }
  }

  it should "hold the first parcel until the frontend can advance" in {
    simulate(new Rv32CParcelController) { dut =>
      initialize(dut)
      dut.io.advance.poke(false.B)
      dut.io.parcelBits.poke("h8313".U)
      dut.io.parcelResponseReady.expect(false.B)
      dut.io.instructionValid.expect(false.B)
      dut.clock.step()
      dut.io.parcelRequestAddress.expect(pc.U)

      dut.io.advance.poke(true.B)
      dut.io.parcelResponseReady.expect(true.B)
      dut.clock.step()
      dut.io.parcelRequestAddress.expect((pc + 2).U)
    }
  }

  it should "report a second-parcel page fault at PC+2 while preserving a four-byte instruction" in {
    simulate(new Rv32CParcelController) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke("h8313".U)
      dut.clock.step()

      dut.io.parcelBits.poke(0.U)
      dut.io.parcelPageFault.poke(true.B)
      dut.io.instructionValid.expect(true.B)
      dut.io.instructionBytes.expect(4.U)
      dut.io.faultAddress.expect((pc + 2).U)
      dut.io.pageFault.expect(true.B)
      dut.io.accessFault.expect(false.B)
    }
  }

  it should "report a second-parcel access fault at PC+2" in {
    simulate(new Rv32CParcelController) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke("h8313".U)
      dut.clock.step()

      dut.io.parcelBits.poke(0.U)
      dut.io.parcelAccessFault.poke(true.B)
      dut.io.instructionValid.expect(true.B)
      dut.io.faultAddress.expect((pc + 2).U)
      dut.io.accessFault.expect(true.B)
    }
  }

  it should "turn an illegal compressed encoding into decoder-visible illegal bits while preserving raw mtval bits" in {
    simulate(new Rv32CParcelController) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke(0.U)
      dut.io.instructionValid.expect(true.B)
      dut.io.instruction.expect(0.U)
      dut.io.rawInstruction.expect(0.U)
      dut.io.instructionBytes.expect(2.U)
    }
  }

  it should "discard a saved first parcel on a frontend kill" in {
    simulate(new Rv32CParcelController) { dut =>
      initialize(dut)
      dut.io.parcelBits.poke("h8313".U)
      dut.clock.step()
      dut.io.parcelRequestAddress.expect((pc + 2).U)

      dut.io.kill.poke(true.B)
      dut.io.instructionValid.expect(false.B)
      dut.io.parcelResponseReady.expect(false.B)
      dut.clock.step()

      dut.io.kill.poke(false.B)
      dut.io.parcelRequestAddress.expect(pc.U)
    }
  }

  it should "complete an RV64C instruction in one parcel with instBytes equal to two" in {
    simulate(new RvcParcelController(64)) { dut =>
      initialize(dut, rv64Pc)
      dut.io.parcelBits.poke("h3575".U) // C.ADDIW a0, a0, -3

      dut.io.parcelRequestAddress.expect(rv64Pc.U)
      dut.io.parcelResponseReady.expect(true.B)
      dut.io.instructionValid.expect(true.B)
      dut.io.instruction.expect("hffd5051b".U)
      dut.io.rawInstruction.expect("h00003575".U)
      dut.io.instructionBytes.expect(2.U)
      dut.io.faultAddress.expect(rv64Pc.U)
    }
  }

  it should "assemble an uncompressed RV64 instruction from PC and PC+2 parcels" in {
    simulate(new RvcParcelController(64)) { dut =>
      initialize(dut, rv64Pc)
      dut.io.parcelBits.poke("h051b".U)
      dut.io.instructionValid.expect(false.B)
      dut.clock.step()

      dut.io.parcelRequestAddress.expect((rv64Pc + 2).U)
      dut.io.parcelBits.poke("hffd5".U)
      dut.io.instructionValid.expect(true.B)
      dut.io.instruction.expect("hffd5051b".U)
      dut.io.rawInstruction.expect("hffd5051b".U)
      dut.io.instructionBytes.expect(4.U)
      dut.io.faultAddress.expect((rv64Pc + 2).U)
    }
  }

  it should "report an RV64 second-parcel page fault at PC+2" in {
    simulate(new RvcParcelController(64)) { dut =>
      initialize(dut, rv64Pc)
      dut.io.parcelBits.poke("h051b".U)
      dut.clock.step()

      dut.io.parcelBits.poke(0.U)
      dut.io.parcelPageFault.poke(true.B)
      dut.io.instructionValid.expect(true.B)
      dut.io.instructionBytes.expect(4.U)
      dut.io.faultAddress.expect((rv64Pc + 2).U)
      dut.io.pageFault.expect(true.B)
    }
  }
}
