package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MachineExceptionCode, MemSize, PrivilegeMode, XRetOp}
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, Rv32SstcCsrAddress}
import aethercore.core.v2._

/** F5 closure checks for the synchronous privileged surface claimed by #144. */
trait V2F5PrivilegedClosureChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def closurePokeBase(
      dut: TinyPrivilegedBackend,
      pc: BigInt,
      executionClass: ExecutionClass.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      writesRd: Boolean = false,
      producesValue: Boolean = false,
      immediate: BigInt = 0,
      rawInst: BigInt = 0x13,
      systemKind: SystemOperationKind.Type = SystemOperationKind.None,
      csrOp: CsrOp.Type = CsrOp.None,
      csrAddress: Int = 0,
      csrUseImmediate: Boolean = false,
      csrImmediate: Int = 0,
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
    dut.io.dispatch.bits.decoded.memory.kind.poke(MemoryOperationKind.None)
    dut.io.dispatch.bits.decoded.memory.size.poke(MemSize.Word)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(false.B)
    dut.io.dispatch.bits.decoded.memory.atomicOp.poke(AtomicOp.None)
    dut.io.dispatch.bits.decoded.memory.acquire.poke(false.B)
    dut.io.dispatch.bits.decoded.memory.release.poke(false.B)
    dut.io.dispatch.bits.decoded.system.kind.poke(systemKind)
    dut.io.dispatch.bits.decoded.system.csrOp.poke(csrOp)
    dut.io.dispatch.bits.decoded.system.csrAddress.poke(csrAddress.U)
    dut.io.dispatch.bits.decoded.system.csrUseImmediate.poke(csrUseImmediate.B)
    dut.io.dispatch.bits.decoded.system.csrImmediate.poke(csrImmediate.U)
    dut.io.dispatch.bits.decoded.system.xret.poke(xret)
    dut.io.dispatch.bits.decoded.ordering.poke(
      if (executionClass == ExecutionClass.System) OrderingClass.SerializeBoth else OrderingClass.Normal
    )
    dut.io.dispatch.bits.decoded.exception.valid.poke(false.B)
    dut.io.dispatch.bits.decoded.exception.cause.poke(0.U)
    dut.io.dispatch.bits.decoded.exception.value.poke(0.U)
  }

  private def closureDispatch(dut: TinyPrivilegedBackend)(poke: => Unit): Unit = {
    poke
    dut.io.dispatch.ready.expect(true.B)
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def closureAwaitCommit(dut: TinyPrivilegedBackend, maxCycles: Int = 96): Unit = {
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"commit did not arrive within $maxCycles cycles") {
      dut.io.commit.valid.peek().litToBoolean shouldBe true
    }
  }

  private def closureDispatchConstant(
      dut: TinyPrivilegedBackend,
      pc: BigInt,
      rd: Int,
      value: BigInt
  ): Unit =
    closureDispatch(dut) {
      closurePokeBase(
        dut,
        pc,
        ExecutionClass.Integer,
        rd = rd,
        writesRd = true,
        producesValue = true,
        immediate = value
      )
    }

  private def closureRetireRegister(dut: TinyPrivilegedBackend, rd: Int, value: BigInt): Unit = {
    closureAwaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.io.commit.rdWrite.expect(true.B)
    dut.io.commit.rd.expect(rd.U)
    dut.io.commit.rdData.expect(value.U)
    dut.clock.step()
  }

  private def closureDispatchCsr(
      dut: TinyPrivilegedBackend,
      pc: BigInt,
      address: Int,
      op: CsrOp.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      producesValue: Boolean = false,
      useImmediate: Boolean = false,
      csrImmediate: Int = 0,
      rawInst: BigInt = 0x00001073L
  ): Unit =
    closureDispatch(dut) {
      closurePokeBase(
        dut,
        pc,
        ExecutionClass.System,
        rd = rd,
        rs1 = rs1,
        usesRs1 = usesRs1,
        writesRd = rd != 0,
        producesValue = producesValue,
        rawInst = rawInst,
        systemKind = SystemOperationKind.Csr,
        csrOp = op,
        csrAddress = address,
        csrUseImmediate = useImmediate,
        csrImmediate = csrImmediate
      )
    }

  private def closureWriteCsrFromRegister(
      dut: TinyPrivilegedBackend,
      pc: BigInt,
      address: Int,
      rs1: Int
  ): Unit = {
    closureDispatchCsr(
      dut,
      pc,
      address,
      CsrOp.Write,
      rs1 = rs1,
      usesRs1 = true
    )
    closureAwaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.clock.step()
  }

  private def closureDispatchXret(
      dut: TinyPrivilegedBackend,
      pc: BigInt,
      op: XRetOp.Type,
      rawInst: BigInt
  ): Unit =
    closureDispatch(dut) {
      closurePokeBase(
        dut,
        pc,
        ExecutionClass.System,
        rawInst = rawInst,
        systemKind = SystemOperationKind.Xret,
        xret = op
      )
    }

  private def closureExpectIllegalSystem(
      config: aethercore.config.CoreConfig,
      kind: SystemOperationKind.Type,
      raw: BigInt,
      pc: BigInt
  ): Unit = {
    simulate(new TinyPrivilegedBackend(config)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      closureDispatch(dut) {
        closurePokeBase(
          dut,
          pc,
          ExecutionClass.System,
          rawInst = raw,
          systemKind = kind
        )
      }
      closureAwaitCommit(dut)
      dut.io.commit.exception.expect(true.B)
      dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
      dut.io.commit.exceptionValue.expect(raw.U)
      dut.io.commit.rdWrite.expect(false.B)
      dut.io.privilegedRedirect.valid.expect(true.B)
    }
  }

  behavior of "AetherCore v2 F5 privileged closure"

  it should "execute CSR immediate write/set/clear at retirement" in {
    simulate(new TinyPrivilegedBackend(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val pc = BigInt("80005000", 16)

      closureDispatchCsr(
        dut,
        pc,
        MachineCsrAddress.Mscratch,
        CsrOp.Write,
        rd = 4,
        producesValue = true,
        useImmediate = true,
        csrImmediate = 5
      )
      closureRetireRegister(dut, 4, 0)

      closureDispatchCsr(
        dut,
        pc + 4,
        MachineCsrAddress.Mscratch,
        CsrOp.Set,
        rd = 5,
        producesValue = true,
        useImmediate = true,
        csrImmediate = 2
      )
      closureRetireRegister(dut, 5, 5)

      closureDispatchCsr(
        dut,
        pc + 8,
        MachineCsrAddress.Mscratch,
        CsrOp.Clear,
        rd = 6,
        producesValue = true,
        useImmediate = true,
        csrImmediate = 1
      )
      closureRetireRegister(dut, 6, 7)

      closureDispatchCsr(
        dut,
        pc + 12,
        MachineCsrAddress.Mscratch,
        CsrOp.Set,
        rd = 7,
        producesValue = true
      )
      closureRetireRegister(dut, 7, 6)
    }
  }

  it should "reject writes to a read-only CSR" in {
    simulate(new TinyPrivilegedBackend(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val raw = BigInt("30101073", 16)
      closureDispatchCsr(
        dut,
        BigInt("80005100", 16),
        MachineCsrAddress.Misa,
        CsrOp.Write,
        rd = 1,
        producesValue = true,
        rawInst = raw
      )
      closureAwaitCommit(dut)
      dut.io.commit.exception.expect(true.B)
      dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
      dut.io.commit.exceptionValue.expect(raw.U)
      dut.io.commit.rdWrite.expect(false.B)
      dut.io.privilegedRedirect.valid.expect(true.B)
    }
  }

  it should "retire EBREAK and predecoded exceptions as precise traps" in {
    simulate(new TinyPrivilegedBackend(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val ebreakPc = BigInt("80005200", 16)
      closureDispatch(dut) {
        closurePokeBase(
          dut,
          ebreakPc,
          ExecutionClass.System,
          rawInst = 0x00100073L,
          systemKind = SystemOperationKind.Ebreak
        )
      }
      closureAwaitCommit(dut)
      dut.io.commit.exception.expect(true.B)
      dut.io.commit.exceptionCause.expect(MachineExceptionCode.Breakpoint.U)
      dut.io.commit.exceptionValue.expect(ebreakPc.U)
      dut.io.privilegedRedirect.valid.expect(true.B)
      dut.clock.step()
    }

    simulate(new TinyPrivilegedBackend(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val pc = BigInt("80005300", 16)
      val trapValue = BigInt("deadbeef", 16)
      closureDispatch(dut) {
        closurePokeBase(dut, pc, ExecutionClass.Integer, rawInst = 0xffffffffL)
        dut.io.dispatch.bits.decoded.exception.valid.poke(true.B)
        dut.io.dispatch.bits.decoded.exception.cause.poke(MachineExceptionCode.IllegalInstruction.U)
        dut.io.dispatch.bits.decoded.exception.value.poke(trapValue.U)
      }
      closureAwaitCommit(dut)
      dut.io.commit.exception.expect(true.B)
      dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
      dut.io.commit.exceptionValue.expect(trapValue.U)
      dut.io.commit.rdWrite.expect(false.B)
      dut.io.privilegedRedirect.valid.expect(true.B)
    }
  }

  it should "reject MRET below M-mode" in {
    simulate(new TinyPrivilegedBackend(CoreProfiles.rv32imsuSoftware)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val pc = BigInt("80005400", 16)
      val userPc = BigInt("80006000", 16)

      closureDispatchConstant(dut, pc, 1, userPc)
      closureRetireRegister(dut, 1, userPc)
      closureWriteCsrFromRegister(dut, pc + 4, MachineCsrAddress.Mepc, 1)
      closureDispatchXret(dut, pc + 8, XRetOp.Machine, 0x30200073L)
      closureAwaitCommit(dut)
      dut.io.commit.exception.expect(false.B)
      dut.io.privilegedRedirect.valid.expect(true.B)
      dut.clock.step()
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)

      val illegalRaw = BigInt("30200073", 16)
      closureDispatchXret(dut, userPc, XRetOp.Machine, illegalRaw)
      closureAwaitCommit(dut)
      dut.io.commit.exception.expect(true.B)
      dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
      dut.io.commit.exceptionValue.expect(illegalRaw.U)
      dut.io.privilegedRedirect.valid.expect(true.B)
    }
  }

  it should "retire available FENCE operations as serialized cacheless no-ops" in {
    for ((config, kind, raw) <- Seq(
      (CoreProfiles.rv32imSoftware, SystemOperationKind.Fence, BigInt("0000000f", 16)),
      (CoreProfiles.rv64imCurrent, SystemOperationKind.FenceI, BigInt("0000100f", 16))
    )) {
      simulate(new TinyPrivilegedBackend(config)) { dut =>
        dut.io.dispatch.valid.poke(false.B)
        closureDispatch(dut) {
          closurePokeBase(
            dut,
            BigInt("80005500", 16),
            ExecutionClass.System,
            rawInst = raw,
            systemKind = kind
          )
        }
        closureAwaitCommit(dut)
        dut.io.commit.exception.expect(false.B)
        dut.io.commit.rdWrite.expect(false.B)
        dut.io.privilegedRedirect.valid.expect(false.B)
        dut.clock.step()
        dut.io.occupancy.expect(0.U)
      }
    }
  }

  it should "fail closed on unavailable or deferred system operations" in {
    closureExpectIllegalSystem(
      CoreProfiles.rv32imSoftware,
      SystemOperationKind.FenceI,
      BigInt("0000100f", 16),
      BigInt("80005540", 16)
    )
    closureExpectIllegalSystem(
      CoreProfiles.rv32imSoftware,
      SystemOperationKind.Wfi,
      BigInt("10500073", 16),
      BigInt("80005544", 16)
    )
    closureExpectIllegalSystem(
      CoreProfiles.rv32imsuSoftware,
      SystemOperationKind.SfenceVma,
      BigInt("12000073", 16),
      BigInt("80005548", 16)
    )
  }

  it should "source the architectural time CSR from the backend input" in {
    val config = CoreProfiles.rv32imasuSv32PmpSoftware
    simulate(new TinyPrivilegedBackend(config)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val timeValue = BigInt("1122334455667788", 16)
      dut.io.time.get.poke(timeValue.U)

      closureDispatchCsr(
        dut,
        BigInt("80005600", 16),
        Rv32SstcCsrAddress.Time,
        CsrOp.Set,
        rd = 9,
        producesValue = true
      )
      closureRetireRegister(dut, 9, BigInt("55667788", 16))
    }
  }
}
