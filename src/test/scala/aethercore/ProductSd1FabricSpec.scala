package aethercore

import aethercore.common.{AtomicOp, MemSize}
import aethercore.config.CoreProfiles
import aethercore.memory.AetherMemOp
import aethercore.soc.{AetherSoCAddressMap, AetherSoCPlatformFabric}
import aethercore.soc.peripheral.AetherPlicMap
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The Product persistent-SD board must carry actual byte-wide Linux SPI and
  * GPIO MMIO writes into physical pins and PLIC source 11. This deliberately
  * exercises the production semantic-memory fabric, not just standalone
  * peripheral registers or the host-only SDHC card model.
  */
class ProductSd1FabricSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product SD1 MMIO and interrupt wiring"

  private val platform = CoreProfiles.rv64imasuSv39PmpSoftware.platform
  private val map = AetherSoCAddressMap.qualifiedLinux(platform)

  private def initialize(dut: AetherSoCPlatformFabric): Unit = {
    dut.io.resolvedPhysicalAddress.poke(0.U)
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.txnId.poke(0.U)
    dut.io.request.bits.op.poke(AetherMemOp.Read)
    dut.io.request.bits.paddr.poke(0.U)
    dut.io.request.bits.size.poke(MemSize.Byte)
    dut.io.request.bits.wdata.poke(0.U)
    dut.io.request.bits.wmask.poke(0.U)
    dut.io.request.bits.atomicOp.poke(AtomicOp.None)
    dut.io.request.bits.attributes.cacheable.poke(false.B)
    dut.io.request.bits.attributes.idempotent.poke(false.B)
    dut.io.request.bits.attributes.sideEffecting.poke(true.B)
    dut.io.request.bits.attributes.ordered.poke(true.B)
    dut.io.request.bits.attributes.executable.poke(false.B)
    dut.io.request.bits.attributes.supportsAtomic.poke(false.B)
    dut.io.request.bits.attributes.supportsPartial.poke(true.B)
    dut.io.response.ready.poke(true.B)
    dut.io.externalRequest.get.ready.poke(false.B)
    dut.io.externalResponse.get.valid.poke(false.B)
    dut.io.externalResponse.get.bits.txnId.poke(0.U)
    dut.io.externalResponse.get.bits.rdata.poke(0.U)
    dut.io.externalResponse.get.bits.fault.poke(false.B)
    dut.io.externalResponse.get.bits.last.poke(true.B)
    dut.io.memReady.poke(false.B)
    dut.io.memRdata.poke(0.U)
    dut.io.memFault.poke(false.B)
    dut.io.rxValid.poke(false.B)
    dut.io.rxByte.poke(0.U)
    dut.io.uartTxReady.poke(true.B)
    dut.io.timebaseTick.poke(true.B)
    dut.io.spiMiso.get.poke(true.B)
    dut.clock.step(3)
  }

  private def mmio(
      dut: AetherSoCPlatformFabric,
      address: BigInt,
      op: AetherMemOp.Type,
      size: MemSize.Type,
      data: BigInt = 0,
      mask: Int = 1
  ): BigInt = {
    val txnId = 1
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.txnId.poke(txnId.U)
    dut.io.request.bits.op.poke(op)
    dut.io.request.bits.paddr.poke(address.U)
    dut.io.request.bits.size.poke(size)
    dut.io.request.bits.wdata.poke(data.U)
    dut.io.request.bits.wmask.poke(mask.U)
    dut.io.resolvedPhysicalAddress.poke(address.U)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)

    var completed = false
    var result = BigInt(0)
    for (_ <- 0 until 12 if !completed) {
      // Device responses must stay local. A bogus RAM/DDR request means a
      // broken physical aperture or a byte-width decode error.
      dut.io.externalRequest.get.valid.expect(false.B)
      if (dut.io.response.valid.peek().litToBoolean) {
        dut.io.response.bits.txnId.expect(txnId.U)
        dut.io.response.bits.fault.expect(false.B)
        result = dut.io.response.bits.rdata.peek().litValue
        completed = true
      }
      dut.clock.step()
    }
    completed shouldBe true
    result
  }

  it should "route Linux byte SPI and GPIO MMIO to pins and SPI IRQ through PLIC11" in {
    simulate(new AetherSoCPlatformFabric(
      paddrBits = platform.paddrBits,
      dataBits = 64,
      txnIdBits = 4,
      addressMap = map,
      plicSourceCount = 52,
      externalSemanticMemory = true,
      enableSpiSd = true,
      spiPlicSourceId = 11
    )) { dut =>
      initialize(dut)

      // GPIO CS is high until Linux programs DATA then output-direction.
      dut.io.spiCsN.get.expect(true.B)
      mmio(dut, map.spiCsGpioBase, AetherMemOp.Write, MemSize.Word, 0, 0xf)
      dut.io.spiCsN.get.expect(true.B)
      mmio(dut, map.spiCsGpioBase + 4, AetherMemOp.Write, MemSize.Word, 1, 0xf)
      dut.io.spiCsN.get.expect(false.B)
      mmio(dut, map.spiCsGpioBase, AetherMemOp.Read, MemSize.Word, 0, 0xf) shouldBe 0
      mmio(dut, map.spiCsGpioBase, AetherMemOp.Write, MemSize.Word, 1, 0xf)
      dut.io.spiCsN.get.expect(true.B)

      // PLIC 52-source supervisor context and SPI source 11 as in Product DTS.
      mmio(dut, map.plicBase + AetherPlicMap.priority(11),
        AetherMemOp.Write, MemSize.Word, 3, 0xf)
      mmio(dut, map.plicBase + AetherPlicMap.SupervisorEnable,
        AetherMemOp.Write, MemSize.Word, BigInt(1) << 11, 0xf)
      mmio(dut, map.plicBase + AetherPlicMap.SupervisorThreshold,
        AetherMemOp.Write, MemSize.Word, 0, 0xf)
      dut.io.supervisorExternalInterrupt.expect(false.B)

      // spi-oc-tiny uses byte-wide register operations, not 64-bit stores.
      mmio(dut, map.spiBase + 8, AetherMemOp.Read, MemSize.Byte) shouldBe 3
      mmio(dut, map.spiBase + 8, AetherMemOp.Write, MemSize.Byte, 2)
      dut.io.supervisorExternalInterrupt.expect(true.B)
      mmio(dut, map.plicBase + AetherPlicMap.SupervisorClaimComplete,
        AetherMemOp.Read, MemSize.Word, 0, 0xf) shouldBe 11
      dut.io.supervisorExternalInterrupt.expect(false.B)

      // Drop the actual SPI source before PLIC completion. It must not rearm.
      mmio(dut, map.spiBase + 8, AetherMemOp.Write, MemSize.Byte, 0)
      mmio(dut, map.plicBase + AetherPlicMap.SupervisorClaimComplete,
        AetherMemOp.Write, MemSize.Word, 11, 0xf)
      dut.io.supervisorExternalInterrupt.expect(false.B)
    }
  }
}
