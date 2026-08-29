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

/**
  * Integrated F6 VM/PMP proofs.
  *
  * These checks intentionally program privilege/PMP/SATP through the same
  * precise CSR retirement path used by F5, then exercise the F6 LSU from
  * Supervisor mode. They therefore prove the composition boundary rather than
  * poking translation controls behind the CSR owner.
  */
trait V2F6VirtualMemoryChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
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

  private def pokeDispatchBase(
      dut: TinyMemoryBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      immediate: BigInt = 0,
      rawInst: BigInt = 0x13,
      memoryKind: MemoryOperationKind.Type = MemoryOperationKind.None,
      memorySize: MemSize.Type = MemSize.Word,
      memoryUnsigned: Boolean = false,
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
    dut.io.dispatch.bits.decoded.lhsSource.poke(
      if (usesRs1) OperandSourceKind.Rs1 else OperandSourceKind.Zero
    )
    dut.io.dispatch.bits.decoded.rhsSource.poke(
      if (executionClass == ExecutionClass.Integer) OperandSourceKind.Immediate else OperandSourceKind.Zero
    )
    dut.io.dispatch.bits.decoded.rs1.poke(rs1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(0.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(usesRs1.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(false.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(writesRd.B)
    dut.io.dispatch.bits.decoded.immediate.poke(immediate.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(BranchType.None)
    dut.io.dispatch.bits.decoded.memory.kind.poke(memoryKind)
    dut.io.dispatch.bits.decoded.memory.size.poke(memorySize)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(memoryUnsigned.B)
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

  private def retireRegister(dut: TinyMemoryBackend, rd: Int, value: BigInt): Unit = {
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.io.commit.rdWrite.expect(true.B)
    dut.io.commit.rd.expect(rd.U)
    dut.io.commit.rdData.expect(value.U)
    dut.clock.step()
  }

  private def dispatchCsrWrite(
      dut: TinyMemoryBackend,
      pc: BigInt,
      address: Int,
      rs1: Int
  ): Unit =
    dispatch(dut) {
      pokeDispatchBase(
        dut,
        pc,
        ExecutionClass.System,
        rs1 = rs1,
        usesRs1 = true,
        rawInst = 0x00001073L,
        systemKind = SystemOperationKind.Csr,
        csrOp = CsrOp.Write,
        csrAddress = address
      )
    }

  private def writeCsr(
      dut: TinyMemoryBackend,
      pc: BigInt,
      address: Int,
      value: BigInt,
      scratchRd: Int = 1
  ): BigInt = {
    dispatchConstant(dut, pc, scratchRd, value)
    retireRegister(dut, scratchRd, value)
    dispatchCsrWrite(dut, pc + 4, address, scratchRd)
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.io.commit.rdWrite.expect(false.B)
    dut.clock.step()
    pc + 8
  }

  private def enterSupervisor(
      dut: TinyMemoryBackend,
      startPc: BigInt,
      supervisorPc: BigInt,
      rootPpn: BigInt
  ): BigInt = {
    var pc = startPc
    pc = writeCsr(
      dut,
      pc,
      SupervisorCsrAddress.Satp,
      (BigInt(8) << 60) | rootPpn
    )
    pc = writeCsr(dut, pc, MachineCsrAddress.Mepc, supervisorPc)
    // MPP=S (01b). Other writable state remains zero for this focused proof.
    pc = writeCsr(dut, pc, MachineCsrAddress.Mstatus, BigInt(1) << 11)

    dispatch(dut) {
      pokeDispatchBase(
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
    dut.io.privilegedRedirect.valid.expect(true.B)
    dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Return)
    dut.io.privilegedRedirect.bits.target.expect(supervisorPc.U)
    dut.clock.step()
    dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
    pc + 4
  }

  private def dispatchLoad(
      dut: TinyMemoryBackend,
      pc: BigInt,
      rd: Int,
      rs1: Int
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
        rawInst = 0x0000a103L,
        memoryKind = MemoryOperationKind.Load,
        memorySize = MemSize.Word,
        memoryUnsigned = true
      )
    }

  private def pte(
      ppn: BigInt,
      valid: Boolean = true,
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      user: Boolean = false,
      accessed: Boolean = false,
      dirty: Boolean = false
  ): BigInt =
    (ppn << 10) |
      (if (valid) BigInt(1) else BigInt(0)) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (write) BigInt(1) << 2 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (user) BigInt(1) << 4 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0)) |
      (if (dirty) BigInt(1) << 7 else BigInt(0))

  private def vpn(va: BigInt, level: Int): BigInt = {
    val mask = (BigInt(1) << geometry.vpnBitsPerLevel) - 1
    (va >> (geometry.pageOffsetBits + level * geometry.vpnBitsPerLevel)) & mask
  }

  private def pteAddress(tablePpn: BigInt, va: BigInt, level: Int): BigInt =
    (tablePpn << geometry.pageOffsetBits) + vpn(va, level) * geometry.pteBytes

  private def providePte(
      dut: TinyMemoryBackend,
      expectedAddress: BigInt,
      value: BigInt,
      maxCycles: Int = 64
  ): Unit = {
    var cycles = 0
    while (!dut.io.pteValid.peek().litToBoolean && cycles < maxCycles) {
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      cycles += 1
    }
    withClue(s"external PTE request did not arrive within $maxCycles cycles: ") {
      dut.io.pteValid.peek().litToBoolean shouldBe true
    }
    dut.io.pteAddress.expect(expectedAddress.U)
    dut.io.pteData.poke(value.U)
    dut.io.pteReady.poke(true.B)
    dut.clock.step()
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
  }

  private def allowAllPhysicalMemory(dut: TinyMemoryBackend, startPc: BigInt): BigInt = {
    var pc = startPc
    // RV64 PA56 exposes a 54-bit pmpaddr field. All encoded address bits set
    // invokes the PmpChecker full-domain NAPOT case; cfg=RWX|NAPOT = 0x1f.
    val fullDomainNapot = (BigInt(1) << (config.platform.paddrBits - 2)) - 1
    pc = writeCsr(dut, pc, PmpCsrAddress.pmpaddr(0), fullDomainNapot)
    pc = writeCsr(dut, pc, PmpCsrAddress.pmpcfg(64, 0), BigInt(0x1f))
    pc
  }

  behavior of "AetherCore v2 F6 integrated Sv39/PMP"

  it should "fail an S-mode page-table read locally when PMP denies the implicit PTW access" in {
    simulate(new TinyMemoryBackend(config, geometry)) { dut =>
      initialize(dut)

      val machinePc = BigInt("80020000", 16)
      val supervisorPc = BigInt("80200000", 16)
      val rootPpn = BigInt("10000", 16)
      val va = BigInt("1234567024", 16)

      // Leave all PMP entries OFF. S-mode unmatched accesses are denied.
      enterSupervisor(dut, machinePc, supervisorPc, rootPpn)
      dispatchConstant(dut, supervisorPc, rd = 1, value = va)
      retireRegister(dut, 1, va)
      dispatchLoad(dut, supervisorPc + 4, rd = 2, rs1 = 1)

      var cycles = 0
      var externalPteRequests = 0
      var externalDataRequests = 0
      while (!dut.io.commit.valid.peek().litToBoolean && cycles < 128) {
        if (dut.io.pteValid.peek().litToBoolean) externalPteRequests += 1
        if (dut.io.memoryRequest.valid.peek().litToBoolean) externalDataRequests += 1
        dut.clock.step()
        cycles += 1
      }

      withClue("PMP-denied implicit PTW access must retire as a precise load access fault: ") {
        dut.io.commit.valid.peek().litToBoolean shouldBe true
        externalPteRequests shouldBe 0
        externalDataRequests shouldBe 0
        dut.io.commit.exception.expect(true.B)
        dut.io.commit.exceptionCause.expect(MachineExceptionCode.LoadAccessFault.U)
        dut.io.commit.exceptionValue.expect(va.U)
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

  it should "walk three Sv39 levels in S-mode and retire the translated physical load" in {
    simulate(new TinyMemoryBackend(config, geometry)) { dut =>
      initialize(dut)

      val machinePc = BigInt("80030000", 16)
      val supervisorPc = BigInt("80300000", 16)
      val rootPpn = BigInt("10000", 16)
      val level1Ppn = BigInt("11000", 16)
      val level0Ppn = BigInt("12000", 16)
      val leafPpn = BigInt("2345678", 16)
      val va = BigInt("1234567024", 16)
      val translatedPa = (leafPpn << 12) | (va & 0xfff)

      val afterPmp = allowAllPhysicalMemory(dut, machinePc)
      enterSupervisor(dut, afterPmp, supervisorPc, rootPpn)

      dispatchConstant(dut, supervisorPc, rd = 1, value = va)
      retireRegister(dut, 1, va)
      dispatchLoad(dut, supervisorPc + 4, rd = 2, rs1 = 1)

      providePte(
        dut,
        pteAddress(rootPpn, va, level = 2),
        pte(level1Ppn)
      )
      providePte(
        dut,
        pteAddress(level1Ppn, va, level = 1),
        pte(level0Ppn)
      )
      providePte(
        dut,
        pteAddress(level0Ppn, va, level = 0),
        pte(leafPpn, read = true, accessed = true)
      )

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      withClue("translated data request did not appear: ") {
        dut.io.memoryRequest.valid.peek().litToBoolean shouldBe true
      }
      dut.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
      dut.io.memoryRequest.bits.paddr.expect(translatedPa.U)
      val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.txnId.poke(txn.U)
      dut.io.memoryResponse.bits.rdata.poke(BigInt("0000000089abcdef", 16).U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.last.poke(true.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      awaitCommit(dut)
      withClue("translated load must become one precise architectural retirement: ") {
        dut.io.commit.exception.expect(false.B)
        dut.io.commit.rdWrite.expect(true.B)
        dut.io.commit.rd.expect(2.U)
        dut.io.commit.rdData.expect(BigInt("0000000089abcdef", 16).U)
        dut.io.commit.memValid.expect(true.B)
        dut.io.commit.memWrite.expect(false.B)
        dut.io.commit.memAddr.expect(translatedPa.U)
      }
      dut.clock.step()
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      dut.io.occupancy.expect(0.U)
    }
  }
}
