package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.sim.AetherSoCUnifiedHostMemoryAdapter
import aethercore.soc.AetherSoCInstructionReadAdapter

class AetherSoCInstructionFlowThroughSpec
    extends AnyFlatSpec with Matchers with ChiselSim {

  behavior of "AetherSoC instruction-memory flow-through"

  it should "complete a zero-wait AetherMem response in the request-accept cycle and retain held fallback" in {
    simulate(new AetherSoCInstructionReadAdapter(56, 64, 2)) { dut =>
      dut.io.legacyValid.poke(true.B)
      dut.io.legacyAddr.poke("h80001000".U)
      dut.io.legacyBytes.poke(4.U)
      dut.io.request.ready.poke(true.B)
      dut.io.response.valid.poke(true.B)
      dut.io.response.bits.txnId.poke(0.U)
      dut.io.response.bits.rdata.poke("h0000000012345678".U)
      dut.io.response.bits.fault.poke(false.B)
      dut.io.response.bits.last.poke(true.B)

      withClue("same-cycle zero-wait response: ") {
        dut.io.request.valid.expect(true.B)
        dut.io.response.ready.expect(true.B)
        dut.io.legacyReady.expect(true.B)
        dut.io.legacyInst.expect("h12345678".U)
        dut.clock.step()
        // request.fire and response.fire in the same cycle must not leave a
        // phantom outstanding lifetime behind.
        dut.io.request.valid.expect(true.B)
      }

      withClue("held fallback when downstream response is delayed: ") {
        dut.io.response.valid.poke(false.B)
        dut.io.legacyAddr.poke("h80002000".U)
        dut.io.request.valid.expect(true.B)
        dut.clock.step()

        // Request was accepted but no response existed, so the adapter must
        // suppress replay until the captured lifetime completes.
        dut.io.request.valid.expect(false.B)
        dut.io.response.ready.expect(true.B)
        dut.io.legacyReady.expect(false.B)

        dut.io.response.valid.poke(true.B)
        dut.io.response.bits.rdata.poke("h00000000deadbeef".U)
        dut.io.legacyReady.expect(true.B)
        dut.io.legacyInst.expect("hdeadbeef".U)
        dut.clock.step()

        dut.io.response.valid.poke(false.B)
        dut.io.request.valid.expect(true.B)
      }
    }
  }

  it should "flow a fresh instruction request through the unified host response arbiter and capture on backpressure" in {
    simulate(new AetherSoCUnifiedHostMemoryAdapter(56, 64, 2, 2)) { dut =>
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.txnId.poke("b1000".U) // source=2, local txn=0
      dut.io.request.bits.op.poke(AetherMemOp.Read)
      dut.io.request.bits.paddr.poke("h80003000".U)
      dut.io.request.bits.size.poke(MemSize.Word)
      dut.io.request.bits.wdata.poke(0.U)
      dut.io.request.bits.wmask.poke(0.U)
      dut.io.request.bits.atomicOp.poke(AtomicOp.None)
      dut.io.request.bits.attributes.cacheable.poke(true.B)
      dut.io.request.bits.attributes.idempotent.poke(true.B)
      dut.io.request.bits.attributes.sideEffecting.poke(false.B)
      dut.io.request.bits.attributes.ordered.poke(false.B)
      dut.io.request.bits.attributes.executable.poke(true.B)
      dut.io.request.bits.attributes.supportsAtomic.poke(false.B)
      dut.io.request.bits.attributes.supportsPartial.poke(true.B)

      dut.io.imemInst.poke("h00c585b3".U)
      dut.io.imemFault.poke(false.B)
      dut.io.ptwReady.poke(false.B)
      dut.io.ptwRdata.poke(0.U)
      dut.io.ptwFault.poke(false.B)
      dut.io.memReady.poke(false.B)
      dut.io.memRdata.poke(0.U)
      dut.io.memFault.poke(false.B)

      withClue("same-cycle host instruction response: ") {
        dut.io.response.ready.poke(true.B)
        dut.io.request.ready.expect(true.B)
        dut.io.imemValid.expect(true.B)
        dut.io.imemAddr.expect("h80003000".U)
        dut.io.imemBytes.expect(4.U)
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.txnId.expect("b1000".U)
        dut.io.response.bits.rdata.expect("h0000000000c585b3".U)
        dut.clock.step()
      }

      withClue("capture when response arbiter is backpressured: ") {
        dut.io.request.bits.txnId.poke("b1001".U)
        dut.io.request.bits.paddr.poke("h80004000".U)
        dut.io.imemInst.poke("h00100073".U)
        dut.io.response.ready.poke(false.B)
        dut.io.request.ready.expect(true.B)
        dut.io.response.valid.expect(true.B)
        dut.clock.step()

        dut.io.request.valid.poke(false.B)
        dut.io.imemValid.expect(true.B)
        dut.io.imemAddr.expect("h80004000".U)
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.txnId.expect("b1001".U)

        dut.io.response.ready.poke(true.B)
        dut.clock.step()
        dut.io.imemValid.expect(false.B)
      }
    }
  }
}
