package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.AtomicOp
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore
import aethercore.memory.AetherMemOp

/**
  * OpenSBI's coldboot lottery uses an RV64 AMOSWAP.W.aq-style atomic exchange.
  * Keep this as a real machine-code F7 oracle so the firmware frontier does not
  * depend on v1-only atomic coverage.
  */
trait V2F7OpenSbiAtomicChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val Config = CoreProfiles.rv64imasuSv39PmpSoftware
  private val Geometry = PageTableGeometry.Sv39
  private val Reset = Config.platform.resetVector
  private val DataAddress = BigInt("100", 16)
  private val Nop = BigInt("00000013", 16)

  private val AtomicMemCode = AetherMemOp.Atomic.litValue
  private val SwapCode = AtomicOp.Swap.litValue

  // addi x1,x0,0x100; addi x2,x0,1;
  // amoswap.w.aq x3,x2,(x1); amoswap.w.aq x4,x2,(x1)
  private val Program: Map[BigInt, BigInt] = Seq(
    BigInt("10000093", 16),
    BigInt("00100113", 16),
    BigInt("0c20a1af", 16),
    BigInt("0c20a22f", 16)
  ).zipWithIndex.map { case (inst, index) => (Reset + index * 4) -> inst }.toMap

  behavior of "AetherCore v2 F7 OpenSBI coldboot atomic exchange"

  it should "execute consecutive RV64 AMOSWAP.W.aq operations with old-value return semantics" in {
    simulate(new TinyPagedCore(Config, Geometry)) { dut =>
      dut.io.imem.inst.poke(Nop.U)
      dut.io.imem.fault.poke(false.B)
      dut.io.time.foreach(_.poke(0.U))
      dut.io.ptw.ready.poke(true.B)
      dut.io.ptw.rdata.poke(0.U)
      dut.io.ptw.fault.poke(false.B)

      dut.io.resolvedAttributes.cacheable.poke(true.B)
      dut.io.resolvedAttributes.idempotent.poke(true.B)
      dut.io.resolvedAttributes.sideEffecting.poke(false.B)
      dut.io.resolvedAttributes.ordered.poke(false.B)
      dut.io.resolvedAttributes.executable.poke(false.B)
      dut.io.resolvedAttributes.supportsAtomic.poke(true.B)
      dut.io.resolvedAttributes.supportsPartial.poke(true.B)

      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.txnId.poke(0.U)
      dut.io.memoryResponse.bits.rdata.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)

      case class Pending(txnId: BigInt, oldValue: BigInt)
      var pending: Option[Pending] = None
      var memoryValue = BigInt(0)
      val committed = mutable.Map.empty[BigInt, BigInt]
      var swaps = 0

      var cycles = 0
      while (cycles < 400 && !committed.contains(Reset + 12)) {
        val fetchAddress = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(Program.getOrElse(fetchAddress, Nop).U)

        pending match {
          case Some(response) =>
            dut.io.memoryResponse.valid.poke(true.B)
            dut.io.memoryResponse.bits.txnId.poke(response.txnId.U)
            dut.io.memoryResponse.bits.rdata.poke(response.oldValue.U)
          case None =>
            dut.io.memoryResponse.valid.poke(false.B)
            dut.io.memoryResponse.bits.txnId.poke(0.U)
            dut.io.memoryResponse.bits.rdata.poke(0.U)
        }

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == Reset + 8 || pc == Reset + 12) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(true.B)
            dut.io.commit.memAddr.expect(DataAddress.U)
            dut.io.commit.memWdata.expect(1.U)
            dut.io.commit.memWmask.expect("hf".U)
            committed(pc) = dut.io.commit.rdData.peek().litValue
          }
        }

        val responseFire = pending.nonEmpty && dut.io.memoryResponse.ready.peek().litToBoolean
        val requestFire = dut.io.memoryRequest.valid.peek().litToBoolean
        var nextPending: Option[Pending] = None

        if (requestFire) {
          dut.io.memoryRequest.bits.op.peek().litValue shouldBe AtomicMemCode
          dut.io.memoryRequest.bits.atomicOp.peek().litValue shouldBe SwapCode
          dut.io.memoryRequest.bits.paddr.expect(DataAddress.U)
          dut.io.memoryRequest.bits.size.expect(aethercore.common.MemSize.Word)
          dut.io.memoryRequest.bits.wdata.expect(1.U)
          dut.io.memoryRequest.bits.wmask.expect("hf".U)

          val old = memoryValue
          memoryValue = BigInt(1)
          nextPending = Some(Pending(dut.io.memoryRequest.bits.txnId.peek().litValue, old))
          swaps += 1
        }

        dut.clock.step()
        cycles += 1

        if (responseFire) pending = None
        if (nextPending.nonEmpty) {
          withClue("blocking LSU issued a second AMOSWAP while a response was pending: ") {
            pending shouldBe None
          }
          pending = nextPending
        }
      }

      swaps shouldBe 2
      committed(Reset + 8) shouldBe BigInt(0)
      committed(Reset + 12) shouldBe BigInt(1)
      memoryValue shouldBe BigInt(1)
    }
  }
}
