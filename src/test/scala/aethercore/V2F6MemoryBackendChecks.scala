package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MachineExceptionCode, MemSize, XRetOp}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2._
import aethercore.memory.AetherMemOp

/** End-to-end F6 checks across dependency/ROB/LSU/Commit ownership. */
trait V2F6MemoryBackendChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeEnvironment(dut: TinyMemoryBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    dut.io.time.foreach(_.poke(0.U))

    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
    dut.io.pteFault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(false.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(false.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def pokeDispatchBase(
      dut: TinyMemoryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      rs2: Int = 0,
      usesRs1: Boolean = false,
      usesRs2: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      immediate: BigInt = 0,
      rawInst: BigInt = 0x13,
      memoryKind: MemoryOperationKind.Type = MemoryOperationKind.None,
      memorySize: MemSize.Type = MemSize.Word,
      memoryUnsigned: Boolean = false,
      atomicOp: AtomicOp.Type = AtomicOp.None
  ): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(executionClass)
    dut.io.dispatch.bits.producesValue.poke(producesValue.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke((rawInst & 0xffffffffL).U)
    dut.io.dispatch.bits.decoded.rawInst.poke((rawInst & 0xffffffffL).U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(AluOp.Add)
    dut.io.dispatch.bits.decoded.wordOp.poke(false.B)
    dut.io.dispatch.bits.decoded.lhsSource.poke(
      if (usesRs1) OperandSourceKind.Rs1 else OperandSourceKind.Zero
    )
    dut.io.dispatch.bits.decoded.rhsSource.poke(
      if (usesRs2) OperandSourceKind.Rs2
      else if (executionClass == ExecutionClass.Integer) OperandSourceKind.Immediate
      else OperandSourceKind.Zero
    )
    dut.io.dispatch.bits.decoded.rs1.poke(rs1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(rs2.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(usesRs1.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(usesRs2.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(writesRd.B)
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(BranchType.None)
    dut.io.dispatch.bits.decoded.memory.kind.poke(memoryKind)
    dut.io.dispatch.bits.decoded.memory.size.poke(memorySize)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(memoryUnsigned.B)
    dut.io.dispatch.bits.decoded.memory.atomicOp.poke(atomicOp)
    dut.io.dispatch.bits.decoded.memory.acquire.poke(false.B)
    dut.io.dispatch.bits.decoded.memory.release.poke(false.B)
    dut.io.dispatch.bits.decoded.system.kind.poke(SystemOperationKind.None)
    dut.io.dispatch.bits.decoded.system.csrOp.poke(CsrOp.None)
    dut.io.dispatch.bits.decoded.system.csrAddress.poke(0.U)
    dut.io.dispatch.bits.decoded.system.csrUseImmediate.poke(false.B)
    dut.io.dispatch.bits.decoded.system.csrImmediate.poke(0.U)
    dut.io.dispatch.bits.decoded.system.xret.poke(XRetOp.None)
    dut.io.dispatch.bits.decoded.ordering.poke(OrderingClass.Normal)
    dut.io.dispatch.bits.decoded.exception.valid.poke(false.B)
    dut.io.dispatch.bits.decoded.exception.cause.poke(0.U)
    dut.io.dispatch.bits.decoded.exception.value.poke(0.U)
  }

  private def dispatch(dut: TinyMemoryBackend)(poke: => Unit): Unit = {
    poke
    var cycles = 0
    while (!dut.io.dispatch.ready.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    withClue("dispatch did not become ready: ") {
      dut.io.dispatch.ready.peek().litToBoolean shouldBe true
    }
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def dispatchConstant(dut: TinyMemoryBackend, pc: BigInt, rd: Int, value: BigInt): Unit =
    dispatch(dut) {
      pokeDispatchBase(
        dut,
        pc,
        ExecutionClass.Integer,
        rd = rd,
        writesRd = true,
        producesValue = true,
        immediate = value
      )
    }

  private def dispatchLoad(
      dut: TinyMemoryBackend,
      pc: BigInt,
      rd: Int,
      rs1: Int,
      offset: BigInt = 0,
      size: MemSize.Type = MemSize.Word,
      unsigned: Boolean = true
  ): Unit =
    dispatch(dut) {
      pokeDispatchBase(
        dut,
        pc,
        ExecutionClass.Memory,
        rd = rd,
        rs1 = rs1,
        usesRs1 = true,
        writesRd = true,
        producesValue = true,
        immediate = offset,
        rawInst = 0x0000a103L,
        memoryKind = MemoryOperationKind.Load,
        memorySize = size,
        memoryUnsigned = unsigned
      )
    }

  private def dispatchStore(
      dut: TinyMemoryBackend,
      pc: BigInt,
      rs1: Int,
      rs2: Int,
      offset: BigInt = 0,
      size: MemSize.Type = MemSize.Word
  ): Unit =
    dispatch(dut) {
      pokeDispatchBase(
        dut,
        pc,
        ExecutionClass.Memory,
        rs1 = rs1,
        rs2 = rs2,
        usesRs1 = true,
        usesRs2 = true,
        immediate = offset,
        rawInst = 0x0041a023L,
        memoryKind = MemoryOperationKind.Store,
        memorySize = size
      )
    }

  private def awaitCommit(dut: TinyMemoryBackend, maxCycles: Int = 128): Unit = {
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"commit did not arrive within $maxCycles cycles: ") {
      dut.io.commit.valid.peek().litToBoolean shouldBe true
    }
  }

  private def retireRegister(dut: TinyMemoryBackend, rd: Int, value: BigInt): Unit = {
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.io.commit.rdWrite.expect(true.B)
    dut.io.commit.rd.expect(rd.U)
    dut.io.commit.rdData.expect(value.U)
    dut.io.commit.memValid.expect(false.B)
    dut.clock.step()
  }

  private def awaitMemoryRequest(dut: TinyMemoryBackend, maxCycles: Int = 64): BigInt = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"physical memory request did not arrive within $maxCycles cycles: ") {
      dut.io.memoryRequest.valid.peek().litToBoolean shouldBe true
    }
    dut.io.memoryRequest.bits.txnId.peek().litValue
  }

  private def acceptMemoryRequest(dut: TinyMemoryBackend): BigInt = {
    val txn = awaitMemoryRequest(dut)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.clock.step()
    dut.io.memoryRequest.ready.poke(false.B)
    txn
  }

  private def respond(
      dut: TinyMemoryBackend,
      txn: BigInt,
      data: BigInt = 0,
      fault: Boolean = false
  ): Unit = {
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.bits.txnId.poke(txn.U)
    dut.io.memoryResponse.bits.rdata.poke(data.U)
    dut.io.memoryResponse.bits.fault.poke(fault.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
    dut.io.memoryResponse.ready.expect(true.B)
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.fault.poke(false.B)
  }

  behavior of "AetherCore v2 F6 integrated memory backend"

  it should "retire loads and stores through one precise ROB/Commit path and trap a bus fault" in {
    val config = CoreProfiles.rv64imsuSv39PmpSoftware
    simulate(new TinyMemoryBackend(config, PageTableGeometry.Sv39)) { dut =>
      pokeEnvironment(dut)
      dut.io.commit.memAddr.getWidth shouldBe 56
      dut.io.memoryRequest.bits.paddr.getWidth shouldBe 56

      val pc = BigInt("80010000", 16)

      withClue("load retirement: ") {
        dispatchConstant(dut, pc, rd = 1, value = 0x1000)
        retireRegister(dut, 1, 0x1000)

        dispatchLoad(dut, pc + 4, rd = 2, rs1 = 1)
        val txn = awaitMemoryRequest(dut)
        dut.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
        dut.io.memoryRequest.bits.paddr.expect(0x1000.U)
        val acceptedTxn = acceptMemoryRequest(dut)
        acceptedTxn shouldBe txn
        respond(dut, txn, data = 0x89abcdefL)

        awaitCommit(dut)
        dut.io.commit.exception.expect(false.B)
        dut.io.commit.rdWrite.expect(true.B)
        dut.io.commit.rd.expect(2.U)
        dut.io.commit.rdData.expect(BigInt("0000000089abcdef", 16).U)
        dut.io.commit.memValid.expect(true.B)
        dut.io.commit.memWrite.expect(false.B)
        dut.io.commit.memAddr.expect(0x1000.U)
        dut.io.commit.memWmask.expect(0.U)
        dut.clock.step()
      }

      withClue("store externalization and retirement trace: ") {
        dispatchConstant(dut, pc + 8, rd = 3, value = 0x2000)
        retireRegister(dut, 3, 0x2000)
        dispatchConstant(dut, pc + 12, rd = 4, value = 0x12345678L)
        retireRegister(dut, 4, 0x12345678L)

        dispatchStore(dut, pc + 16, rs1 = 3, rs2 = 4)
        val txn = awaitMemoryRequest(dut)
        dut.io.memoryRequest.bits.op.expect(AetherMemOp.Write)
        dut.io.memoryRequest.bits.paddr.expect(0x2000.U)
        dut.io.memoryRequest.bits.wdata.expect(0x12345678L.U)
        dut.io.memoryRequest.bits.wmask.expect("hf".U)
        acceptMemoryRequest(dut) shouldBe txn
        respond(dut, txn)

        awaitCommit(dut)
        dut.io.commit.exception.expect(false.B)
        dut.io.commit.rdWrite.expect(false.B)
        dut.io.commit.memValid.expect(true.B)
        dut.io.commit.memWrite.expect(true.B)
        dut.io.commit.memAddr.expect(0x2000.U)
        dut.io.commit.memWdata.expect(0x12345678L.U)
        dut.io.commit.memWmask.expect("hf".U)
        dut.clock.step()
      }

      withClue("bus access fault becomes a precise retiring trap: ") {
        dispatchLoad(dut, pc + 20, rd = 5, rs1 = 1, offset = 4)
        val txn = awaitMemoryRequest(dut)
        dut.io.memoryRequest.bits.paddr.expect(0x1004.U)
        acceptMemoryRequest(dut) shouldBe txn
        respond(dut, txn, fault = true)

        awaitCommit(dut)
        dut.io.commit.exception.expect(true.B)
        dut.io.commit.exceptionCause.expect(MachineExceptionCode.LoadAccessFault.U)
        dut.io.commit.exceptionValue.expect(0x1004.U)
        dut.io.commit.rdWrite.expect(false.B)
        dut.io.commit.memValid.expect(false.B)
        dut.io.privilegedRedirect.valid.expect(true.B)
        dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Trap)
        dut.clock.step()
      }

      dut.io.occupancy.expect(0.U)
    }
  }
}
