package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{CsrOp, PrivilegeMode}
import aethercore.config.CoreProfiles
import aethercore.core.v2._

/** Small harness that exposes only the A8 system-completion transport facts. */
private class A8SystemCompletionHarness extends Module {
  val io = IO(new Bundle {
    val ready = Input(Bool())
    val csrReadData = Input(UInt(64.W))
    val headIndex = Input(UInt(2.W))
    val headGeneration = Input(UInt(8.W))
    val valid = Output(Bool())
    val value = Output(UInt(64.W))
    val responseIndex = Output(UInt(2.W))
    val responseGeneration = Output(UInt(8.W))
  })

  private val system = Module(new TinySystemCompletion(CoreProfiles.rv64imCurrent.isa))

  system.io.head.valid := true.B
  system.io.head.bits := 0.U.asTypeOf(system.io.head.bits)
  system.io.head.bits.executionClass := ExecutionClass.System
  system.io.head.bits.producesValue := true.B
  system.io.head.bits.robToken.index := io.headIndex
  system.io.head.bits.robToken.generation := io.headGeneration
  system.io.head.bits.producerTag.id := io.headIndex
  system.io.head.bits.producerTag.generation := io.headGeneration
  system.io.head.bits.valueRef.id := io.headIndex
  system.io.head.bits.valueRef.generation := io.headGeneration
  system.io.head.bits.decoded.rawInst := "h30002073".U
  system.io.head.bits.decoded.rs1 := 0.U
  system.io.head.bits.decoded.system.kind := SystemOperationKind.Csr
  system.io.head.bits.decoded.system.csrOp := CsrOp.Set
  system.io.head.bits.decoded.system.csrAddress := "h300".U
  system.io.head.bits.decoded.system.csrUseImmediate := false.B
  system.io.head.bits.decoded.system.csrImmediate := 0.U
  system.io.head.bits.decoded.exception.valid := false.B

  system.io.headDependenciesValid := true.B
  system.io.headRs1 := 0.U.asTypeOf(system.io.headRs1)
  system.io.headRs1.ready := true.B
  system.io.headOperandsReady := true.B
  system.io.csrReadData := io.csrReadData
  system.io.csrReadImplemented := true.B
  system.io.csrReadWritable := true.B
  system.io.currentPrivilege := PrivilegeMode.Machine.U
  system.io.completion.ready := io.ready

  io.valid := system.io.completion.valid
  io.value := system.io.completion.bits.value
  io.responseIndex := system.io.completion.bits.robToken.index
  io.responseGeneration := system.io.completion.bits.robToken.generation
}

/** Isolate round-robin transport from producer semantics. */
private class A8CompletionArbiterHarness extends Module {
  val io = IO(new Bundle {
    val sourceValid = Input(Vec(3, Bool()))
    val sourceReady = Output(Vec(3, Bool()))
    val outReady = Input(Bool())
    val outValid = Output(Bool())
    val outValue = Output(UInt(64.W))
  })

  private val merge = Module(new TinyCompletionArbiter(64, 3))
  for (index <- 0 until 3) {
    merge.io.in(index).valid := io.sourceValid(index)
    merge.io.in(index).bits := 0.U.asTypeOf(merge.io.in(index).bits)
    merge.io.in(index).bits.robToken.index := index.U
    merge.io.in(index).bits.robToken.generation := 0.U
    merge.io.in(index).bits.producerTag.id := index.U
    merge.io.in(index).bits.producerTag.generation := 0.U
    merge.io.in(index).bits.valueRef.id := index.U
    merge.io.in(index).bits.valueRef.generation := 0.U
    merge.io.in(index).bits.hasValue := true.B
    merge.io.in(index).bits.value := ((index + 1) * 11).U
    io.sourceReady(index) := merge.io.in(index).ready
  }
  merge.io.out.ready := io.outReady
  io.outValid := merge.io.out.valid
  io.outValue := merge.io.out.bits.value
}

trait V2A8CompletionChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  behavior of "AetherCore v2 A8 completion ownership"

  it should "hold a system completion bit-stable under backpressure and emit one observed lifetime once" in {
    simulate(new A8SystemCompletionHarness) { dut =>
      dut.io.ready.poke(false.B)
      dut.io.csrReadData.poke(0x111.U)
      dut.io.headIndex.poke(1.U)
      dut.io.headGeneration.poke(7.U)

      dut.io.valid.expect(true.B)
      dut.io.value.expect(0x111.U)
      dut.io.responseIndex.expect(1.U)
      dut.io.responseGeneration.expect(7.U)

      // The first blocked cycle captures the exact semantic result.
      dut.clock.step()
      dut.io.csrReadData.poke(0x222.U)
      for (_ <- 0 until 3) {
        dut.io.valid.expect(true.B)
        dut.io.value.expect(0x111.U)
        dut.io.responseIndex.expect(1.U)
        dut.io.responseGeneration.expect(7.U)
        dut.clock.step()
      }

      // Accept it. The unchanged head must not regenerate a duplicate response.
      dut.io.ready.poke(true.B)
      dut.io.valid.expect(true.B)
      dut.io.value.expect(0x111.U)
      dut.clock.step()
      dut.io.ready.poke(false.B)
      dut.io.valid.expect(false.B)

      // A genuinely new observed lifetime can immediately produce a new value.
      dut.io.headGeneration.poke(8.U)
      dut.io.valid.expect(true.B)
      dut.io.value.expect(0x222.U)
      dut.io.responseGeneration.expect(8.U)
    }
  }

  it should "give every simultaneously pending completion source bounded round-robin service" in {
    simulate(new A8CompletionArbiterHarness) { dut =>
      for (index <- 0 until 3) dut.io.sourceValid(index).poke(true.B)
      dut.io.outReady.poke(false.B)

      // Backpressure must leave the selected response stable.
      dut.io.outValid.expect(true.B)
      val held = dut.io.outValue.peek().litValue
      for (_ <- 0 until 2) {
        dut.clock.step()
        dut.io.outValid.expect(true.B)
        dut.io.outValue.expect(held.U)
      }

      dut.io.outReady.poke(true.B)
      var pending = Set(BigInt(11), BigInt(22), BigInt(33))
      var cycles = 0
      while (pending.nonEmpty && cycles < 6) {
        dut.io.outValid.expect(true.B)
        val value = dut.io.outValue.peek().litValue
        withClue(s"unexpected or duplicate completion value $value: ") {
          pending.contains(value) shouldBe true
        }
        val source = (value / 11).toInt - 1
        dut.io.sourceReady(source).expect(true.B)
        dut.clock.step()
        dut.io.sourceValid(source).poke(false.B)
        pending -= value
        cycles += 1
      }

      pending shouldBe empty
      cycles should be <= 3
    }
  }
}
