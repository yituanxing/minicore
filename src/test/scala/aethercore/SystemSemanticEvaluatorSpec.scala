package aethercore

import aethercore.common.{CsrOp, MachineExceptionCode, PrivilegeMode, XRetOp}
import aethercore.config.CoreProfiles
import aethercore.core.semantic.{SystemOperationKind, SystemSemanticEvaluator}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Same architectural evaluator used by Tiny's completion wrapper and Product. */
class SystemSemanticEvaluatorSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Shared head-only SYSTEM semantics"

  private val isa = CoreProfiles.rv64imsuSv39PmpSoftware.isa

  private def init(d: SystemSemanticEvaluator): Unit = {
    d.io.decoded.poke(0.U.asTypeOf(d.io.decoded))
    d.io.producesValue.poke(false.B)
    d.io.rs1Value.poke(0.U)
    d.io.csrReadData.poke(0.U)
    d.io.csrReadImplemented.poke(true.B)
    d.io.csrReadWritable.poke(true.B)
    d.io.currentPrivilege.poke(PrivilegeMode.Machine.U)
  }

  it should "calculate CSR old value and pending write without mutating a CSR owner" in {
    simulate(new SystemSemanticEvaluator(isa)) { d =>
      init(d)
      d.io.decoded.system.kind.poke(SystemOperationKind.Csr)
      d.io.decoded.system.csrOp.poke(CsrOp.Set)
      d.io.decoded.system.csrAddress.poke("h300".U)
      d.io.decoded.rs1.poke(2.U)
      d.io.decoded.rawInst.poke("h300120f3".U)
      d.io.producesValue.poke(true.B)
      d.io.rs1Value.poke("ha".U)
      d.io.csrReadData.poke("h50".U)

      d.io.csrReadAddr.expect("h300".U)
      d.io.result.hasValue.expect(true.B)
      d.io.result.value.expect("h50".U)
      d.io.result.exception.valid.expect(false.B)
      d.io.result.privileged.csrWriteValid.expect(true.B)
      d.io.result.privileged.csrAddress.expect("h300".U)
      d.io.result.privileged.csrWriteData.expect("h5a".U)

      // CSRRS rs1=x0 is a read-only access even for a writable CSR.
      d.io.decoded.rs1.poke(0.U)
      d.io.rs1Value.poke(0.U)
      d.io.result.hasValue.expect(true.B)
      d.io.result.privileged.csrWriteValid.expect(false.B)

      // A real write to read-only CSR must trap with original instruction.
      d.io.decoded.rs1.poke(2.U)
      d.io.csrReadWritable.poke(false.B)
      d.io.result.exception.valid.expect(true.B)
      d.io.result.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      d.io.result.exception.value.expect("h300120f3".U)
      d.io.result.privileged.csrWriteValid.expect(false.B)
    }
  }

  it should "prioritize a predecoded fetch fault over illegal SYSTEM semantics" in {
    simulate(new SystemSemanticEvaluator(isa)) { d =>
      init(d)
      d.io.decoded.system.kind.poke(SystemOperationKind.None)
      d.io.decoded.rawInst.poke("hffffffff".U)
      d.io.decoded.exception.valid.poke(true.B)
      d.io.decoded.exception.cause.poke(MachineExceptionCode.InstructionAccessFault.U)
      d.io.decoded.exception.value.poke("h80000002".U)
      d.io.result.exception.valid.expect(true.B)
      d.io.result.exception.cause.expect(MachineExceptionCode.InstructionAccessFault.U)
      d.io.result.exception.value.expect("h80000002".U)
      d.io.result.privileged.trapReturn.expect(false.B)
    }
  }

  it should "compute M/S ECALL cause and validate xRET without side effects" in {
    simulate(new SystemSemanticEvaluator(isa)) { d =>
      init(d)
      d.io.decoded.system.kind.poke(SystemOperationKind.Ecall)
      d.io.currentPrivilege.poke(PrivilegeMode.Supervisor.U)
      d.io.result.exception.cause.expect(MachineExceptionCode.EnvironmentCallFromS.U)
      d.io.currentPrivilege.poke(PrivilegeMode.User.U)
      d.io.result.exception.cause.expect(MachineExceptionCode.EnvironmentCallFromU.U)
      d.io.currentPrivilege.poke(PrivilegeMode.Machine.U)
      d.io.result.exception.cause.expect(MachineExceptionCode.EnvironmentCallFromM.U)

      d.io.decoded.system.kind.poke(SystemOperationKind.Xret)
      d.io.decoded.system.xret.poke(XRetOp.Machine)
      d.io.result.exception.valid.expect(false.B)
      d.io.result.privileged.trapReturn.expect(true.B)
      d.io.result.privileged.trapReturnSupervisor.expect(false.B)
      d.io.currentPrivilege.poke(PrivilegeMode.Supervisor.U)
      d.io.result.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      d.io.result.privileged.trapReturn.expect(false.B)
      d.io.decoded.system.xret.poke(XRetOp.Supervisor)
      d.io.result.exception.valid.expect(false.B)
      d.io.result.privileged.trapReturn.expect(true.B)
      d.io.result.privileged.trapReturnSupervisor.expect(true.B)
    }
  }

  it should "emit legal SFENCE and WFI effects only with their privilege gates" in {
    simulate(new SystemSemanticEvaluator(isa, allowSfenceVma = true, allowWfi = true)) { d =>
      init(d)
      d.io.decoded.system.kind.poke(SystemOperationKind.SfenceVma)
      d.io.currentPrivilege.poke(PrivilegeMode.Supervisor.U)
      d.io.result.exception.valid.expect(false.B)
      d.io.result.privileged.sfenceVma.expect(true.B)
      d.io.currentPrivilege.poke(PrivilegeMode.User.U)
      d.io.result.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      d.io.result.privileged.sfenceVma.expect(false.B)

      d.io.decoded.system.kind.poke(SystemOperationKind.Wfi)
      d.io.result.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      d.io.currentPrivilege.poke(PrivilegeMode.Machine.U)
      d.io.result.exception.valid.expect(false.B)
      d.io.result.privileged.wfi.expect(true.B)
    }
  }
}
