package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MemSize}
import aethercore.config.CoreProfiles
import aethercore.memory.AetherMemOp
import aethercore.soc.{AetherSoCAddressMap, AetherSoCPlatformFabric}

class AetherSoCPlatformFabricSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherSoCPlatformFabric"

  private val platform = CoreProfiles.rv64imasuSv39PmpSoftware.platform
  private val map = AetherSoCAddressMap.qualifiedLinux(platform)

  private def initialize(dut: AetherSoCPlatformFabric): Unit = {
    dut.io.resolvedPhysicalAddress.poke(0.U)
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.txnId.poke(0.U)
    dut.io.request.bits.op.poke(AetherMemOp.Read)
    dut.io.request.bits.paddr.poke(0.U)
    dut.io.request.bits.size.poke(MemSize.DWord)
    dut.io.request.bits.wdata.poke(0.U)
    dut.io.request.bits.wmask.poke(0.U)
    dut.io.request.bits.atomicOp.poke(AtomicOp.None)
    dut.io.request.bits.attributes.cacheable.poke(false.B)
    dut.io.request.bits.attributes.idempotent.poke(false.B)
    dut.io.request.bits.attributes.sideEffecting.poke(false.B)
    dut.io.request.bits.attributes.ordered.poke(false.B)
    dut.io.request.bits.attributes.executable.poke(false.B)
    dut.io.request.bits.attributes.supportsAtomic.poke(false.B)
    dut.io.request.bits.attributes.supportsPartial.poke(true.B)
    dut.io.response.ready.poke(true.B)

    dut.io.memReady.poke(false.B)
    dut.io.memRdata.poke(0.U)
    dut.io.memFault.poke(false.B)
    dut.io.externalRequest.foreach(_.ready.poke(false.B))
    dut.io.externalResponse.foreach { response =>
      response.valid.poke(false.B)
      response.bits.txnId.poke(0.U)
      response.bits.rdata.poke(0.U)
      response.bits.fault.poke(false.B)
      response.bits.last.poke(true.B)
    }

    dut.io.rxValid.poke(false.B)
    dut.io.rxByte.poke(0.U)
    dut.io.uartTxReady.poke(true.B)
    dut.io.timebaseTick.poke(true.B)
  }

  private def issue(
      dut: AetherSoCPlatformFabric,
      address: BigInt,
      op: AetherMemOp.Type,
      data: BigInt = 0,
      mask: BigInt = 0xff,
      txnId: Int = 1
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.txnId.poke(txnId.U)
    dut.io.request.bits.op.poke(op)
    dut.io.request.bits.paddr.poke(address.U)
    dut.io.request.bits.size.poke(MemSize.DWord)
    dut.io.request.bits.wdata.poke(data.U)
    dut.io.request.bits.wmask.poke(mask.U)
    dut.io.request.bits.atomicOp.poke(AtomicOp.None)
    dut.io.request.bits.attributes.cacheable.poke((address >= map.ramBase && address < map.ramLimit).B)
    dut.io.request.bits.attributes.idempotent.poke((address >= map.ramBase && address < map.ramLimit).B)
    dut.io.request.bits.attributes.sideEffecting.poke((address < map.ramBase || address >= map.ramLimit).B)
    dut.io.request.bits.attributes.ordered.poke((address < map.ramBase || address >= map.ramLimit).B)
    dut.io.request.bits.attributes.executable.poke((address >= map.ramBase && address < map.ramLimit).B)
    dut.io.request.bits.attributes.supportsAtomic.poke((address >= map.ramBase && address < map.ramLimit).B)
    dut.io.request.bits.attributes.supportsPartial.poke(true.B)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  it should "classify RAM PMA separately from device space" in {
    simulate(new AetherSoCPlatformFabric(
      paddrBits = platform.paddrBits,
      dataBits = platform.busDataBits,
      txnIdBits = 2,
      addressMap = map
    )) { dut =>
      initialize(dut)

      dut.io.resolvedPhysicalAddress.poke(map.ramBase.U)
      dut.io.resolvedAttributes.cacheable.expect(true.B)
      dut.io.resolvedAttributes.idempotent.expect(true.B)
      dut.io.resolvedAttributes.sideEffecting.expect(false.B)
      dut.io.resolvedAttributes.executable.expect(true.B)
      dut.io.resolvedAttributes.supportsAtomic.expect(true.B)

      dut.io.resolvedPhysicalAddress.poke(map.uartBase.U)
      dut.io.resolvedAttributes.cacheable.expect(false.B)
      dut.io.resolvedAttributes.idempotent.expect(false.B)
      dut.io.resolvedAttributes.sideEffecting.expect(true.B)
      dut.io.resolvedAttributes.ordered.expect(true.B)
      dut.io.resolvedAttributes.supportsAtomic.expect(false.B)
    }
  }

  it should "route RAM, exit and UART through one fabric transaction contract" in {
    simulate(new AetherSoCPlatformFabric(
      paddrBits = platform.paddrBits,
      dataBits = platform.busDataBits,
      txnIdBits = 2,
      addressMap = map
    )) { dut =>
      initialize(dut)

      val ramAddress = map.ramBase + 0x1000
      issue(dut, ramAddress, AetherMemOp.Read, txnId = 2)
      dut.io.memValid.expect(true.B)
      dut.io.memWrite.expect(false.B)
      dut.io.memAddr.expect(ramAddress.U)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke("h1122334455667788".U)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(2.U)
      dut.io.response.bits.rdata.expect("h1122334455667788".U)
      dut.clock.step()
      dut.io.memReady.poke(false.B)
      dut.io.memValid.expect(false.B)

      issue(dut, map.exitAddress, AetherMemOp.Write, data = 7, txnId = 1)
      dut.io.response.valid.expect(true.B)
      dut.io.exitValid.expect(true.B)
      dut.io.exitCode.expect(7.U)
      dut.clock.step()
      dut.io.exitValid.expect(false.B)

      issue(dut, map.uartBase, AetherMemOp.Write, data = 0x41, txnId = 3)
      dut.io.response.valid.expect(true.B)
      dut.io.uartValid.expect(true.B)
      dut.io.uartByte.expect(0x41.U)
      dut.clock.step()
      dut.io.uartValid.expect(false.B)
    }
  }

  it should "hold a UART MMIO response until the physical TX sink is ready" in {
    simulate(new AetherSoCPlatformFabric(
      paddrBits = platform.paddrBits,
      dataBits = platform.busDataBits,
      txnIdBits = 2,
      addressMap = map
    )) { dut =>
      initialize(dut)
      dut.io.uartTxReady.poke(false.B)

      issue(dut, map.uartBase, AetherMemOp.Write, data = 0x5a, txnId = 1)
      dut.io.response.valid.expect(false.B)
      dut.io.uartValid.expect(false.B)
      dut.clock.step(2)
      dut.io.response.valid.expect(false.B)
      dut.io.uartValid.expect(false.B)

      dut.io.uartTxReady.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.uartValid.expect(true.B)
      dut.io.uartByte.expect(0x5a.U)
      dut.clock.step()
      dut.io.uartValid.expect(false.B)
    }
  }


  it should "allow semantic RAM reads to remain concurrently outstanding and return out of order" in {
    simulate(new AetherSoCPlatformFabric(
      paddrBits = platform.paddrBits,
      dataBits = platform.busDataBits,
      txnIdBits = 2,
      addressMap = map,
      externalSemanticMemory = true
    )) { dut =>
      initialize(dut)
      dut.io.externalRequest.get.ready.poke(true.B)

      val a0 = map.ramBase + 0x1000
      val a1 = map.ramBase + 0x2000

      issue(dut, a0, AetherMemOp.Read, txnId = 0)
      dut.io.externalRequest.get.valid.expect(true.B)
      dut.io.externalRequest.get.bits.txnId.expect(0.U)
      dut.io.externalRequest.get.bits.paddr.expect(a0.U)
      dut.clock.step()

      issue(dut, a1, AetherMemOp.Read, txnId = 1)
      dut.io.externalRequest.get.valid.expect(true.B)
      dut.io.externalRequest.get.bits.txnId.expect(1.U)
      dut.io.externalRequest.get.bits.paddr.expect(a1.U)
      dut.clock.step()

      // Return the younger read first. Transaction identity, not request order,
      // owns the response.
      dut.io.externalResponse.get.valid.poke(true.B)
      dut.io.externalResponse.get.bits.txnId.poke(1.U)
      dut.io.externalResponse.get.bits.rdata.poke("h2222222222222222".U)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(1.U)
      dut.io.response.bits.rdata.expect("h2222222222222222".U)
      dut.clock.step()

      dut.io.externalResponse.get.bits.txnId.poke(0.U)
      dut.io.externalResponse.get.bits.rdata.poke("h1111111111111111".U)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(0.U)
      dut.io.response.bits.rdata.expect("h1111111111111111".U)
      dut.clock.step()
      dut.io.externalResponse.get.valid.poke(false.B)
    }
  }

  it should "hold serialized RAM operations behind the concurrent read drain barrier" in {
    simulate(new AetherSoCPlatformFabric(
      paddrBits = platform.paddrBits,
      dataBits = platform.busDataBits,
      txnIdBits = 2,
      addressMap = map,
      externalSemanticMemory = true
    )) { dut =>
      initialize(dut)
      dut.io.externalRequest.get.ready.poke(true.B)

      val readAddress = map.ramBase + 0x3000
      val writeAddress = map.ramBase + 0x4000

      issue(dut, readAddress, AetherMemOp.Read, txnId = 0)
      dut.io.externalRequest.get.valid.expect(true.B)
      dut.clock.step()

      // The write may enter the local queue, but cannot cross the semantic
      // external seam until the older read lifetime has retired.
      issue(dut, writeAddress, AetherMemOp.Write, data = 0x55, txnId = 2)
      dut.io.externalRequest.get.valid.expect(false.B)

      dut.io.externalResponse.get.valid.poke(true.B)
      dut.io.externalResponse.get.bits.txnId.poke(0.U)
      dut.io.externalResponse.get.bits.rdata.poke("h1234".U)
      dut.io.response.valid.expect(true.B)
      dut.clock.step()
      dut.io.externalResponse.get.valid.poke(false.B)

      dut.io.externalRequest.get.valid.expect(true.B)
      dut.io.externalRequest.get.bits.op.expect(AetherMemOp.Write)
      dut.io.externalRequest.get.bits.txnId.expect(2.U)
      dut.clock.step()

      // A younger read can queue, but the serialized write response owns the
      // global barrier until completion.
      issue(dut, readAddress + 8, AetherMemOp.Read, txnId = 1)
      dut.io.externalRequest.get.valid.expect(false.B)

      dut.io.externalResponse.get.valid.poke(true.B)
      dut.io.externalResponse.get.bits.txnId.poke(2.U)
      dut.io.externalResponse.get.bits.rdata.poke(0.U)
      dut.io.response.valid.expect(true.B)
      dut.clock.step()
      dut.io.externalResponse.get.valid.poke(false.B)

      dut.io.externalRequest.get.valid.expect(true.B)
      dut.io.externalRequest.get.bits.txnId.expect(1.U)
    }
  }

}
