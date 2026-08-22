package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, BranchType}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2._
import aethercore.memory.AetherMemOp

/** Regressions derived from the real OpenSBI fdt_size_cells() frontier.
  *
  * F7 exposed an important lifetime rule: RobToken is a bounded discriminator,
  * not a globally unique instruction number. Once-only issue state must be
  * scoped to the currently observed head lifetime. A8 widens generation so the
  * old 16-instruction numeric alias is no longer the implementation boundary;
  * the direct issue test below therefore protects the semantic rule without
  * depending on a particular generation width.
  */
trait V2F7GenerationWrapChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val Config = CoreProfiles.rv64imsuSv39PmpSoftware
  private val Reset = Config.platform.resetVector
  private val Nop = BigInt("00000013", 16)
  private val DataAddress = BigInt("100", 16)

  behavior of "AetherCore v2 bounded-generation issue ownership"

  it should "reissue a later load while the ROB head stays continuously occupied" in {
    simulate(new TinyBareCore(Config, PageTableGeometry.Sv39)) { dut =>
      dut.io.imem.inst.poke(Nop.U)
      dut.io.imem.fault.poke(false.B)
      dut.io.time.foreach(_.poke(0.U))
      dut.io.ptw.ready.poke(false.B)
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

      // Keep the original software-shaped frontier: two loads separated by
      // sixteen instructions while the tiny ROB never naturally becomes empty.
      // The direct issue regression below, rather than this program distance,
      // now protects numeric-token reuse independently of GenerationBits.
      val program = (0 until 18).map { index =>
        val inst = index match {
          case 0  => BigInt("10000093", 16) // addi x1,x0,0x100
          case 1  => BigInt("0000a183", 16) // lw x3,0(x1)
          case 17 => BigInt("0000a283", 16) // lw x5,0(x1)
          case _  => Nop
        }
        (Reset + index * 4) -> inst
      }.toMap

      var pendingTxn: Option[BigInt] = None
      var firstRequestCycle = -1
      var reads = 0
      var secondLoadCommitted = false
      var sawEmptyAfterFirstRequest = false
      var cycles = 0

      while (cycles < 500 && !secondLoadCommitted) {
        val fetchAddress = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(program.getOrElse(fetchAddress, Nop).U)

        val mayRespond = pendingTxn.nonEmpty &&
          (reads > 1 || cycles - firstRequestCycle >= 5)
        if (mayRespond) {
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.txnId.poke(pendingTxn.get.U)
          dut.io.memoryResponse.bits.rdata.poke(42.U)
        } else {
          dut.io.memoryResponse.valid.poke(false.B)
          dut.io.memoryResponse.bits.txnId.poke(0.U)
          dut.io.memoryResponse.bits.rdata.poke(0.U)
        }

        val responseFire = mayRespond && dut.io.memoryResponse.ready.peek().litToBoolean
        val requestFire = dut.io.memoryRequest.valid.peek().litToBoolean &&
          dut.io.memoryRequest.ready.peek().litToBoolean

        var newTxn: Option[BigInt] = None
        if (requestFire) {
          dut.io.memoryRequest.bits.op.peek().litValue shouldBe AetherMemOp.Read.litValue
          dut.io.memoryRequest.bits.paddr.expect(DataAddress.U)
          reads += 1
          if (reads == 1) firstRequestCycle = cycles
          newTxn = Some(dut.io.memoryRequest.bits.txnId.peek().litValue)
        }

        if (firstRequestCycle >= 0 && !secondLoadCommitted && dut.io.occupancy.peek().litValue == 0) {
          sawEmptyAfterFirstRequest = true
        }

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == Reset + 17 * 4) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(5.U)
          dut.io.commit.rdData.expect(42.U)
          secondLoadCommitted = true
        }

        dut.clock.step()
        cycles += 1

        if (responseFire) pendingTxn = None
        if (newTxn.nonEmpty) {
          withClue("blocking LSU accepted a new request with a response still pending: ") {
            pendingTxn shouldBe None
          }
          pendingTxn = newTxn
        }
      }

      withClue("the test accidentally allowed head.valid to become empty, masking the software-shaped frontier: ") {
        sawEmptyAfterFirstRequest shouldBe false
      }
      withClue("the later load was incorrectly suppressed by stale once-only issue state: ") {
        secondLoadCommitted shouldBe true
      }
      reads shouldBe 2
    }
  }

  it should "scope once-only issue state to an observed head lifetime rather than numeric token history" in {
    simulate(new TinyOldestIssue(64)) { dut =>
      def pokeHead(index: Int, generation: Int, pc: BigInt): Unit = {
        dut.io.head.valid.poke(true.B)
        dut.io.head.bits.executionClass.poke(ExecutionClass.Integer)
        dut.io.head.bits.robToken.index.poke(index.U)
        dut.io.head.bits.robToken.generation.poke(generation.U)
        dut.io.head.bits.producerTag.id.poke(index.U)
        dut.io.head.bits.producerTag.generation.poke(generation.U)
        dut.io.head.bits.valueRef.id.poke(index.U)
        dut.io.head.bits.valueRef.generation.poke(generation.U)
        dut.io.head.bits.decoded.aluOp.poke(AluOp.Add)
        dut.io.head.bits.decoded.wordOp.poke(false.B)
        dut.io.head.bits.decoded.lhsSource.poke(OperandSourceKind.Zero)
        dut.io.head.bits.decoded.rhsSource.poke(OperandSourceKind.Immediate)
        dut.io.head.bits.decoded.pc.poke(pc.U)
        dut.io.head.bits.decoded.instBytes.poke(4.U)
        dut.io.head.bits.decoded.immediate.poke(1.U)
        dut.io.head.bits.decoded.controlFlow.kind.poke(ControlFlowKind.None)
        dut.io.head.bits.decoded.controlFlow.branchType.poke(BranchType.None)
      }

      dut.io.headDependenciesValid.poke(true.B)
      dut.io.headOperandsReady.poke(true.B)
      dut.io.headRs1.ready.poke(true.B)
      dut.io.headRs1.value.poke(0.U)
      dut.io.headRs1.producerTag.id.poke(0.U)
      dut.io.headRs1.producerTag.generation.poke(0.U)
      dut.io.headRs2.ready.poke(true.B)
      dut.io.headRs2.value.poke(0.U)
      dut.io.headRs2.producerTag.id.poke(0.U)
      dut.io.headRs2.producerTag.generation.poke(0.U)

      // Lifetime A fires once and becomes the remembered issued token.
      pokeHead(index = 0, generation = 7, pc = BigInt("92000000", 16))
      dut.io.request.ready.poke(true.B)
      dut.io.request.valid.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.expect(false.B)

      // Observe a different head lifetime B but backpressure its request. The
      // old A latch must clear solely because the observed lifetime changed.
      pokeHead(index = 1, generation = 9, pc = BigInt("92000004", 16))
      dut.io.request.ready.poke(false.B)
      dut.io.request.valid.expect(true.B)
      dut.clock.step()

      // Reuse the exact same *numeric* identity as A. This models a later wrap
      // without requiring 256 per-slot reuses in a unit test. It must be
      // eligible because B proved that A's observed lifetime had ended.
      pokeHead(index = 0, generation = 7, pc = BigInt("92000400", 16))
      dut.io.request.ready.poke(true.B)
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.robToken.index.expect(0.U)
      dut.io.request.bits.robToken.generation.expect(7.U)
      dut.clock.step()
      dut.io.request.valid.expect(false.B)
    }
  }
}
