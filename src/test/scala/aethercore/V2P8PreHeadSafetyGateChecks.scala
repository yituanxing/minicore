package aethercore

import aethercore.core.v2.TinyPreHeadSafetyGate
import aethercore.memory.AetherMemOp
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Focused combinational proof for the pre-head externalization policy. */
trait V2P8PreHeadSafetyGateChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def initialize(dut: TinyPreHeadSafetyGate): Unit = {
    dut.io.speculative.poke(false.B)
    dut.io.memoryValid.poke(false.B)
    dut.io.memoryOp.poke(AetherMemOp.Read)
    dut.io.attributes.cacheable.poke(false.B)
    dut.io.attributes.idempotent.poke(false.B)
    dut.io.attributes.sideEffecting.poke(true.B)
    dut.io.attributes.ordered.poke(true.B)
    dut.io.attributes.executable.poke(false.B)
    dut.io.attributes.supportsAtomic.poke(false.B)
    dut.io.attributes.supportsPartial.poke(false.B)
  }

  behavior of "AetherCore v2 pre-head externalization safety gate"

  it should "pass exact-head PTW and memory traffic independent of PMA replay safety" in {
    simulate(new TinyPreHeadSafetyGate) { dut =>
      initialize(dut)
      dut.io.memoryValid.poke(true.B)
      dut.io.memoryOp.poke(AetherMemOp.Write)
      dut.io.ptePermit.expect(true.B)
      dut.io.memoryPermit.expect(true.B)
    }
  }

  it should "suppress all PTW traffic while speculative" in {
    simulate(new TinyPreHeadSafetyGate) { dut =>
      initialize(dut)
      dut.io.speculative.poke(true.B)
      dut.io.ptePermit.expect(false.B)
    }
  }

  it should "permit only replay-safe physical Reads before head" in {
    simulate(new TinyPreHeadSafetyGate) { dut =>
      initialize(dut)
      dut.io.speculative.poke(true.B)
      dut.io.memoryValid.poke(true.B)
      dut.io.memoryOp.poke(AetherMemOp.Read)
      dut.io.attributes.idempotent.poke(true.B)
      dut.io.attributes.sideEffecting.poke(false.B)
      dut.io.attributes.ordered.poke(false.B)
      dut.io.memoryPermit.expect(true.B)

      dut.io.attributes.idempotent.poke(false.B)
      dut.io.memoryPermit.expect(false.B)
      dut.io.attributes.idempotent.poke(true.B)
      dut.io.attributes.sideEffecting.poke(true.B)
      dut.io.memoryPermit.expect(false.B)
      dut.io.attributes.sideEffecting.poke(false.B)
      dut.io.attributes.ordered.poke(true.B)
      dut.io.memoryPermit.expect(false.B)

      dut.io.attributes.ordered.poke(false.B)
      dut.io.memoryOp.poke(AetherMemOp.Write)
      dut.io.memoryPermit.expect(false.B)
      dut.io.memoryOp.poke(AetherMemOp.Atomic)
      dut.io.memoryPermit.expect(false.B)
    }
  }

  it should "not grant safe attributes when no speculative request is live" in {
    simulate(new TinyPreHeadSafetyGate) { dut =>
      initialize(dut)
      dut.io.speculative.poke(true.B)
      dut.io.attributes.idempotent.poke(true.B)
      dut.io.attributes.sideEffecting.poke(false.B)
      dut.io.attributes.ordered.poke(false.B)
      dut.io.memoryValid.poke(false.B)
      dut.io.memoryPermit.expect(false.B)
    }
  }
}
