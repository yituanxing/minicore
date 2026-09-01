package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.soc.AetherSoCInstructionCache

class AetherSoCInstructionCacheSpec
    extends AnyFlatSpec with Matchers with ChiselSim {

  behavior of "AetherSoCInstructionCache"

  private def idleResponse(dut: AetherSoCInstructionCache): Unit = {
    dut.io.response.valid.poke(false.B)
    dut.io.response.bits.txnId.poke(0.U)
    dut.io.response.bits.rdata.poke(0.U)
    dut.io.response.bits.fault.poke(false.B)
    dut.io.response.bits.last.poke(true.B)
  }

  it should "miss once, fill exact bytes, then satisfy a same-cycle hit" in {
    simulate(new AetherSoCInstructionCache()) { dut =>
      dut.io.invalidateAll.poke(false.B)
      dut.io.frontendValid.poke(true.B)
      dut.io.frontendAddr.poke("h80001000".U)
      dut.io.frontendBytes.poke(2.U)
      dut.io.request.ready.poke(true.B)
      idleResponse(dut)

      withClue("cold request: ") {
        dut.io.request.valid.expect(true.B)
        dut.io.request.bits.paddr.expect("h80001000".U)
        dut.io.frontendReady.expect(false.B)
      }
      dut.clock.step()

      dut.io.response.valid.poke(true.B)
      dut.io.response.bits.rdata.poke("h0000000000001234".U)
      withClue("terminal miss response: ") {
        dut.io.response.ready.expect(true.B)
        dut.io.frontendReady.expect(true.B)
        dut.io.frontendInst.expect("h00001234".U)
      }
      dut.clock.step()

      idleResponse(dut)
      withClue("filled same-cycle hit: ") {
        dut.io.frontendReady.expect(true.B)
        dut.io.frontendInst.expect("h00001234".U)
        dut.io.request.valid.expect(false.B)
      }

      // Only two bytes were fetched. A four-byte request to the same address
      // must miss rather than consuming uninitialized bytes from the line.
      dut.io.frontendBytes.poke(4.U)
      dut.io.frontendReady.expect(false.B)
      dut.io.request.valid.expect(true.B)
    }
  }


  it should "merge a second narrow miss with captured tag and byte-valid state" in {
    simulate(new AetherSoCInstructionCache()) { dut =>
      dut.io.invalidateAll.poke(false.B)
      dut.io.frontendValid.poke(true.B)
      dut.io.frontendAddr.poke("h80005000".U)
      dut.io.frontendBytes.poke(2.U)
      dut.io.request.ready.poke(true.B)
      idleResponse(dut)

      dut.io.request.valid.expect(true.B)
      dut.clock.step()
      dut.io.response.valid.poke(true.B)
      dut.io.response.bits.rdata.poke("h0000000000001234".U)
      dut.clock.step()
      idleResponse(dut)

      dut.io.frontendAddr.poke("h80005002".U)
      dut.io.frontendBytes.poke(2.U)
      dut.io.request.valid.expect(true.B)
      dut.clock.step()
      dut.io.response.valid.poke(true.B)
      dut.io.response.bits.rdata.poke("h000000000000abcd".U)
      dut.clock.step()
      idleResponse(dut)

      dut.io.frontendAddr.poke("h80005000".U)
      dut.io.frontendBytes.poke(4.U)
      dut.io.frontendReady.expect(true.B)
      dut.io.frontendInst.expect("habcd1234".U)
      dut.io.request.valid.expect(false.B)
    }
  }

  it should "invalidate hits at FENCE.I and refuse stale redirected responses" in {
    simulate(new AetherSoCInstructionCache()) { dut =>
      dut.io.invalidateAll.poke(false.B)
      dut.io.frontendValid.poke(true.B)
      dut.io.frontendAddr.poke("h80002000".U)
      dut.io.frontendBytes.poke(4.U)
      dut.io.request.ready.poke(true.B)
      idleResponse(dut)

      // Fill one 4B instruction.
      dut.io.request.valid.expect(true.B)
      dut.clock.step()
      dut.io.response.valid.poke(true.B)
      dut.io.response.bits.rdata.poke("h00000000deadbeef".U)
      dut.io.frontendReady.expect(true.B)
      dut.clock.step()
      idleResponse(dut)
      dut.io.frontendReady.expect(true.B)
      dut.io.frontendInst.expect("hdeadbeef".U)

      // Retiring FENCE.I must suppress the hit immediately and invalidate state.
      dut.io.invalidateAll.poke(true.B)
      dut.io.frontendReady.expect(false.B)
      dut.io.request.valid.expect(false.B)
      dut.clock.step()
      dut.io.invalidateAll.poke(false.B)
      dut.io.frontendReady.expect(false.B)
      dut.io.request.valid.expect(true.B)

      // Launch a new miss for A, then redirect the frontend to B before A
      // returns. A's response must be consumed but never presented as B.
      dut.io.frontendAddr.poke("h80003000".U)
      dut.io.frontendBytes.poke(2.U)
      dut.io.request.valid.expect(true.B)
      dut.clock.step()

      dut.io.frontendAddr.poke("h80004000".U)
      dut.io.response.valid.poke(true.B)
      dut.io.response.bits.rdata.poke("h000000000000cafe".U)
      withClue("stale redirected response: ") {
        dut.io.response.ready.expect(true.B)
        dut.io.frontendReady.expect(false.B)
      }
      dut.clock.step()

      idleResponse(dut)
      withClue("redirect target can launch after stale response drains: ") {
        dut.io.request.valid.expect(true.B)
        dut.io.request.bits.paddr.expect("h80004000".U)
      }
    }
  }
}
