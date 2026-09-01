package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.soc.peripheral.{AetherPlic, AetherPlicMap}

class AetherPlicSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherPlic"

  private def initialize(dut: AetherPlic): Unit = {
    dut.io.sources.poke(0.U)
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(0.U)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
    dut.io.complete.poke(false.B)
  }

  private def write(
      dut: AetherPlic,
      address: Int,
      value: BigInt,
      mask: Int = 0xf
  ): Unit = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(true.B)
    dut.io.address.poke(address.U)
    dut.io.wdata.poke(value.U)
    dut.io.wmask.poke(mask.U)
    dut.io.complete.poke(true.B)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.complete.poke(false.B)
  }

  private def read(dut: AetherPlic, address: Int): BigInt = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(address.U)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
    dut.io.complete.poke(true.B)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    val value = dut.io.rdata.peek().litValue
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.complete.poke(false.B)
    value
  }

  it should "apply claim, completion and register writes only on terminal acceptance" in {
    simulate(new AetherPlic(
      sourceCount = 4,
      enableBase = AetherPlicMap.Enable,
      thresholdOffset = AetherPlicMap.Threshold,
      claimCompleteOffset = AetherPlicMap.ClaimComplete
    )) { dut =>
      initialize(dut)

      // A presented write without the terminal response pulse is observational only.
      dut.io.request.poke(true.B)
      dut.io.write.poke(true.B)
      dut.io.address.poke(AetherPlicMap.priority(1).U)
      dut.io.wdata.poke(3.U)
      dut.io.wmask.poke(0xf.U)
      dut.io.complete.poke(false.B)
      dut.clock.step()
      dut.io.request.poke(false.B)
      dut.io.write.poke(false.B)
      read(dut, AetherPlicMap.priority(1)) shouldBe 0

      write(dut, AetherPlicMap.priority(1), 3)
      write(dut, AetherPlicMap.Enable, 0x2)
      dut.io.sources.poke(1.U)
      dut.clock.step()
      dut.io.interrupt.expect(true.B)

      // Claim data is visible combinationally, but no in-service state mutates
      // until the exact memory response is accepted.
      dut.io.request.poke(true.B)
      dut.io.write.poke(false.B)
      dut.io.address.poke(AetherPlicMap.ClaimComplete.U)
      dut.io.complete.poke(false.B)
      dut.io.rdata.expect(1.U)
      dut.clock.step()
      dut.io.inService.expect(0.U)

      dut.io.complete.poke(true.B)
      dut.io.rdata.expect(1.U)
      dut.clock.step()
      dut.io.request.poke(false.B)
      dut.io.complete.poke(false.B)
      dut.io.inService.expect(1.U)
      dut.io.interrupt.expect(false.B)

      // Completion without terminal acceptance must also be inert.
      dut.io.request.poke(true.B)
      dut.io.write.poke(true.B)
      dut.io.address.poke(AetherPlicMap.ClaimComplete.U)
      dut.io.wdata.poke(1.U)
      dut.io.wmask.poke(0xf.U)
      dut.io.complete.poke(false.B)
      dut.clock.step()
      dut.io.inService.expect(1.U)

      dut.io.complete.poke(true.B)
      dut.clock.step()
      dut.io.request.poke(false.B)
      dut.io.write.poke(false.B)
      dut.io.complete.poke(false.B)
      dut.io.inService.expect(0.U)
      dut.io.interrupt.expect(true.B)
    }
  }


  it should "specialize state to physically wired sources without renumbering IRQ IDs" in {
    val uartSourceMask = BigInt(1) << 9
    simulate(new AetherPlic(
      sourceCount = 16,
      addressBits = 22,
      implementedSourceMask = Some(uartSourceMask)
    )) { dut =>
      initialize(dut)

      // Unwired source 5 remains legal MMIO space but is WARL-zero.
      write(dut, AetherPlicMap.priority(5), 3)
      read(dut, AetherPlicMap.priority(5)) shouldBe 0

      // Wired UART source 10 retains its architectural priority and ID.
      write(dut, AetherPlicMap.priority(10), 3)
      read(dut, AetherPlicMap.priority(10)) shouldBe 3

      write(
        dut,
        AetherPlicMap.SupervisorEnable,
        (BigInt(1) << 5) | (BigInt(1) << 10)
      )
      read(dut, AetherPlicMap.SupervisorEnable) shouldBe (BigInt(1) << 10)

      // An unwired input bit cannot become pending/interrupting.
      dut.io.sources.poke((BigInt(1) << 4).U)
      dut.clock.step()
      dut.io.interrupt.expect(false.B)

      // The physical UART input still claims as source ID 10.
      dut.io.sources.poke((BigInt(1) << 9).U)
      dut.clock.step()
      dut.io.interrupt.expect(true.B)
      read(dut, AetherPlicMap.SupervisorClaimComplete) shouldBe 10
    }
  }

  it should "retain the qualified QEMU-virt supervisor context and absent machine aperture" in {
    simulate(new AetherPlic(sourceCount = 52)) { dut =>
      initialize(dut)

      // Machine-context cold-init aperture is legal but inert.
      write(dut, AetherPlicMap.Enable, BigInt("ffffffff", 16))
      write(dut, AetherPlicMap.Threshold, 7)
      read(dut, AetherPlicMap.Enable) shouldBe 0
      read(dut, AetherPlicMap.Threshold) shouldBe 0
      dut.io.enabled.expect(0.U)

      // Source 10 is the existing UART source in the qualified Linux board.
      write(dut, AetherPlicMap.priority(10), 3)
      write(dut, AetherPlicMap.SupervisorEnable, BigInt(1) << 10)
      dut.io.sources.poke((BigInt(1) << 9).U)
      dut.clock.step()
      dut.io.interrupt.expect(true.B)

      read(dut, AetherPlicMap.SupervisorClaimComplete) shouldBe 10
      dut.io.inService.expect((BigInt(1) << 9).U)
      dut.io.interrupt.expect(false.B)

      write(dut, AetherPlicMap.SupervisorClaimComplete, 10)
      dut.io.inService.expect(0.U)
      dut.io.interrupt.expect(true.B)
    }
  }
}
