package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.file.{Files, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.{CoreConfig, CoreProfiles, IsaConfig, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore

/**
  * Software qualification for the common-RVC implementation boundary.
  *
  * The executable is built by the owning CI workflow with the pinned RV64 GCC
  * toolchain. Ordinary repository-wide Scala test runs do not need that external
  * artifact, so this suite registers its executable test only when the workload
  * path is supplied (or the owning gate explicitly requires it).
  */
class V2A8Rv64CCompilerWorkloadSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore v2 compiler-produced RV64C workload"

  private val Required = sys.env.get("AETHERCORE_V2_RV64C_WORKLOAD_REQUIRED").contains("1")
  private val WorkloadPath = sys.env.get("AETHERCORE_V2_RV64C_WORKLOAD_BIN")

  private val Config = CoreConfig(
    name = "rv64imc-sv39-compiler-workload",
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
  private val ExpectedA0 = BigInt(94)

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

  private def parcelAt(image: Array[Byte], address: BigInt): BigInt = {
    val delta = address - Reset
    if (delta >= 0 && delta + 1 < image.length) {
      val offset = delta.toInt
      BigInt((image(offset).toInt & 0xff) | ((image(offset + 1).toInt & 0xff) << 8))
    } else {
      NopParcel
    }
  }

  if (Required || WorkloadPath.nonEmpty) {
    it should "execute the pinned compiler-produced RV64C image on TinyPagedCore" in {
      val path = WorkloadPath.getOrElse(fail(
        "AETHERCORE_V2_RV64C_WORKLOAD_BIN is required by the RV64C workload gate"
      ))
      val imagePath = Paths.get(path)
      withClue(s"RV64C workload image does not exist at $path: ") {
        Files.isRegularFile(imagePath) shouldBe true
      }
      val image = Files.readAllBytes(imagePath)
      image.length should be > 0
      image.length should be <= 4096

      simulate(new TinyPagedCore(Config, Geometry)) { dut =>
        initialize(dut)

        var cycles = 0
        var compressedRetires = 0
        var wideRetires = 0
        var sawExpectedResult = false
        var sawBreakpoint = false
        var highestFetch = Reset

        while (cycles < 500 && !sawBreakpoint) {
          val fetchValid = dut.io.imem.valid.peek().litToBoolean
          val fetchAddress = dut.io.imem.addr.peek().litValue
          dut.io.imem.inst.poke(parcelAt(image, fetchAddress).U)
          dut.io.imem.fault.poke(false.B)

          if (fetchValid) {
            dut.io.imem.bytes.expect(2.U)
            if (fetchAddress > highestFetch) {
              highestFetch = fetchAddress
            }
          }

          // This focused compiler body is deliberately register-only. Any data
          // transaction would mean the software contract silently acquired a
          // stack/data-memory dependency and must be reviewed instead of hidden
          // by the RVC frontend qualification.
          dut.io.memoryRequest.valid.expect(false.B)

          if (dut.io.commit.valid.peek().litToBoolean) {
            val commitPc = dut.io.commit.pc.peek().litValue
            val exception = dut.io.commit.exception.peek().litToBoolean
            val instBytes = dut.io.commit.instBytes.peek().litValue

            if (exception) {
              val cause = dut.io.commit.exceptionCause.peek().litValue
              withClue(f"unexpected exception at pc=0x$commitPc%x: ") {
                cause shouldBe BigInt(MachineExceptionCode.Breakpoint)
              }
              sawExpectedResult shouldBe true
              sawBreakpoint = true
            } else {
              if (instBytes == BigInt(2)) {
                compressedRetires += 1
              } else if (instBytes == BigInt(4)) {
                wideRetires += 1
              } else {
                fail(s"unexpected retired instruction length $instBytes at pc=0x${commitPc.toString(16)}")
              }

              if (dut.io.commit.rdWrite.peek().litToBoolean &&
                  dut.io.commit.rd.peek().litValue == 10 &&
                  dut.io.commit.rdData.peek().litValue == ExpectedA0) {
                sawExpectedResult = true
              }
            }
          }

          dut.clock.step()
          cycles += 1
        }

        withClue("compiler-produced workload never produced a0=94: ") {
          sawExpectedResult shouldBe true
        }
        withClue("compiler-produced workload never reached its architectural breakpoint: ") {
          sawBreakpoint shouldBe true
        }
        withClue("compiler-produced workload retired no compressed instructions: ") {
          compressedRetires should be >= 2
        }
        withClue("compiler-produced workload retired no ordinary 32-bit instruction: ") {
          wideRetires should be >= 1
        }
        withClue("frontend never advanced beyond the reset parcel: ") {
          highestFetch should be > Reset
        }
      }
    }
  }
}
