package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.v2.{ExecutionClass, MemoryOperationKind}
import aethercore.sim.V2CausalPerformanceCounterBank

/** Freeze the P8 causal-attribution identities independently from Linux. */
trait V2P8TopDownChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def clear(dut: V2CausalPerformanceCounterBank): Unit = {
    dut.io.events.frontValid.poke(false.B)
    dut.io.events.backendReady.poke(true.B)
    dut.io.events.commit.poke(false.B)
    dut.io.events.robNonEmpty.poke(false.B)
    dut.io.events.headValid.poke(false.B)
    dut.io.events.headClass.poke(ExecutionClass.None)

    dut.io.events.lsuBusy.poke(false.B)
    dut.io.events.lifetimeValid.poke(false.B)
    dut.io.events.memoryKind.poke(MemoryOperationKind.None)
    dut.io.events.physicalAddressValid.poke(false.B)
    dut.io.events.writeLike.poke(false.B)
    dut.io.events.writePermitMatched.poke(false.B)
    dut.io.events.physicalRequestIssued.poke(false.B)
    dut.io.events.completionPending.poke(false.B)
    dut.io.events.memoryRequestValid.poke(false.B)
    dut.io.events.memoryRequestReady.poke(false.B)
    dut.io.events.memoryRequestFire.poke(false.B)
  }

  behavior of "AetherCore v2 P8 causal performance counters"

  it should "partition frontend/backend, critical CPI and LSU lifetime without overlap" in {
    simulate(new V2CausalPerformanceCounterBank) { dut =>
      clear(dut)

      // Cycle 1: frontend supplies work, backend accepts it, and one instruction retires.
      dut.io.events.frontValid.poke(true.B)
      dut.io.events.backendReady.poke(true.B)
      dut.io.events.commit.poke(true.B)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.headValid.poke(true.B)
      dut.io.events.headClass.poke(ExecutionClass.Integer)
      dut.clock.step()

      // Cycle 2: genuine frontend starvation while the backend could accept work.
      clear(dut)
      dut.clock.step()

      // Cycle 3: backend owns the blocked dispatch cycle; oldest memory op is resolving.
      clear(dut)
      dut.io.events.frontValid.poke(false.B)
      dut.io.events.backendReady.poke(false.B)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.headValid.poke(true.B)
      dut.io.events.headClass.poke(ExecutionClass.Memory)
      dut.io.events.lsuBusy.poke(true.B)
      dut.io.events.lifetimeValid.poke(true.B)
      dut.io.events.memoryKind.poke(MemoryOperationKind.Load)
      dut.clock.step()

      // Cycle 4: resolved store is blocked only by exact-head write permission.
      clear(dut)
      dut.io.events.frontValid.poke(true.B)
      dut.io.events.backendReady.poke(false.B)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.headValid.poke(true.B)
      dut.io.events.headClass.poke(ExecutionClass.Memory)
      dut.io.events.lsuBusy.poke(true.B)
      dut.io.events.lifetimeValid.poke(true.B)
      dut.io.events.memoryKind.poke(MemoryOperationKind.Store)
      dut.io.events.physicalAddressValid.poke(true.B)
      dut.io.events.writeLike.poke(true.B)
      dut.io.events.writePermitMatched.poke(false.B)
      dut.clock.step()

      // Cycle 5: load already crossed the physical request and waits for response.
      clear(dut)
      dut.io.events.frontValid.poke(true.B)
      dut.io.events.backendReady.poke(true.B)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.headValid.poke(true.B)
      dut.io.events.headClass.poke(ExecutionClass.Memory)
      dut.io.events.lsuBusy.poke(true.B)
      dut.io.events.lifetimeValid.poke(true.B)
      dut.io.events.memoryKind.poke(MemoryOperationKind.Load)
      dut.io.events.physicalAddressValid.poke(true.B)
      dut.io.events.physicalRequestIssued.poke(true.B)
      dut.clock.step()

      // Cycle 6: an atomic completion is held at the architectural completion seam.
      clear(dut)
      dut.io.events.frontValid.poke(false.B)
      dut.io.events.backendReady.poke(true.B)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.headValid.poke(true.B)
      dut.io.events.headClass.poke(ExecutionClass.Memory)
      dut.io.events.lsuBusy.poke(true.B)
      dut.io.events.lifetimeValid.poke(true.B)
      dut.io.events.memoryKind.poke(MemoryOperationKind.Atomic)
      dut.io.events.physicalAddressValid.poke(true.B)
      dut.io.events.writeLike.poke(true.B)
      dut.io.events.writePermitMatched.poke(true.B)
      dut.io.events.physicalRequestIssued.poke(true.B)
      dut.io.events.completionPending.poke(true.B)
      dut.clock.step()

      // Cycle 7: valid+ready physical request handshake is its own priority bucket.
      clear(dut)
      dut.io.events.frontValid.poke(true.B)
      dut.io.events.backendReady.poke(true.B)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.headValid.poke(true.B)
      dut.io.events.headClass.poke(ExecutionClass.Memory)
      dut.io.events.lsuBusy.poke(true.B)
      dut.io.events.lifetimeValid.poke(true.B)
      dut.io.events.memoryKind.poke(MemoryOperationKind.Store)
      dut.io.events.physicalAddressValid.poke(true.B)
      dut.io.events.writeLike.poke(true.B)
      dut.io.events.writePermitMatched.poke(true.B)
      dut.io.events.physicalRequestIssued.poke(true.B)
      dut.io.events.memoryRequestValid.poke(true.B)
      dut.io.events.memoryRequestReady.poke(true.B)
      dut.io.events.memoryRequestFire.poke(true.B)
      dut.clock.step()

      // Cycle 8: physical request is valid but transport cannot accept it.
      clear(dut)
      dut.io.events.frontValid.poke(true.B)
      dut.io.events.backendReady.poke(false.B)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.headValid.poke(true.B)
      dut.io.events.headClass.poke(ExecutionClass.Memory)
      dut.io.events.lsuBusy.poke(true.B)
      dut.io.events.lifetimeValid.poke(true.B)
      dut.io.events.memoryKind.poke(MemoryOperationKind.Load)
      dut.io.events.physicalAddressValid.poke(true.B)
      dut.io.events.memoryRequestValid.poke(true.B)
      dut.io.events.memoryRequestReady.poke(false.B)
      dut.clock.step()

      dut.io.counters.cycles.expect(8.U)

      // Top-down is exhaustive and backend owns cycles where both sides are blocked.
      dut.io.counters.flow.expect(3.U)
      dut.io.counters.frontendBound.expect(2.U)
      dut.io.counters.backendBound.expect(3.U)

      // Critical-CPI partition: one retire, one empty, six memory-head no-retire cycles.
      dut.io.counters.criticalRetire.expect(1.U)
      dut.io.counters.criticalRobEmpty.expect(1.U)
      dut.io.counters.criticalCompute.expect(0.U)
      dut.io.counters.criticalBranch.expect(0.U)
      dut.io.counters.criticalMemory.expect(6.U)
      dut.io.counters.criticalSystem.expect(0.U)
      dut.io.counters.criticalOther.expect(0.U)

      // Six busy cycles split exactly by memory kind.
      dut.io.counters.lsuBusy.expect(6.U)
      dut.io.counters.memoryKindLoad.expect(3.U)
      dut.io.counters.memoryKindStore.expect(2.U)
      dut.io.counters.memoryKindAtomic.expect(1.U)
      dut.io.counters.memoryKindOther.expect(0.U)

      // And independently split exactly by lifetime stage.
      dut.io.counters.memoryStageResolve.expect(1.U)
      dut.io.counters.memoryStagePermit.expect(1.U)
      dut.io.counters.memoryStageRequestBackpressure.expect(1.U)
      dut.io.counters.memoryStageRequestFire.expect(1.U)
      dut.io.counters.memoryStageResponse.expect(1.U)
      dut.io.counters.memoryStageCompletion.expect(1.U)
      dut.io.counters.memoryStageOther.expect(0.U)

      dut.io.counters.resolveLoad.expect(1.U)
      dut.io.counters.permitStore.expect(1.U)
      dut.io.counters.responseLoad.expect(1.U)
      dut.io.counters.completionAtomic.expect(1.U)
    }
  }
}
