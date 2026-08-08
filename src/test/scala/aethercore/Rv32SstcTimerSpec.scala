package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.{Rv32SstcBit, Rv32SstcCsrAddress, Rv32SstcTimer}

class Rv32SstcTimerSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Rv32SstcTimer"

  private def initialize(dut: Rv32SstcTimer): Unit = {
    dut.io.time.poke(0.U)
    dut.io.writeLow.poke(false.B)
    dut.io.writeHigh.poke(false.B)
    dut.io.writeData.poke(0.U)
  }

  private def writeLow(dut: Rv32SstcTimer, value: BigInt): Unit = {
    dut.io.writeData.poke(value.U)
    dut.io.writeLow.poke(true.B)
    dut.clock.step()
    dut.io.writeLow.poke(false.B)
  }

  private def writeHigh(dut: Rv32SstcTimer, value: BigInt): Unit = {
    dut.io.writeData.poke(value.U)
    dut.io.writeHigh.poke(true.B)
    dut.clock.step()
    dut.io.writeHigh.poke(false.B)
  }

  it should "use the architectural RV32 Sstc CSR numbers" in {
    Rv32SstcCsrAddress.Mcounteren shouldBe 0x306
    Rv32SstcCsrAddress.Menvcfg shouldBe 0x30a
    Rv32SstcCsrAddress.Menvcfgh shouldBe 0x31a
    Rv32SstcCsrAddress.Stimecmp shouldBe 0x14d
    Rv32SstcCsrAddress.Stimecmph shouldBe 0x15d
    Rv32SstcCsrAddress.Time shouldBe 0xc01
    Rv32SstcCsrAddress.Timeh shouldBe 0xc81
    Rv32SstcBit.SupervisorTimerInterrupt shouldBe 5
    Rv32SstcBit.McounterenTime shouldBe 1
    Rv32SstcBit.MenvcfghStce shouldBe 31
  }

  it should "start disabled and assert pending exactly at the 64-bit threshold" in {
    simulate(new Rv32SstcTimer) { dut =>
      initialize(dut)
      dut.io.compare.expect("hffffffffffffffff".U)
      dut.io.pending.expect(false.B)

      writeLow(dut, BigInt("ffffffff", 16))
      writeHigh(dut, BigInt("00000001", 16))
      writeLow(dut, BigInt("00000020", 16))

      dut.io.readHigh.expect(1.U)
      dut.io.readLow.expect("h20".U)
      dut.io.compare.expect("h0000000100000020".U)

      dut.io.time.poke(BigInt("000000010000001f", 16).U)
      dut.io.pending.expect(false.B)
      dut.io.time.poke(BigInt("0000000100000020", 16).U)
      dut.io.pending.expect(true.B)
      dut.io.time.poke(BigInt("0000000100000021", 16).U)
      dut.io.pending.expect(true.B)
    }
  }

  it should "preserve the untouched half across independent RV32 writes" in {
    simulate(new Rv32SstcTimer) { dut =>
      initialize(dut)
      writeHigh(dut, BigInt("12345678", 16))
      dut.io.readHigh.expect("h12345678".U)
      dut.io.readLow.expect("hffffffff".U)

      writeLow(dut, BigInt("89abcdef", 16))
      dut.io.readHigh.expect("h12345678".U)
      dut.io.readLow.expect("h89abcdef".U)
      dut.io.compare.expect("h1234567889abcdef".U)
    }
  }
}
