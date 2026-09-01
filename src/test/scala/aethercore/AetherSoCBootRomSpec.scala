package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.soc.{AetherSoCAddressMap, AetherSoCBootRom}

class AetherSoCBootRomSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherSoCBootRom"

  private val base = AetherSoCAddressMap.QualifiedBootRomBase
  private val bytes = AetherSoCAddressMap.QualifiedBootRomBytes

  private def initialize(dut: AetherSoCBootRom): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.txnId.poke(0.U)
    dut.io.request.bits.op.poke(AetherMemOp.Read)
    dut.io.request.bits.paddr.poke(0.U)
    dut.io.request.bits.size.poke(MemSize.Word)
    dut.io.request.bits.wdata.poke(0.U)
    dut.io.request.bits.wmask.poke(0.U)
    dut.io.request.bits.atomicOp.poke(AtomicOp.None)
    dut.io.request.bits.attributes.cacheable.poke(false.B)
    dut.io.request.bits.attributes.idempotent.poke(true.B)
    dut.io.request.bits.attributes.sideEffecting.poke(false.B)
    dut.io.request.bits.attributes.ordered.poke(false.B)
    dut.io.request.bits.attributes.executable.poke(true.B)
    dut.io.request.bits.attributes.supportsAtomic.poke(false.B)
    dut.io.request.bits.attributes.supportsPartial.poke(false.B)
    dut.io.response.ready.poke(true.B)
  }

  private def readWord(dut: AetherSoCBootRom, address: BigInt, txnId: Int): BigInt = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.txnId.poke(txnId.U)
    dut.io.request.bits.op.poke(AetherMemOp.Read)
    dut.io.request.bits.paddr.poke(address.U)
    dut.io.request.bits.size.poke(MemSize.Word)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)

    dut.io.response.valid.expect(true.B)
    dut.io.response.bits.txnId.expect(txnId.U)
    dut.io.response.bits.fault.expect(false.B)
    val value = dut.io.response.bits.rdata.peek().litValue & BigInt("ffffffff", 16)
    dut.clock.step()
    value
  }

  it should "contain the reset trampoline into external OpenSBI RAM" in {
    simulate(new AetherSoCBootRom(
      addrBits = 56,
      dataBits = 64,
      txnIdBits = 4,
      baseAddress = base,
      apertureBytes = bytes
    )) { dut =>
      initialize(dut)

      readWord(dut, base + 0, 1) shouldBe BigInt("00100293", 16)
      readWord(dut, base + 4, 2) shouldBe BigInt("01f29293", 16)
      readWord(dut, base + 8, 3) shouldBe BigInt("00028067", 16)
    }
  }


  it should "preserve ROM contents and crossing faults in predecoded local-offset mode" in {
    simulate(new AetherSoCBootRom(
      addrBits = 32,
      dataBits = 64,
      txnIdBits = 4,
      baseAddress = base,
      apertureBytes = bytes,
      requestAlreadyDecoded = true
    )) { dut =>
      initialize(dut)

      readWord(dut, base + 0, 1) shouldBe BigInt("00100293", 16)
      readWord(dut, base + 8, 2) shouldBe BigInt("00028067", 16)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.txnId.poke(3.U)
      dut.io.request.bits.op.poke(AetherMemOp.Read)
      dut.io.request.bits.paddr.poke((base + bytes - 4).U)
      dut.io.request.bits.size.poke(MemSize.DWord)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.txnId.expect(3.U)
      dut.io.response.bits.fault.expect(true.B)
    }
  }

  it should "be read-only and reject reads crossing the ROM aperture" in {
    simulate(new AetherSoCBootRom(
      addrBits = 56,
      dataBits = 64,
      txnIdBits = 4,
      baseAddress = base,
      apertureBytes = bytes
    )) { dut =>
      initialize(dut)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.txnId.poke(1.U)
      dut.io.request.bits.op.poke(AetherMemOp.Write)
      dut.io.request.bits.paddr.poke(base.U)
      dut.io.request.bits.size.poke(MemSize.Word)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.fault.expect(true.B)
      dut.clock.step()

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.txnId.poke(2.U)
      dut.io.request.bits.op.poke(AetherMemOp.Read)
      dut.io.request.bits.paddr.poke((base + bytes - 4).U)
      dut.io.request.bits.size.poke(MemSize.DWord)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.fault.expect(true.B)
    }
  }
}
