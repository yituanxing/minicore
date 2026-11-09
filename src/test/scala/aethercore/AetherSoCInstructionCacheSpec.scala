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

  it should "keep indexing, alias eviction and FENCE.I correct at every cache A/B size" in {
    for (entries <- Seq(64, 256, 512)) {
      withClue(s"I-cache entries=$entries: ") {
        simulate(new AetherSoCInstructionCache(entries = entries)) { dut =>
          val base = BigInt("80001000", 16)
          val adjacentIndex = base + 8
          val alias = base + entries * 8

          dut.io.invalidateAll.poke(false.B)
          dut.io.frontendValid.poke(true.B)
          dut.io.frontendBytes.poke(4.U)
          dut.io.request.ready.poke(true.B)
          idleResponse(dut)

          def fill(addr: BigInt, data: BigInt): Unit = {
            dut.io.frontendAddr.poke(addr.U)
            dut.io.request.valid.expect(true.B)
            dut.io.request.bits.paddr.expect(addr.U)
            dut.clock.step()
            dut.io.response.valid.poke(true.B)
            dut.io.response.bits.rdata.poke(data.U)
            dut.io.frontendReady.expect(true.B)
            dut.clock.step()
            idleResponse(dut)
            dut.io.frontendReady.expect(true.B)
            dut.io.frontendInst.expect(data.U)
          }

          fill(base, BigInt("11223344", 16))
          fill(adjacentIndex, BigInt("55667788", 16))
          dut.io.frontendAddr.poke(base.U)
          dut.io.request.valid.expect(false.B)
          dut.io.frontendInst.expect("h11223344".U)

          // One cache-span displacement must replace exactly this direct-
          // mapped index, without accidentally sharing the old line tag.
          fill(alias, BigInt("deadbeef", 16))
          dut.io.frontendAddr.poke(base.U)
          dut.io.frontendReady.expect(false.B)
          dut.io.request.valid.expect(true.B)
          dut.io.frontendAddr.poke(adjacentIndex.U)
          dut.io.frontendReady.expect(true.B)
          dut.io.frontendInst.expect("h55667788".U)

          dut.io.invalidateAll.poke(true.B)
          dut.io.frontendReady.expect(false.B)
          dut.clock.step()
          dut.io.invalidateAll.poke(false.B)
          dut.io.request.valid.expect(true.B)
        }
      }
    }
  }
}
