package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MachineExceptionCode, MemSize, PrivilegeMode, XRetOp}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.{MachineCsrAddress, PmpCsrAddress, SupervisorCsrAddress}
import aethercore.core.v2._
import aethercore.memory.AetherMemOp

/** Final F6 memory-closure checks before freeze qualification. */
trait V2F6MemoryClosureChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val config = CoreProfiles.rv64imsuSv39PmpSoftware
  private val geometry = PageTableGeometry.Sv39

  private def initialize(dut: TinyMemoryBackend): Unit = {
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

  private def pokeBase(
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
      systemKind: SystemOperationKind.Type = SystemOperationKind.None,
      csrOp: CsrOp.Type = CsrOp.None,
      csrAddress: Int = 0,
      xret: XRetOp.Type = XRetOp.None
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
    dut.io.dispatch.bits.decoded.lhsSource.poke(if (usesRs1) OperandSourceKind.Rs1 else OperandSourceKind.Zero)
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
    dut.io.dispatch.bits.decoded.memory.size.poke(MemSize.Word)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(true.B)
    dut.io.dispatch.bits.decoded.memory.atomicOp.poke(AtomicOp.None)
    dut.io.dispatch.bits.decoded.memory.acquire.poke(false.B)
    dut.io.dispatch.bits.decoded.memory.release.poke(false.B)
    dut.io.dispatch.bits.decoded.system.kind.poke(systemKind)
    dut.io.dispatch.bits.decoded.system.csrOp.poke(csrOp)
    dut.io.dispatch.bits.decoded.system.csrAddress.poke(csrAddress.U)
    dut.io.dispatch.bits.decoded.system.csrUseImmediate.poke(false.B)
    dut.io.dispatch.bits.decoded.system.csrImmediate.poke(0.U)
    dut.io.dispatch.bits.decoded.system.xret.poke(xret)
    dut.io.dispatch.bits.decoded.ordering.poke(
      if (executionClass == ExecutionClass.System) OrderingClass.SerializeBoth else OrderingClass.Normal
    )
    dut.io.dispatch.bits.decoded.exception.valid.poke(false.B)
    dut.io.dispatch.bits.decoded.exception.cause.poke(0.U)
    dut.io.dispatch.bits.decoded.exception.value.poke(0.U)
  }

  private def dispatch(dut: TinyMemoryBackend)(poke: => Unit): Unit = {
    poke
    var cycles = 0
    while (!dut.io.dispatch.ready.peek().litToBoolean && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    withClue("dispatch did not become ready: ") {
      dut.io.dispatch.ready.peek().litToBoolean shouldBe true
    }
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def awaitCommit(dut: TinyMemoryBackend, maxCycles: Int = 160): Unit = {
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"commit did not arrive within $maxCycles cycles: ") {
      dut.io.commit.valid.peek().litToBoolean shouldBe true
    }
  }

  private def constant(dut: TinyMemoryBackend, pc: BigInt, rd: Int, value: BigInt): Unit =
    dispatch(dut) {
      pokeBase(dut, pc, ExecutionClass.Integer, rd = rd, writesRd = true, producesValue = true, immediate = value)
    }

  private def retireRegister(dut: TinyMemoryBackend, rd: Int, value: BigInt): Unit = {
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.io.commit.rdWrite.expect(true.B)
    dut.io.commit.rd.expect(rd.U)
    dut.io.commit.rdData.expect(value.U)
    dut.clock.step()
  }

  private def writeCsr(dut: TinyMemoryBackend, pc: BigInt, address: Int, value: BigInt): BigInt = {
    constant(dut, pc, 1, value)
    retireRegister(dut, 1, value)
    dispatch(dut) {
      pokeBase(
        dut,
        pc + 4,
        ExecutionClass.System,
        rs1 = 1,
        usesRs1 = true,
        rawInst = 0x00001073L,
        systemKind = SystemOperationKind.Csr,
        csrOp = CsrOp.Write,
        csrAddress = address
      )
    }
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.clock.step()
    pc + 8
  }

  private def enterSupervisor(dut: TinyMemoryBackend, startPc: BigInt, supervisorPc: BigInt, rootPpn: BigInt): Unit = {
    var pc = startPc
    val fullDomainNapot = (BigInt(1) << (config.platform.paddrBits - 2)) - 1
    pc = writeCsr(dut, pc, PmpCsrAddress.pmpaddr(0), fullDomainNapot)
    pc = writeCsr(dut, pc, PmpCsrAddress.pmpcfg(64, 0), BigInt(0x1f))
    pc = writeCsr(dut, pc, SupervisorCsrAddress.Satp, (BigInt(8) << 60) | rootPpn)
    pc = writeCsr(dut, pc, MachineCsrAddress.Mepc, supervisorPc)
    pc = writeCsr(dut, pc, MachineCsrAddress.Mstatus, BigInt(1) << 11)
    dispatch(dut) {
      pokeBase(
        dut,
        pc,
        ExecutionClass.System,
        rawInst = 0x30200073L,
        systemKind = SystemOperationKind.Xret,
        xret = XRetOp.Machine
      )
    }
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.clock.step()
    dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
  }

  private def dispatchStore(dut: TinyMemoryBackend, pc: BigInt, rs1: Int, rs2: Int): Unit =
    dispatch(dut) {
      pokeBase(
        dut,
        pc,
        ExecutionClass.Memory,
        rs1 = rs1,
        rs2 = rs2,
        usesRs1 = true,
        usesRs2 = true,
        rawInst = 0x0020a023L,
        memoryKind = MemoryOperationKind.Store
      )
    }

  private def dispatchLoad(dut: TinyMemoryBackend, pc: BigInt, rd: Int, rs1: Int): Unit =
    dispatch(dut) {
      pokeBase(
        dut,
        pc,
        ExecutionClass.Memory,
        rd = rd,
        rs1 = rs1,
        usesRs1 = true,
        writesRd = true,
        producesValue = true,
        rawInst = 0x0000a183L,
        memoryKind = MemoryOperationKind.Load
      )
    }

  private def vpn(va: BigInt, level: Int): BigInt = {
    val mask = (BigInt(1) << geometry.vpnBitsPerLevel) - 1
    (va >> (geometry.pageOffsetBits + level * geometry.vpnBitsPerLevel)) & mask
  }

  private def pteAddress(tablePpn: BigInt, va: BigInt, level: Int): BigInt =
    (tablePpn << geometry.pageOffsetBits) + vpn(va, level) * geometry.pteBytes

  private def pointerPte(ppn: BigInt): BigInt = (ppn << 10) | BigInt(1)
  private def storeLeafPte(ppn: BigInt): BigInt =
    (ppn << 10) | BigInt(1) | (BigInt(1) << 1) | (BigInt(1) << 2) | (BigInt(1) << 6) | (BigInt(1) << 7)

  private def providePte(dut: TinyMemoryBackend, address: BigInt, value: BigInt): Unit = {
    var cycles = 0
    while (!dut.io.pteValid.peek().litToBoolean && cycles < 64) {
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      cycles += 1
    }
    withClue("PTE request did not arrive: ") {
      dut.io.pteValid.peek().litToBoolean shouldBe true
    }
    dut.io.pteAddress.expect(address.U)
    dut.io.pteData.poke(value.U)
    dut.io.pteReady.poke(true.B)
    dut.clock.step()
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
  }

  private def walkToLeaf(
      dut: TinyMemoryBackend,
      va: BigInt,
      rootPpn: BigInt,
      level1Ppn: BigInt,
      level0Ppn: BigInt,
      leaf: BigInt
  ): Unit = {
    providePte(dut, pteAddress(rootPpn, va, 2), pointerPte(level1Ppn))
    providePte(dut, pteAddress(level1Ppn, va, 1), pointerPte(level0Ppn))
    providePte(dut, pteAddress(level0Ppn, va, 0), leaf)
  }

  behavior of "AetherCore v2 F6 memory closure"

  it should "retire a translated S-mode store and precisely trap an invalid-leaf page fault" in {
    simulate(new TinyMemoryBackend(config, geometry)) { dut =>
      initialize(dut)

      val machinePc = BigInt("80060000", 16)
      val supervisorPc = BigInt("80600000", 16)
      val rootPpn = BigInt("40000", 16)
      val level1Ppn = BigInt("41000", 16)
      val level0Ppn = BigInt("42000", 16)
      val storeLeafPpn = BigInt("2345a00", 16)
      val storeVa = BigInt("3456782028", 16)
      val storePa = (storeLeafPpn << 12) | (storeVa & 0xfff)
      val storeData = BigInt("89abcdef", 16)
      val faultVa = storeVa + BigInt("4000", 16)

      enterSupervisor(dut, machinePc, supervisorPc, rootPpn)

      constant(dut, supervisorPc, 1, storeVa)
      retireRegister(dut, 1, storeVa)
      constant(dut, supervisorPc + 4, 2, storeData)
      retireRegister(dut, 2, storeData)
      dispatchStore(dut, supervisorPc + 8, rs1 = 1, rs2 = 2)

      walkToLeaf(dut, storeVa, rootPpn, level1Ppn, level0Ppn, storeLeafPte(storeLeafPpn))

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      withClue("translated store request did not appear: ") {
        dut.io.memoryRequest.valid.peek().litToBoolean shouldBe true
      }
      dut.io.memoryRequest.bits.op.expect(AetherMemOp.Write)
      dut.io.memoryRequest.bits.paddr.expect(storePa.U)
      dut.io.memoryRequest.bits.wdata.expect(storeData.U)
      dut.io.memoryRequest.bits.wmask.expect("hf".U)
      val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(txn.U)
      dut.io.memoryResponse.bits.rdata.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      awaitCommit(dut)
      withClue("translated store retirement trace: ") {
        dut.io.commit.exception.expect(false.B)
        dut.io.commit.rdWrite.expect(false.B)
        dut.io.commit.memValid.expect(true.B)
        dut.io.commit.memWrite.expect(true.B)
        dut.io.commit.memAddr.expect(storePa.U)
        dut.io.commit.memWdata.expect(storeData.U)
        dut.io.commit.memWmask.expect("hf".U)
      }
      dut.clock.step()

      constant(dut, supervisorPc + 12, 3, faultVa)
      retireRegister(dut, 3, faultVa)
      dispatchLoad(dut, supervisorPc + 16, rd = 4, rs1 = 3)

      providePte(dut, pteAddress(rootPpn, faultVa, 2), pointerPte(level1Ppn))
      providePte(dut, pteAddress(level1Ppn, faultVa, 1), pointerPte(level0Ppn))
      providePte(dut, pteAddress(level0Ppn, faultVa, 0), 0)

      var externalDataRequests = 0
      cycles = 0
      while (!dut.io.commit.valid.peek().litToBoolean && cycles < 128) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) externalDataRequests += 1
        dut.clock.step()
        cycles += 1
      }
      withClue("invalid leaf must become a precise load page fault without final data traffic: ") {
        dut.io.commit.valid.peek().litToBoolean shouldBe true
        externalDataRequests shouldBe 0
        dut.io.commit.exception.expect(true.B)
        dut.io.commit.exceptionCause.expect(MachineExceptionCode.LoadPageFault.U)
        dut.io.commit.exceptionValue.expect(faultVa.U)
        dut.io.commit.rdWrite.expect(false.B)
        dut.io.commit.memValid.expect(false.B)
        dut.io.privilegedRedirect.valid.expect(true.B)
        dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Trap)
      }
      dut.clock.step()
      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)
      dut.io.occupancy.expect(0.U)
    }
  }
}
