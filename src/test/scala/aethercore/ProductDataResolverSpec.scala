package aethercore

import aethercore.common.{MachineExceptionCode, MemSize, PrivilegeMode}
import aethercore.core.product._
import aethercore.core.semantic.MemoryOperationKind
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Cycle-accurate architectural translation/ownership checks. No Python oracle. */
class ProductDataResolverSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Small Product data resolver and shared Sv39 TLB/PTW"

  private val profile = ProductCoreProfiles.smallEngineering

  private def init(d: ProductDataResolver): Unit = {
    d.io.memoryIssue.valid.poke(false.B)
    d.io.memoryIssue.bits.poke(0.U.asTypeOf(d.io.memoryIssue.bits))
    d.io.robHead.valid.poke(false.B)
    d.io.robHead.bits.poke(0.U.asTypeOf(d.io.robHead.bits))
    d.io.kill.valid.poke(false.B)
    d.io.kill.bits.poke(0.U.asTypeOf(d.io.kill.bits))
    d.io.translationFence.poke(false.B)
    d.io.context.effectivePrivilege.poke(PrivilegeMode.Machine.U)
    d.io.context.satpEnabled.poke(false.B)
    d.io.context.rootPpn.poke(1.U)
    d.io.context.sum.poke(false.B)
    d.io.context.mxr.poke(false.B)
    d.io.pmpConfig.poke(0.U.asTypeOf(d.io.pmpConfig))
    d.io.pmpAddress.poke(0.U.asTypeOf(d.io.pmpAddress))
    d.io.pte.ready.poke(false.B)
    d.io.pte.rdata.poke(0.U)
    d.io.pte.fault.poke(false.B)
    d.io.pmaAttributes.cacheable.poke(true.B)
    d.io.pmaAttributes.idempotent.poke(true.B)
    d.io.pmaAttributes.sideEffecting.poke(false.B)
    d.io.pmaAttributes.ordered.poke(false.B)
    d.io.pmaAttributes.executable.poke(true.B)
    d.io.pmaAttributes.supportsAtomic.poke(true.B)
    d.io.pmaAttributes.supportsPartial.poke(true.B)
    d.io.resolved.ready.poke(false.B)
    d.clock.step()
  }

  private def issue(d: ProductDataResolver, index: Int, va: BigInt,
                    size: MemSize.Type = MemSize.Word,
                    kind: MemoryOperationKind.Type = MemoryOperationKind.Load,
                    generation: Int = 0): Unit = {
    d.io.memoryIssue.bits.poke(0.U.asTypeOf(d.io.memoryIssue.bits))
    d.io.memoryIssue.bits.token.index.poke(index.U)
    d.io.memoryIssue.bits.token.generation.poke(generation.U)
    d.io.memoryIssue.bits.memoryKind.poke(kind)
    d.io.memoryIssue.bits.memorySize.poke(size)
    d.io.memoryIssue.bits.lhs.poke(va.U)
    d.io.memoryIssue.bits.immediate.poke(0.U)
    d.io.memoryIssue.valid.poke(true.B)
    d.io.memoryIssue.ready.expect(true.B)
    d.clock.step()
    d.io.memoryIssue.valid.poke(false.B)
  }

  private def head(d: ProductDataResolver, index: Int, generation: Int = 0): Unit = {
    d.io.robHead.valid.poke(true.B)
    d.io.robHead.bits.index.poke(index.U)
    d.io.robHead.bits.generation.poke(generation.U)
  }

  private def awaitResult(d: ProductDataResolver, cycles: Int = 30): Unit = {
    for (_ <- 0 until cycles) {
      if (!d.io.resolved.valid.peek().litToBoolean) d.clock.step()
    }
    d.io.resolved.valid.expect(true.B)
  }

  private def consume(d: ProductDataResolver): Unit = {
    d.io.resolved.ready.poke(true.B)
    d.clock.step()
    d.io.resolved.ready.poke(false.B)
  }

  private def allowAllPmp(d: ProductDataResolver): Unit = {
    // NAPOT with all encoded address bits set covers implemented PA domain.
    d.io.pmpConfig(0).poke("h1f".U)
    d.io.pmpAddress(0).poke(((BigInt(1) << d.io.pmpAddress(0).getWidth) - 1).U)
  }

  private def supplyPte(d: ProductDataResolver, pte: BigInt): Unit = {
    for (_ <- 0 until 12) {
      if (!d.io.pte.valid.peek().litToBoolean) d.clock.step()
    }
    d.io.pte.valid.expect(true.B)
    d.io.pte.rdata.poke(pte.U)
    d.io.pte.ready.poke(true.B)
    d.clock.step()
    d.io.pte.ready.poke(false.B)
  }

  it should "translate bare addresses without PA aliasing and retain VA through output backpressure" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      issue(d, 0, BigInt("80000100", 16))
      awaitResult(d)
      d.io.resolved.bits.token.index.expect(0.U)
      d.io.resolved.bits.virtualAddress.expect("h80000100".U)
      d.io.resolved.bits.paddr.expect("h80000100".U)
      d.io.resolved.bits.fault.valid.expect(false.B)
      d.io.pending.expect(1.U)
      d.clock.step(3)
      d.io.resolved.valid.expect(true.B)
      consume(d)
      d.io.pending.expect(0.U)
    }
  }

  it should "report misalignment before PTW and use virtual, never physical, trap values" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      d.io.context.satpEnabled.poke(true.B)
      d.io.context.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      issue(d, 1, BigInt("80000103", 16))
      awaitResult(d)
      d.io.pte.valid.expect(false.B)
      d.io.resolved.bits.fault.valid.expect(true.B)
      d.io.resolved.bits.fault.cause.expect(MachineExceptionCode.LoadAddressMisaligned.U)
      d.io.resolved.bits.fault.value.expect("h80000103".U)
      consume(d)
    }
  }

  it should "apply data PMP after bare translation with the original virtual fault address" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      d.io.context.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      issue(d, 2, BigInt("80002000", 16))
      awaitResult(d)
      d.io.resolved.bits.fault.valid.expect(true.B)
      d.io.resolved.bits.fault.cause.expect(MachineExceptionCode.LoadAccessFault.U)
      d.io.resolved.bits.fault.value.expect("h80002000".U)
      consume(d)
    }
  }

  it should "not allocate a non-head PTW miss, then report head PTE PMP denial" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      d.io.context.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      d.io.context.satpEnabled.poke(true.B)
      head(d, 0)
      issue(d, 1, BigInt("80003000", 16))
      for (_ <- 0 until 12) {
        d.io.pte.valid.expect(false.B)
        d.io.walking.expect(false.B)
        d.io.resolved.valid.expect(false.B)
        d.clock.step()
      }
      head(d, 1)
      awaitResult(d)
      d.io.pte.valid.expect(false.B) // Supervisor PTE PMP denies before bus access
      d.io.resolved.bits.fault.cause.expect(MachineExceptionCode.LoadAccessFault.U)
      d.io.resolved.bits.fault.value.expect("h80003000".U)
      consume(d)
    }
  }

  it should "cancel an active head walk without poisoning a reused ROB index" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      allowAllPmp(d)
      d.io.context.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      d.io.context.satpEnabled.poke(true.B)
      head(d, 0)
      issue(d, 0, BigInt("80004000", 16))
      for (_ <- 0 until 8) d.clock.step()
      d.io.walking.expect(true.B)
      d.io.pte.valid.expect(true.B)
      d.io.kill.valid.poke(true.B)
      d.io.kill.bits.index.poke(0.U)
      d.io.kill.bits.generation.poke(0.U)
      d.clock.step()
      d.io.kill.valid.poke(false.B)
      d.io.walking.expect(false.B)
      d.io.pte.valid.expect(false.B)
      d.io.resolved.valid.expect(false.B)
      d.io.context.satpEnabled.poke(false.B)
      head(d, 0, 1)
      issue(d, 0, BigInt("80005000", 16), generation = 1)
      awaitResult(d)
      d.io.resolved.bits.token.generation.expect(1.U)
      d.io.resolved.bits.paddr.expect("h80005000".U)
      consume(d)
    }
  }

  it should "retry an unconsumed translated result after SFENCE, preserving its ROB token" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      issue(d, 3, BigInt("80006000", 16))
      awaitResult(d)
      d.io.pending.expect(1.U)
      d.io.translationFence.poke(true.B)
      d.io.resolved.valid.expect(false.B)
      d.clock.step()
      d.io.translationFence.poke(false.B)
      awaitResult(d)
      d.io.resolved.bits.token.index.expect(3.U)
      d.io.resolved.bits.virtualAddress.expect("h80006000".U)
      consume(d)
      d.io.pending.expect(0.U)
    }
  }

  it should "snapshot PMP CSR geometry at issue rather than reading a later CSR state" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      allowAllPmp(d)
      d.io.context.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      issue(d, 0, BigInt("80007000", 16))
      d.io.pmpConfig.poke(0.U.asTypeOf(d.io.pmpConfig))
      d.io.pmpAddress.poke(0.U.asTypeOf(d.io.pmpAddress))
      awaitResult(d)
      d.io.resolved.bits.fault.valid.expect(false.B)
      consume(d)
    }
  }

  it should "walk an exact-head Sv39 miss once, then reuse its TLB entry for a different ROB lifetime" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      allowAllPmp(d)
      d.io.context.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      d.io.context.satpEnabled.poke(true.B)
      head(d, 0)
      issue(d, 0, BigInt("80001000", 16))
      // Sv39 root -> middle -> leaf. There is one shared walker, no second
      // algorithm in ProductDataResolver. The leaf maps to PA 0x80000000.
      supplyPte(d, (BigInt(2) << 10) | 1)
      supplyPte(d, (BigInt(3) << 10) | 1)
      supplyPte(d, (BigInt("80000", 16) << 10) | BigInt("c7", 16))
      awaitResult(d)
      d.io.resolved.bits.fault.valid.expect(false.B)
      d.io.resolved.bits.paddr.expect("h80000000".U)
      d.io.resolved.bits.virtualAddress.expect("h80001000".U)
      consume(d)

      head(d, 1)
      issue(d, 1, BigInt("80001000", 16))
      // TLB hit answers within the TranslationUnit request cycle; no PTE
      // traffic is permitted for this independently tagged new lifetime.
      for (_ <- 0 until 10) {
        d.io.pte.valid.expect(false.B)
        if (!d.io.resolved.valid.peek().litToBoolean) d.clock.step()
      }
      d.io.resolved.valid.expect(true.B)
      d.io.resolved.bits.token.index.expect(1.U)
      d.io.resolved.bits.paddr.expect("h80000000".U)
      consume(d)
    }
  }

  it should "leave capacity for an older head when three younger TLB misses cannot walk" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      allowAllPmp(d)
      d.io.context.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      d.io.context.satpEnabled.poke(true.B)
      head(d, 3) // ROB3 is older; younger 0/1/2 became operand-ready first
      for (i <- 0 until 3) {
        issue(d, i, BigInt("80010000", 16) + BigInt(i * 4096))
      }
      for (_ <- 0 until 8) {
        d.io.walking.expect(false.B)
        d.io.pte.valid.expect(false.B)
        d.clock.step()
      }
      d.io.pending.expect(3.U)
      d.io.resourceAvailable.expect(true.B)
      issue(d, 3, BigInt("80020000", 16))
      d.io.pending.expect(4.U)
      d.io.resourceAvailable.expect(false.B)
      for (_ <- 0 until 12) {
        if (!d.io.pte.valid.peek().litToBoolean) d.clock.step()
      }
      d.io.walking.expect(true.B)
      d.io.pte.valid.expect(true.B) // exact-head walk starts despite 3 young misses
    }
  }

  it should "rotate all six non-power-of-two physical slots without losing ROB identities" in {
    val geometry = profile.geometry.copy(loadQueueEntries = 3, storeQueueEntries = 3)
    simulate(new ProductDataResolver(profile.copy(geometry = geometry))) { d =>
      init(d)
      for (i <- 0 until 6) issue(d, i, BigInt("80010000", 16) + i * 8)
      d.io.pending.expect(6.U)
      d.io.resourceAvailable.expect(false.B)
      val returned = scala.collection.mutable.Set.empty[BigInt]
      for (_ <- 0 until 6) {
        awaitResult(d, cycles = 50)
        d.io.resolved.bits.fault.valid.expect(false.B)
        val token = d.io.resolved.bits.token.index.peek().litValue
        returned should not contain token
        returned += token
        d.io.resolved.bits.virtualAddress.expect(
          (BigInt("80010000", 16) + token * 8).U)
        consume(d)
      }
      returned shouldBe (0 until 6).map(BigInt(_)).toSet
      d.io.pending.expect(0.U)
    }
  }

  it should "cancel a leased PMP classification before it can generate a stale result" in {
    simulate(new ProductDataResolver(profile)) { d =>
      init(d)
      issue(d, 2, BigInt("80030000", 16), generation = 7)
      for (_ <- 0 until 30 if !d.io.pmaValid.peek().litToBoolean) d.clock.step()
      d.io.pmaValid.expect(true.B)
      d.io.kill.valid.poke(true.B)
      d.io.kill.bits.index.poke(2.U)
      d.io.kill.bits.generation.poke(7.U)
      d.clock.step()
      d.io.kill.valid.poke(false.B)
      for (_ <- 0 until 6) {
        d.io.resolved.valid.expect(false.B)
        d.clock.step()
      }
      d.io.pending.expect(0.U)
    }
  }
}
