package aethercore

import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize}
import aethercore.core.product._
import aethercore.core.semantic.MemoryOperationKind
import aethercore.memory.AetherMemOp
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Trace is an architectural ROB-retirement observation, never a request or
  * out-of-order physical completion pulse.
  */
class ProductCommitTraceSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product exact retirement and physical memory trace"

  private val config = ProductCoreProfiles.smallEngineering

  private def init(d: ProductPhysicalLsq): Unit = {
    d.io.reserve.valid.poke(false.B)
    d.io.reserve.bits.poke(0.U.asTypeOf(d.io.reserve.bits))
    d.io.resolve.valid.poke(false.B)
    d.io.resolve.bits.poke(0.U.asTypeOf(d.io.resolve.bits))
    d.io.robHead.valid.poke(true.B)
    d.io.robHead.bits.poke(0.U.asTypeOf(d.io.robHead.bits))
    d.io.kill.valid.poke(false.B)
    d.io.kill.bits.poke(0.U.asTypeOf(d.io.kill.bits))
    d.io.memoryRequest.ready.poke(true.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.io.completion.ready.poke(false.B)
    d.clock.step()
  }

  private def runMemory(
    d: ProductPhysicalLsq, index: Int, kind: MemoryOperationKind.Type,
    size: MemSize.Type, atomicOp: AtomicOp.Type,
    addr: BigInt, storeData: BigInt, oldValue: BigInt,
    expectedWrite: Boolean, expectedTrace: Boolean, expectedWriteData: BigInt,
    expectedMask: Int
  ): Unit = {
    d.io.robHead.bits.index.poke(index.U)
    d.io.reserve.bits.token.index.poke(index.U)
    d.io.reserve.bits.token.generation.poke(0.U)
    d.io.reserve.bits.kind.poke(kind)
    d.io.reserve.bits.size.poke(size)
    d.io.reserve.bits.unsigned.poke(false.B)
    d.io.reserve.bits.atomicOp.poke(atomicOp)
    d.io.reserve.bits.destination.index.poke(0.U)
    d.io.reserve.bits.destination.generation.poke(0.U)
    d.io.reserve.bits.hasDestination.poke(false.B)
    d.io.reserve.valid.poke(true.B)
    d.io.reserve.ready.expect(true.B)
    d.clock.step()
    d.io.reserve.valid.poke(false.B)

    d.io.resolve.bits.token.index.poke(index.U)
    d.io.resolve.bits.token.generation.poke(0.U)
    d.io.resolve.bits.virtualAddress.poke(addr.U)
    d.io.resolve.bits.paddr.poke(addr.U)
    d.io.resolve.bits.storeData.poke(storeData.U)
    d.io.resolve.bits.attributes.cacheable.poke(true.B)
    d.io.resolve.bits.attributes.idempotent.poke(true.B)
    d.io.resolve.bits.attributes.sideEffecting.poke(false.B)
    d.io.resolve.bits.attributes.ordered.poke(false.B)
    d.io.resolve.bits.attributes.executable.poke(true.B)
    d.io.resolve.bits.attributes.supportsAtomic.poke(true.B)
    d.io.resolve.bits.attributes.supportsPartial.poke(true.B)
    d.io.resolve.bits.fault.poke(0.U.asTypeOf(d.io.resolve.bits.fault))
    d.io.resolve.valid.poke(true.B)
    d.clock.step()
    d.io.resolve.valid.poke(false.B)
    d.io.memoryRequest.valid.expect(true.B)
    d.io.memoryRequest.bits.paddr.expect(addr.U)
    val txn = d.io.memoryRequest.bits.txnId.peek().litValue
    d.clock.step()

    d.io.memoryResponse.bits.txnId.poke(txn.U)
    d.io.memoryResponse.bits.rdata.poke(oldValue.U)
    d.io.memoryResponse.bits.fault.poke(false.B)
    d.io.memoryResponse.bits.last.poke(true.B)
    d.io.memoryResponse.valid.poke(true.B)
    d.io.memoryResponse.ready.expect(true.B)
    d.clock.step()
    d.io.memoryResponse.valid.poke(false.B)
    d.io.completion.valid.expect(true.B)
    d.io.completion.bits.token.index.expect(index.U)
    d.io.completion.bits.memoryTrace.valid.expect(expectedTrace.B)
    d.io.completion.bits.memoryTrace.write.expect(expectedWrite.B)
    d.io.completion.bits.memoryTrace.paddr.expect(addr.U)
    d.io.completion.bits.memoryTrace.wdata.expect(expectedWriteData.U)
    d.io.completion.bits.memoryTrace.wmask.expect(expectedMask.U)
    d.io.completion.ready.poke(true.B)
    d.clock.step()
    d.io.completion.ready.poke(false.B)
  }

  it should "trace Load, Store, SC outcomes and actual AMO arithmetic/comparison writes" in {
    simulate(new ProductPhysicalLsq(config.geometry, config.platform.paddrBits)) { d =>
      init(d)
      runMemory(d, 0, MemoryOperationKind.Load, MemSize.Word, AtomicOp.None,
        0x80000000L, 0, 0x12345678L,
        expectedWrite = false, expectedTrace = true, expectedWriteData = 0, expectedMask = 0)
      runMemory(d, 1, MemoryOperationKind.Store, MemSize.Word, AtomicOp.None,
        0x80000004L, 0xabcdefL, 0,
        expectedWrite = true, expectedTrace = true, expectedWriteData = 0xabcdefL, expectedMask = 15)
      // AetherMem SC convention is 0=success, nonzero=reservation failed.
      // A failed SC has no architectural physical memory trace.
      runMemory(d, 2, MemoryOperationKind.Atomic, MemSize.Word, AtomicOp.Sc,
        0x80000008L, 0xffL, 1,
        expectedWrite = false, expectedTrace = false, expectedWriteData = 0, expectedMask = 0)
      // AMOADD.W: 0xffffffff + 3 wraps to 2 in 32 bits. Trace observes the
      // actual write, not the rs2 operand or sign-extended old value.
      runMemory(d, 3, MemoryOperationKind.Atomic, MemSize.Word, AtomicOp.Add,
        0x8000000cL, 3, 0xffffffffL,
        expectedWrite = true, expectedTrace = true, expectedWriteData = 2, expectedMask = 15)
      // Successful SC publishes the write at its physical address.
      runMemory(d, 4, MemoryOperationKind.Atomic, MemSize.Word, AtomicOp.Sc,
        0x80000010L, 0x5555, 0,
        expectedWrite = true, expectedTrace = true, expectedWriteData = 0x5555, expectedMask = 15)
      // AMOMIN.W compares signed 32-bit operands; -2^31 is less than +1.
      runMemory(d, 5, MemoryOperationKind.Atomic, MemSize.Word, AtomicOp.Min,
        0x80000014L, 1, 0x80000000L,
        expectedWrite = true, expectedTrace = true,
        expectedWriteData = 0x80000000L, expectedMask = 15)
      // AMOMAXU.D compares unsigned 64-bit operands; high-bit old wins.
      runMemory(d, 6, MemoryOperationKind.Atomic, MemSize.DWord, AtomicOp.Maxu,
        0x80000018L, BigInt("7fffffffffffffff", 16), BigInt("8000000000000000", 16),
        expectedWrite = true, expectedTrace = true,
        expectedWriteData = BigInt("8000000000000000", 16), expectedMask = 255)
    }
  }

  private def init(d: ProductSmallPagedCore): Unit = {
    d.io.imem.inst.poke("h00000013".U)
    d.io.imem.fault.poke(false.B)
    d.io.ptw.ready.poke(false.B)
    d.io.ptw.rdata.poke(0.U)
    d.io.ptw.fault.poke(false.B)
    d.io.resolvedAttributes.cacheable.poke(true.B)
    d.io.resolvedAttributes.idempotent.poke(true.B)
    d.io.resolvedAttributes.sideEffecting.poke(false.B)
    d.io.resolvedAttributes.ordered.poke(false.B)
    d.io.resolvedAttributes.executable.poke(true.B)
    d.io.resolvedAttributes.supportsAtomic.poke(true.B)
    d.io.resolvedAttributes.supportsPartial.poke(true.B)
    d.io.memoryRequest.ready.poke(true.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    if (config.software.isa.hasTimeCounter) d.io.time.get.poke(0.U)
    d.io.timerInterrupt.poke(false.B)
    if (d.withMachineExternalInterrupt) d.io.machineExternalInterrupt.get.poke(false.B)
    if (d.withSupervisorExternalInterrupt) d.io.supervisorExternalInterrupt.get.poke(false.B)
    if (d.enableInstructionBackpressure) d.io.imemReady.get.poke(true.B)
    d.clock.step()
  }

  it should "report decoded and raw instruction, architectural writeback and precise ECALL" in {
    simulate(new ProductSmallPagedCore(config)) { d =>
      init(d)
      val reset = config.platform.resetVector
      val addi = BigInt("00500093", 16)
      val ecall = BigInt("00000073", 16)
      val words = Map(reset -> addi, (reset + 4) -> ecall)
      var sawAddi = false
      var sawEcall = false
      for (_ <- 0 until 115) {
        val addr = d.io.imem.addr.peek().litValue
        d.io.imem.inst.poke(words.getOrElse(addr, BigInt("00000013", 16)).U)
        if (d.io.commit.valid.peek().litToBoolean) {
          if (d.io.commit.pc.peek().litValue == reset) {
            d.io.commit.inst.expect(addi.U)
            d.io.commit.rawInst.expect(addi.U)
            d.io.commit.instBytes.expect(4.U)
            d.io.commit.rd.expect(1.U)
            d.io.commit.rdWrite.expect(true.B)
            d.io.commit.rdData.expect(5.U)
            d.io.commit.memValid.expect(false.B)
            d.io.commit.exception.expect(false.B)
            sawAddi = true
          }
          if (d.io.commit.pc.peek().litValue == reset + 4) {
            d.io.commit.inst.expect(ecall.U)
            d.io.commit.rawInst.expect(ecall.U)
            d.io.commit.instBytes.expect(4.U)
            d.io.commit.rdWrite.expect(false.B)
            d.io.commit.memValid.expect(false.B)
            d.io.commit.exception.expect(true.B)
            d.io.commit.exceptionCause.expect(MachineExceptionCode.EnvironmentCallFromM.U)
            d.io.commit.exceptionValue.expect(0.U)
            sawEcall = true
          }
        }
        d.clock.step()
      }
      sawAddi shouldBe true
      sawEcall shouldBe true
    }
  }

  it should "elaborate and retire with a 32-bit physical address trace without width adaptation" in {
    val narrow = config.copy(platform = config.platform.copy(
      resetVector = BigInt("1000", 16), paddrBits = 32))
    simulate(new ProductSmallPagedCore(narrow)) { d =>
      init(d)
      d.io.resolvedPhysicalAddress.getWidth shouldBe 32
      d.io.memoryRequest.bits.paddr.getWidth shouldBe 32
      d.io.retire.bits.memoryTrace.paddr.getWidth shouldBe 32
      d.io.commit.memAddr.getWidth shouldBe 32
      var sawRetirement = false
      for (_ <- 0 until 50) {
        d.io.imem.inst.poke(BigInt("00000013", 16).U)
        if (d.io.commit.valid.peek().litToBoolean) {
          d.io.commit.exception.expect(false.B)
          d.io.commit.inst.expect(BigInt("00000013", 16).U)
          sawRetirement = true
        }
        d.clock.step()
      }
      sawRetirement shouldBe true
    }
  }
}
