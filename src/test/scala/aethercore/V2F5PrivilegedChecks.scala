package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MachineExceptionCode, MemSize, PrivilegeMode, XRetOp}
import aethercore.config.{CoreConfig, CoreProfiles}
import aethercore.core.{MachineCsrAddress, SupervisorCsrAddress}
import aethercore.core.v2._

trait V2F5PrivilegedChecks extends V2F5PrivilegedClosureChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pokeDispatchBase(
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
      xret: XRetOp.Type = XRetOp.None,
      controlFlowKind: ControlFlowKind.Type = ControlFlowKind.None
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
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(controlFlowKind)
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

  private def dispatch(dut: TinyPrivilegedBackend)(poke: => Unit): Unit = {
    poke
    dut.io.dispatch.ready.expect(true.B)
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  private def dispatchConstant(dut: TinyPrivilegedBackend, pc: BigInt, rd: Int, value: BigInt): Unit =
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

  private def dispatchCsr(
      dut: TinyPrivilegedBackend,
      pc: BigInt,
      csrAddress: Int,
      csrOp: CsrOp.Type,
      rd: Int = 0,
      rs1: Int = 0,
      usesRs1: Boolean = false,
      producesValue: Boolean = false,
      rawInst: BigInt = 0x00001073L
  ): Unit =
    dispatch(dut) {
      pokeDispatchBase(
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
        csrOp = csrOp,
        csrAddress = csrAddress
      )
    }

  private def dispatchXret(
      dut: TinyPrivilegedBackend,
      pc: BigInt,
      op: XRetOp.Type,
      rawInst: BigInt
  ): Unit =
    dispatch(dut) {
      pokeDispatchBase(
        dut,
        pc,
        ExecutionClass.System,
        rawInst = rawInst,
        systemKind = SystemOperationKind.Xret,
        xret = op
      )
    }

  private def dispatchEcall(dut: TinyPrivilegedBackend, pc: BigInt): Unit =
    dispatch(dut) {
      pokeDispatchBase(
        dut,
        pc,
        ExecutionClass.System,
        rawInst = 0x00000073L,
        systemKind = SystemOperationKind.Ecall
      )
    }

  private def awaitCommit(dut: TinyPrivilegedBackend, maxCycles: Int = 96): Unit = {
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"commit did not arrive within $maxCycles cycles") {
      dut.io.commit.valid.peek().litToBoolean shouldBe true
    }
  }

  private def retireExpectedRegister(
      dut: TinyPrivilegedBackend,
      rd: Int,
      value: BigInt
  ): Unit = {
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.io.commit.rdWrite.expect(true.B)
    dut.io.commit.rd.expect(rd.U)
    dut.io.commit.rdData.expect(value.U)
    dut.clock.step()
  }

  private def writeCsrFromRegister(
      dut: TinyPrivilegedBackend,
      pc: BigInt,
      address: Int,
      rs1: Int
  ): Unit = {
    dispatchCsr(
      dut,
      pc,
      address,
      CsrOp.Write,
      rs1 = rs1,
      usesRs1 = true,
      producesValue = false
    )
    awaitCommit(dut)
    dut.io.commit.exception.expect(false.B)
    dut.io.commit.rdWrite.expect(false.B)
    dut.io.privilegedRedirect.valid.expect(false.B)
    dut.clock.step()
  }

  behavior of "AetherCore v2 F5 precise privileged retirement"

  it should "commit CSR state before a precise trap and return through MRET at both XLENs" in {
    for (config <- Seq(CoreProfiles.rv32imSoftware, CoreProfiles.rv64imCurrent)) {
      simulate(new TinyPrivilegedBackend(config)) { dut =>
        dut.io.dispatch.valid.poke(false.B)
        val mtvecRequested = BigInt("103", 16)
        val mtvecCanonical = BigInt("100", 16)
        val basePc = BigInt("80001000", 16)
        val faultPc = basePc + 0x40
        val illegalRaw = BigInt("7ff01073", 16)

        dispatchConstant(dut, basePc, rd = 1, mtvecRequested)
        retireExpectedRegister(dut, rd = 1, mtvecRequested)

        // CSRRW returns the old CSR value but only mutates mtvec at retirement.
        dispatchCsr(
          dut,
          basePc + 4,
          MachineCsrAddress.Mtvec,
          CsrOp.Write,
          rd = 4,
          rs1 = 1,
          usesRs1 = true,
          producesValue = true
        )
        awaitCommit(dut)
        dut.io.commit.exception.expect(false.B)
        dut.io.commit.rdWrite.expect(true.B)
        dut.io.commit.rd.expect(4.U)
        dut.io.commit.rdData.expect(0.U)
        dut.io.privilegedRedirect.valid.expect(false.B)
        dut.clock.step()

        // CSRRS x5, mtvec, x0 is a pure read. Observe the WARL-canonical value
        // after the prior write has retired.
        dispatchCsr(
          dut,
          basePc + 8,
          MachineCsrAddress.Mtvec,
          CsrOp.Set,
          rd = 5,
          rs1 = 0,
          usesRs1 = false,
          producesValue = true
        )
        retireExpectedRegister(dut, rd = 5, mtvecCanonical)

        dispatchCsr(
          dut,
          faultPc,
          csrAddress = 0x7ff,
          csrOp = CsrOp.Write,
          rd = 6,
          rs1 = 0,
          usesRs1 = false,
          producesValue = true,
          rawInst = illegalRaw
        )

        // System completion is side-effect free. One cycle later the validated
        // exception completion has made the head retireable and squashed all
        // speculative state.
        dut.clock.step()
        dut.io.commit.valid.expect(true.B)
        dut.io.commit.exception.expect(true.B)
        dut.io.commit.rdWrite.expect(false.B)
        dut.io.commit.exceptionCause.expect(MachineExceptionCode.IllegalInstruction.U)
        dut.io.commit.exceptionValue.expect(illegalRaw.U)
        dut.io.privilegedRedirect.valid.expect(true.B)
        dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Trap)
        dut.io.privilegedRedirect.bits.target.expect(mtvecCanonical.U)
        dut.io.dispatch.ready.expect(false.B)
        dut.clock.step()

        // Trap entry saved faultPc into mepc. MRET is validated as a system
        // completion first and mutates mstatus/privilege only on retirement.
        dispatchXret(dut, mtvecCanonical, XRetOp.Machine, 0x30200073L)
        dut.clock.step()
        dut.io.commit.valid.expect(true.B)
        dut.io.commit.exception.expect(false.B)
        dut.io.privilegedRedirect.valid.expect(true.B)
        dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Return)
        dut.io.privilegedRedirect.bits.target.expect(faultPc.U)
        dut.clock.step()
        dut.io.occupancy.expect(0.U)
      }
    }
  }

  it should "delegate a U-mode ECALL to S-mode and return with SRET" in {
    val config: CoreConfig = CoreProfiles.rv32imsuSoftware
    simulate(new TinyPrivilegedBackend(config)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val pc = BigInt("80002000", 16)
      val stvec = BigInt("80000100", 16)
      val userPc = BigInt("80003000", 16)

      // stvec <- 0x80000100
      dispatchConstant(dut, pc, rd = 1, stvec)
      retireExpectedRegister(dut, 1, stvec)
      writeCsrFromRegister(dut, pc + 4, SupervisorCsrAddress.Stvec, rs1 = 1)

      // medeleg <- (1 << EnvironmentCallFromU)
      val ecallDelegate = BigInt(1) << MachineExceptionCode.EnvironmentCallFromU
      dispatchConstant(dut, pc + 8, rd = 1, ecallDelegate)
      retireExpectedRegister(dut, 1, ecallDelegate)
      writeCsrFromRegister(dut, pc + 12, MachineCsrAddress.Medeleg, rs1 = 1)

      // mepc <- userPc; reset MPP is U for this profile, so MRET enters U-mode.
      dispatchConstant(dut, pc + 16, rd = 1, userPc)
      retireExpectedRegister(dut, 1, userPc)
      writeCsrFromRegister(dut, pc + 20, MachineCsrAddress.Mepc, rs1 = 1)

      dispatchXret(dut, pc + 24, XRetOp.Machine, 0x30200073L)
      dut.clock.step()
      dut.io.privilegedRedirect.valid.expect(true.B)
      dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Return)
      dut.io.privilegedRedirect.bits.target.expect(userPc.U)
      dut.clock.step()
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)

      dispatchEcall(dut, userPc)
      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.exception.expect(true.B)
      dut.io.commit.exceptionCause.expect(MachineExceptionCode.EnvironmentCallFromU.U)
      dut.io.privilegedRedirect.valid.expect(true.B)
      dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Trap)
      dut.io.privilegedRedirect.bits.target.expect(stvec.U)
      dut.clock.step()
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)

      dispatchXret(dut, stvec, XRetOp.Supervisor, 0x10200073L)
      dut.clock.step()
      dut.io.privilegedRedirect.valid.expect(true.B)
      dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Return)
      dut.io.privilegedRedirect.bits.target.expect(userPc.U)
      dut.clock.step()
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)
    }
  }

  it should "route an exceptional branch through privileged recovery, never normal branch recovery" in {
    simulate(new TinyPrivilegedBackend(CoreProfiles.rv32imSoftware)) { dut =>
      dut.io.dispatch.valid.poke(false.B)
      val pc = BigInt("80004000", 16)

      dispatch(dut) {
        pokeDispatchBase(
          dut,
          pc,
          ExecutionClass.Branch,
          rd = 1,
          writesRd = true,
          producesValue = true,
          immediate = 2,
          rawInst = 0x002000efL,
          controlFlowKind = ControlFlowKind.DirectJump
        )
      }

      // Younger work is allocated while the one-cycle branch unit is active.
      dispatch(dut) {
        pokeDispatchBase(
          dut,
          pc + 4,
          ExecutionClass.Integer,
          rd = 7,
          writesRd = true,
          producesValue = true,
          immediate = 77
        )
      }

      // Misaligned taken target creates an exception. It must not produce the
      // F4 normal redirect; the ROB instead squashes younger state and leaves
      // the exceptional head for precise retirement.
      dut.io.branchRedirect.valid.expect(false.B)
      dut.clock.step()
      dut.io.occupancy.expect(1.U)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.exception.expect(true.B)
      dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAddressMisaligned.U)
      dut.io.privilegedRedirect.valid.expect(true.B)
      dut.io.privilegedRedirect.bits.kind.expect(PrivilegedRedirectKind.Trap)
      dut.io.branchRedirect.valid.expect(false.B)
      dut.clock.step()

      var sawKilled = false
      for (_ <- 0 until 8) {
        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.rd.peek().litValue == 7) {
          sawKilled = true
        }
        dut.clock.step()
      }
      sawKilled shouldBe false
    }
  }
}
