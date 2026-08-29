package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.MachineExceptionCode
import aethercore.config.{CoreConfig, CoreProfiles, IsaConfig, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore

/** Focused implementation-capability checks for common RV32C/RV64C closure.
  *
  * This deliberately constructs an unnamed RV64IMC+Sv39 implementation profile:
  * named RV64 qualification profiles remain C-free until compiler-produced
  * software passes the permanent workload gate. The test nevertheless exercises
  * the real TinyPagedCore production frontend rather than only the shared parcel
  * controller in isolation.
  */
class V2A8CommonRvcProductionSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore v2 common RVC production frontend"

  private val Config = CoreConfig(
    name = "rv64imc-sv39-implementation",
    isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M', 'C'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr"),
      virtualMemoryModes = Set("Sv39")
    ),
    platform = CoreProfiles.rv64imasuSv39PmpSoftware.platform
  )
  private val Geometry = PageTableGeometry.Sv39
  private val Reset = Config.platform.resetVector
  private val NopParcel = BigInt("0001", 16) // C.NOP

  private def initialize(dut: TinyPagedCore): Unit = {
    dut.io.imem.inst.poke(NopParcel.U)
    dut.io.imem.fault.poke(false.B)

    dut.io.ptw.ready.poke(true.B)
    dut.io.ptw.rdata.poke(0.U)
    dut.io.ptw.fault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(false.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(false.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def driveParcel(
      dut: TinyPagedCore,
      parcels: Map[BigInt, BigInt],
      faultAt: Option[BigInt] = None
  ): Option[BigInt] = {
    val valid = dut.io.imem.valid.peek().litToBoolean
    val address = dut.io.imem.addr.peek().litValue
    dut.io.imem.inst.poke(parcels.getOrElse(address, NopParcel).U)
    dut.io.imem.fault.poke((valid && faultAt.contains(address)).B)
    if (valid) {
      dut.io.imem.bytes.expect(2.U)
      Some(address)
    } else None
  }

  it should "retire compressed and 32-bit instructions with true 2/4-byte lifetimes on RV64" in {
    simulate(new TinyPagedCore(Config, Geometry)) { dut =>
      initialize(dut)

      // 0x0085 = C.ADDI x1,1 -> canonical 0x00108093.
      // 0x00700113 = ADDI x2,x0,7, presented as two little-endian parcels.
      val parcels = Map(
        Reset -> BigInt("0085", 16),
        (Reset + 2) -> BigInt("0113", 16),
        (Reset + 4) -> BigInt("0070", 16)
      )
      val seenFetches = mutable.ArrayBuffer.empty[BigInt]
      var sawCompressed = false
      var sawWide = false
      var cycles = 0

      while (cycles < 160 && !sawWide) {
        driveParcel(dut, parcels).foreach(seenFetches += _)

        if (dut.io.commit.valid.peek().litToBoolean) {
          val commitPc = dut.io.commit.pc.peek().litValue
          if (commitPc == Reset) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.instBytes.expect(2.U)
            dut.io.commit.rawInst.expect(BigInt("00000085", 16).U)
            dut.io.commit.inst.expect(BigInt("00108093", 16).U)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(1.U)
            dut.io.commit.rdData.expect(1.U)
            sawCompressed = true
          }
          if (commitPc == Reset + 2) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.instBytes.expect(4.U)
            dut.io.commit.rawInst.expect(BigInt("00700113", 16).U)
            dut.io.commit.inst.expect(BigInt("00700113", 16).U)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(2.U)
            dut.io.commit.rdData.expect(7.U)
            sawWide = true
          }
        }

        dut.clock.step()
        cycles += 1
      }

      withClue("compressed instruction never retired through TinyPagedCore: ") {
        sawCompressed shouldBe true
      }
      withClue("two-parcel 32-bit instruction never retired through TinyPagedCore: ") {
        sawWide shouldBe true
      }
      seenFetches should contain (Reset)
      seenFetches should contain (Reset + 2)
      seenFetches should contain (Reset + 4)
    }
  }

  it should "attribute a second-parcel access fault to PC+2 without retiring stale first-half bits" in {
    simulate(new TinyPagedCore(Config, Geometry)) { dut =>
      initialize(dut)

      val parcels = Map(
        Reset -> BigInt("0113", 16), // low half of ADDI x2,x0,7; bits[1:0] == 11
        (Reset + 2) -> BigInt("0070", 16)
      )
      val seenFetches = mutable.ArrayBuffer.empty[BigInt]
      var sawFault = false
      var cycles = 0

      while (cycles < 160 && !sawFault) {
        driveParcel(dut, parcels, faultAt = Some(Reset + 2)).foreach(seenFetches += _)

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == Reset &&
            dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAccessFault.U)
          dut.io.commit.exceptionValue.expect((Reset + 2).U)
          dut.io.commit.instBytes.expect(4.U)
          dut.io.commit.rdWrite.expect(false.B)
          sawFault = true
        }

        dut.clock.step()
        cycles += 1
      }

      seenFetches should contain (Reset)
      seenFetches should contain (Reset + 2)
      withClue("PC+2 parcel fault was not retired precisely: ") {
        sawFault shouldBe true
      }
    }
  }
}
