package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, PrivilegeMode, XRetOp}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.{MachineCsrAddress, PmpCsrAddress, SupervisorCsrAddress}
import aethercore.core.v2._
import aethercore.memory.AetherMemOp

/** Proves that SFENCE.VMA has no early side effect and flushes at retirement. */
trait V2F6SfenceChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val config = CoreProfiles.rv64imsuSv39PmpSoftware
  private val geometry = PageTableGeometry.Sv39

  private def initialize(dut: TinyMemoryBackend): Unit = {
    dut.io.dispatch.valid.poke(false.B)
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
      usesRs1: Boolean = false,
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
    dut.io.dispatch.ready.peek().litToBoolean shouldBe true
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def awaitCommit(dut: TinyMemoryBackend, maxCycles: Int = 160): Unit = {
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue("SFENCE test commit timeout: ") {
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
        rawInst = 0x0000a103L,
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
  private def leafPte(ppn: BigInt): BigInt = (ppn << 10) | BigInt(1) | (BigInt(1) << 1) | (BigInt(1) << 6)

  private def providePte(dut: TinyMemoryBackend, address: BigInt, value: BigInt): Unit = {
    var cycles = 0
    while (!dut.io.pteValid.peek().litToBoolean && cycles < 64) {
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      cycles += 1
    }
    dut.io.pteValid.peek().litToBoolean shouldBe true
    dut.io.pteAddress.expect(address.U)
    dut.io.pteData.poke(value.U)
    dut.io.pteReady.poke(true.B)
    dut.clock.step()
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
  }

  private def walk(dut: TinyMemoryBackend, va: BigInt, root: BigInt, l1: BigInt, l0: BigInt, leaf: BigInt): Unit = {
    providePte(dut, pteAddress(root, va, 2), pointerPte(l1))
    providePte(dut, pteAddress(l1, va, 1), pointerPte(l0))
    providePte(dut, pteAddress(l0, va, 0), leafPte(leaf))
  }

  private def acceptRead(dut: TinyMemoryBackend, expectedPa: BigInt, data: BigInt): Unit = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    dut.io.memoryRequest.valid.peek().litToBoolean shouldBe true
    dut.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
    dut.io.memoryRequest.bits.paddr.expect(expectedPa.U)
    val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
    dut.io.memoryRequest.ready.poke(true.B)
    dut.clock.step()
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.bits.txnId.poke(txn.U)
    dut.io.memoryResponse.bits.rdata.poke(data.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
    dut.io.memoryResponse.ready.expect(true.B)
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(false.B)
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.io.commit.memValid.expect(true.B)
    dut.io.commit.memWrite.expect(false.B)
    dut.io.commit.memAddr.expect(expectedPa.U)
    dut.clock.step()
  }

  behavior of "AetherCore v2 F6 SFENCE.VMA retirement ownership"

  it should "keep a cached mapping until SFENCE retires, then re-walk and use the new mapping" in {
    simulate(new TinyMemoryBackend(config, geometry)) { dut =>
      initialize(dut)
      val machinePc = BigInt("80050000", 16)
      val supervisorPc = BigInt("80500000", 16)
      val rootPpn = BigInt("30000", 16)
      val l1Ppn = BigInt("31000", 16)
      val l0Ppn = BigInt("32000", 16)
      val leaf1 = BigInt("3456700", 16)
      val leaf2 = BigInt("3456800", 16)
      val va = BigInt("2345678024", 16)
      val pa1 = (leaf1 << 12) | (va & 0xfff)
      val pa2 = (leaf2 << 12) | (va & 0xfff)

      enterSupervisor(dut, machinePc, supervisorPc, rootPpn)
      constant(dut, supervisorPc, 1, va)
      retireRegister(dut, 1, va)

      // Prime the TLB with VA -> PA1.
      dispatchLoad(dut, supervisorPc + 4, rd = 2, rs1 = 1)
      walk(dut, va, rootPpn, l1Ppn, l0Ppn, leaf1)
      acceptRead(dut, pa1, BigInt("11112222", 16))

      // Without a fence, the same VA must hit the cached translation even if
      // the external page-table model would now return PA2.
      dispatchLoad(dut, supervisorPc + 8, rd = 3, rs1 = 1)
      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 32) {
        dut.io.pteValid.expect(false.B)
        dut.clock.step()
        cycles += 1
      }
      dut.io.pteValid.expect(false.B)
      acceptRead(dut, pa1, BigInt("33334444", 16))

      // SFENCE completion is side-effect free; the observable flush pulse is
      // tied to the precise retirement of the matching system ROB head.
      dispatch(dut) {
        pokeBase(
          dut,
          supervisorPc + 12,
          ExecutionClass.System,
          rawInst = 0x12000073L,
          systemKind = SystemOperationKind.SfenceVma
        )
      }
      awaitCommit(dut)
      dut.io.commit.exception.expect(false.B)
      dut.io.translationFence.expect(true.B)
      dut.clock.step()
      dut.io.translationFence.expect(false.B)

      // The next access to the same VA must miss and observe the changed leaf.
      dispatchLoad(dut, supervisorPc + 16, rd = 4, rs1 = 1)
      walk(dut, va, rootPpn, l1Ppn, l0Ppn, leaf2)
      acceptRead(dut, pa2, BigInt("55556666", 16))
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      dut.io.occupancy.expect(0.U)
    }
  }
}
