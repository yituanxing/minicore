package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.{MachinePlicMmio, MachinePlicMmioMap}

class MachinePlicMmioSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachinePlicMmio"

  private def initialize(dut: MachinePlicMmio): Unit = {
    dut.io.sources.poke(0.U)
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(0.U)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
  }

  private def write(
      dut: MachinePlicMmio,
      address: Int,
      value: BigInt,
      mask: Int = 0xf
  ): Unit = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(true.B)
    dut.io.address.poke(address.U)
    dut.io.wdata.poke(value.U)
    dut.io.wmask.poke(mask.U)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
  }

  private def read(dut: MachinePlicMmio, address: Int): BigInt = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(address.U)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    val value = dut.io.rdata.peek().litValue
    dut.clock.step()
    dut.io.request.poke(false.B)
    value
  }

  private def expectReadFault(dut: MachinePlicMmio, address: Int): Unit = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(address.U)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(true.B)
    dut.io.rdata.expect(0.U)
    dut.clock.step()
    dut.io.request.poke(false.B)
  }

  it should "expose one-based priority, pending, enable, threshold and claim-complete semantics" in {
    simulate(new MachinePlicMmio(sourceCount = 4, priorityBits = 3)) { dut =>
      initialize(dut)

      write(dut, MachinePlicMmioMap.priority(1), 3)
      write(dut, MachinePlicMmioMap.priority(2), 3)
      write(dut, MachinePlicMmioMap.priority(3), 5)
      write(dut, MachinePlicMmioMap.Enable, 0x0e)
      write(dut, MachinePlicMmioMap.Threshold, 2)

      read(dut, MachinePlicMmioMap.priority(1)) shouldBe 3
      read(dut, MachinePlicMmioMap.priority(3)) shouldBe 5
      read(dut, MachinePlicMmioMap.Enable) shouldBe 0x0e
      read(dut, MachinePlicMmioMap.Threshold) shouldBe 2

      dut.io.sources.poke("b0111".U)
      dut.clock.step()
      read(dut, MachinePlicMmioMap.Pending) shouldBe 0x0e
      dut.io.interrupt.expect(true.B)

      // Source 3 wins on priority. Reading claim marks it in service.
      read(dut, MachinePlicMmioMap.ClaimComplete) shouldBe 3
      dut.io.inService.expect("b0100".U)
      read(dut, MachinePlicMmioMap.Pending) shouldBe 0x06

      // Sources 1 and 2 tie, so the lower source ID wins.
      read(dut, MachinePlicMmioMap.ClaimComplete) shouldBe 1
      dut.io.inService.expect("b0101".U)

      // Completing source 3 while its level remains asserted immediately makes
      // it pending and eligible again.
      write(dut, MachinePlicMmioMap.ClaimComplete, 3)
      dut.io.inService.expect("b0001".U)
      dut.io.pending.expect("b0110".U)
      dut.io.interrupt.expect(true.B)
      read(dut, MachinePlicMmioMap.ClaimComplete) shouldBe 3

      // Priority must be strictly greater than the context threshold.
      write(dut, MachinePlicMmioMap.Threshold, 5)
      write(dut, MachinePlicMmioMap.ClaimComplete, 3)
      dut.io.pending.expect("b0110".U)
      dut.io.interrupt.expect(false.B)
      read(dut, MachinePlicMmioMap.ClaimComplete) shouldBe 0
    }
  }

  it should "reserve register bit zero, honor byte masks and reject unknown addresses" in {
    simulate(new MachinePlicMmio(sourceCount = 4, priorityBits = 3)) { dut =>
      initialize(dut)

      // Architectural bits 2 and 4 enable compact internal sources 2 and 4.
      // Reserved bit zero is ignored and always reads back as zero.
      write(dut, MachinePlicMmioMap.Enable, 0x15, mask = 0x1)
      dut.io.enabled.expect("b1010".U)
      read(dut, MachinePlicMmioMap.Enable) shouldBe 0x14

      write(dut, MachinePlicMmioMap.priority(2), 7, mask = 0x1)
      read(dut, MachinePlicMmioMap.priority(2)) shouldBe 7

      // A write to byte lane one preserves the low priority byte.
      write(dut, MachinePlicMmioMap.priority(2), 0xff00, mask = 0x2)
      read(dut, MachinePlicMmioMap.priority(2)) shouldBe 7

      // Source zero is reserved, but conventional PLIC software clears it.
      // Reads return zero and writes are ignored without changing real sources.
      read(dut, MachinePlicMmioMap.PriorityBase) shouldBe 0
      write(dut, MachinePlicMmioMap.PriorityBase, 7)
      read(dut, MachinePlicMmioMap.PriorityBase) shouldBe 0
      read(dut, MachinePlicMmioMap.priority(2)) shouldBe 7

      expectReadFault(dut, MachinePlicMmioMap.priority(1) + 2)
      expectReadFault(dut, 0x003000)

      dut.io.enabled.expect("b1010".U)
      dut.io.inService.expect(0.U)
    }
  }

  it should "cover the N5 QEMU-virt supervisor context and both enable words" in {
    simulate(new MachinePlicMmio(
      sourceCount = 52,
      priorityBits = 3,
      enableBase = MachinePlicMmioMap.SupervisorEnable,
      thresholdOffset = MachinePlicMmioMap.SupervisorThreshold,
      claimCompleteOffset = MachinePlicMmioMap.SupervisorClaimComplete
    )) { dut =>
      initialize(dut)

      // NuttX initializes priority entries through source 52.
      write(dut, MachinePlicMmioMap.priority(1), 1)
      write(dut, MachinePlicMmioMap.priority(31), 2)
      write(dut, MachinePlicMmioMap.priority(32), 3)
      write(dut, MachinePlicMmioMap.priority(52), 4)
      read(dut, MachinePlicMmioMap.priority(52)) shouldBe 4

      // Word 0 exposes IDs 1..31 at architectural bits 1..31.
      write(dut, MachinePlicMmioMap.SupervisorEnable, BigInt("80000002", 16))
      // Word 1 exposes source 32 at bit 0 and source 52 at bit 20.
      write(dut, MachinePlicMmioMap.SupervisorEnable + 4, BigInt("00100001", 16))

      read(dut, MachinePlicMmioMap.SupervisorEnable) shouldBe BigInt("80000002", 16)
      read(dut, MachinePlicMmioMap.SupervisorEnable + 4) shouldBe BigInt("00100001", 16)

      val expectedEnabled =
        (BigInt(1) << 0) |
          (BigInt(1) << 30) |
          (BigInt(1) << 31) |
          (BigInt(1) << 51)
      dut.io.enabled.expect(expectedEnabled.U)

      dut.io.sources.poke(expectedEnabled.U)
      dut.clock.step()
      read(dut, MachinePlicMmioMap.Pending) shouldBe BigInt("80000002", 16)
      read(dut, MachinePlicMmioMap.Pending + 4) shouldBe BigInt("00100001", 16)

      write(dut, MachinePlicMmioMap.SupervisorThreshold, 2)
      read(dut, MachinePlicMmioMap.SupervisorThreshold) shouldBe 2
      read(dut, MachinePlicMmioMap.SupervisorClaimComplete) shouldBe 52

      // This instance intentionally exposes only the selected context window.
      expectReadFault(dut, MachinePlicMmioMap.Enable)
      expectReadFault(dut, MachinePlicMmioMap.Threshold)
    }
  }
}
