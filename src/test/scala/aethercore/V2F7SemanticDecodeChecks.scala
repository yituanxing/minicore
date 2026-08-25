package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common._
import aethercore.config.{CoreProfiles, IsaConfig}
import aethercore.core.v2._

/** Test-only composition that keeps the architectural and backend seams visible. */
private class TinyDecodeBoundaryHarness(val isa: IsaConfig) extends Module {
  private val xlen = isa.xlen

  val io = IO(new Bundle {
    val pc = Input(UInt(xlen.W))
    val inst = Input(UInt(32.W))
    val rawInst = Input(UInt(32.W))
    val instBytes = Input(UInt(3.W))
    val fetchException = Input(new TrapInfo(xlen))
    val decoded = Output(new DecodedInstruction(xlen))
    val dispatch = Output(new RobDispatch(xlen))
  })

  val decode = Module(new TinySemanticDecode(isa))
  val classify = Module(new TinyDispatchClassify(xlen))

  decode.io.pc := io.pc
  decode.io.inst := io.inst
  decode.io.rawInst := io.rawInst
  decode.io.instBytes := io.instBytes
  decode.io.fetchException := io.fetchException
  classify.io.decoded := decode.io.decoded

  io.decoded := decode.io.decoded
  io.dispatch := classify.io.dispatch
}

trait V2F7SemanticDecodeChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def drive(
      dut: TinyDecodeBoundaryHarness,
      pc: BigInt,
      inst: BigInt,
      rawInst: BigInt = -1,
      instBytes: Int = 4,
      fetchException: Option[(Int, BigInt)] = None
  ): Unit = {
    dut.io.pc.poke(pc.U)
    dut.io.inst.poke((inst & 0xffffffffL).U)
    dut.io.rawInst.poke(((if (rawInst < 0) inst else rawInst) & 0xffffffffL).U)
    dut.io.instBytes.poke(instBytes.U)
    fetchException match {
      case Some((cause, value)) =>
        dut.io.fetchException.valid.poke(true.B)
        dut.io.fetchException.cause.poke(cause.U)
        dut.io.fetchException.value.poke(value.U)
      case None =>
        dut.io.fetchException.valid.poke(false.B)
        dut.io.fetchException.cause.poke(0.U)
        dut.io.fetchException.value.poke(0.U)
    }
    dut.clock.step()
  }

  behavior of "AetherCore v2 F7 semantic decode boundary"

  it should "translate representative RV64 I/M/A/control/memory instructions into architectural then backend semantics" in {
    val config = CoreProfiles.rv64imasuSv39PmpSoftware
    simulate(new TinyDecodeBoundaryHarness(config.isa)) { dut =>
      val pc = BigInt("80001000", 16)

      // LUI x5, 0x12345: architectural Zero + Immediate, not a v1 bypass selector.
      drive(dut, pc, BigInt("123452b7", 16))
      dut.io.decoded.lhsSource.expect(OperandSourceKind.Zero)
      dut.io.decoded.rhsSource.expect(OperandSourceKind.Immediate)
      dut.io.decoded.immediate.expect(BigInt("12345000", 16).U)
      dut.io.decoded.rd.expect(5.U)
      dut.io.decoded.writesRd.expect(true.B)
      dut.io.dispatch.executionClass.expect(ExecutionClass.Integer)
      dut.io.dispatch.producesValue.expect(true.B)

      // AUIPC x6, 0x1: Pc + Immediate.
      drive(dut, pc + 4, BigInt("00001317", 16))
      dut.io.decoded.lhsSource.expect(OperandSourceKind.Pc)
      dut.io.decoded.rhsSource.expect(OperandSourceKind.Immediate)
      dut.io.decoded.immediate.expect(BigInt("1000", 16).U)

      // ADDI x2, x1, -1.
      drive(dut, pc + 8, BigInt("fff08113", 16))
      dut.io.decoded.usesRs1.expect(true.B)
      dut.io.decoded.usesRs2.expect(false.B)
      dut.io.decoded.lhsSource.expect(OperandSourceKind.Rs1)
      dut.io.decoded.rhsSource.expect(OperandSourceKind.Immediate)
      dut.io.decoded.immediate.expect(BigInt("ffffffffffffffff", 16).U)

      // ADD x3, x1, x2.
      drive(dut, pc + 12, BigInt("002081b3", 16))
      dut.io.decoded.usesRs1.expect(true.B)
      dut.io.decoded.usesRs2.expect(true.B)
      dut.io.decoded.rhsSource.expect(OperandSourceKind.Rs2)
      dut.io.dispatch.executionClass.expect(ExecutionClass.Integer)

      // MUL and DIV are backend MulDiv work derived only from decoded semantics.
      drive(dut, pc + 16, BigInt("022081b3", 16))
      dut.io.decoded.aluOp.expect(AluOp.Mul)
      dut.io.dispatch.executionClass.expect(ExecutionClass.MulDiv)
      drive(dut, pc + 20, BigInt("0220c1b3", 16))
      dut.io.decoded.aluOp.expect(AluOp.Div)
      dut.io.dispatch.executionClass.expect(ExecutionClass.MulDiv)

      // BEQ x1,x2,+8.
      drive(dut, pc + 24, BigInt("00208463", 16))
      dut.io.decoded.controlFlow.kind.expect(ControlFlowKind.Conditional)
      dut.io.decoded.controlFlow.branchType.expect(BranchType.Eq)
      dut.io.decoded.immediate.expect(8.U)
      dut.io.dispatch.executionClass.expect(ExecutionClass.Branch)

      // JAL x1,+8 and JALR x1,0(x2).
      drive(dut, pc + 28, BigInt("008000ef", 16))
      dut.io.decoded.controlFlow.kind.expect(ControlFlowKind.DirectJump)
      dut.io.decoded.instBytes.expect(4.U)
      drive(dut, pc + 32, BigInt("000100e7", 16))
      dut.io.decoded.controlFlow.kind.expect(ControlFlowKind.IndirectJump)
      dut.io.decoded.usesRs1.expect(true.B)

      // LW x3,4(x1).
      drive(dut, pc + 36, BigInt("0040a183", 16))
      dut.io.decoded.memory.kind.expect(MemoryOperationKind.Load)
      dut.io.decoded.memory.size.expect(MemSize.Word)
      dut.io.decoded.memory.unsigned.expect(false.B)
      dut.io.decoded.immediate.expect(4.U)
      dut.io.dispatch.executionClass.expect(ExecutionClass.Memory)
      dut.io.dispatch.producesValue.expect(true.B)

      // SW x2,8(x1).
      drive(dut, pc + 40, BigInt("0020a423", 16))
      dut.io.decoded.memory.kind.expect(MemoryOperationKind.Store)
      dut.io.decoded.usesRs1.expect(true.B)
      dut.io.decoded.usesRs2.expect(true.B)
      dut.io.decoded.immediate.expect(8.U)
      dut.io.dispatch.executionClass.expect(ExecutionClass.Memory)
      dut.io.dispatch.producesValue.expect(false.B)

      // AMOADD.W x3,x2,(x1), aqrl: ordering annotation survives semantic decode.
      drive(dut, pc + 44, BigInt("0620a1af", 16))
      dut.io.decoded.memory.kind.expect(MemoryOperationKind.Atomic)
      dut.io.decoded.memory.atomicOp.expect(AtomicOp.Add)
      dut.io.decoded.memory.acquire.expect(true.B)
      dut.io.decoded.memory.release.expect(true.B)
      dut.io.decoded.ordering.expect(OrderingClass.SerializeBoth)
      dut.io.dispatch.executionClass.expect(ExecutionClass.Memory)
    }
  }

  it should "preserve system, CSR, fence and exception semantics without backend opcode re-decode" in {
    val config = CoreProfiles.rv64imsuSv39PmpSoftware
    simulate(new TinyDecodeBoundaryHarness(config.isa)) { dut =>
      val pc = BigInt("80002000", 16)

      // CSRRWI x5,mstatus,3: zimm is semantic and rs1 is not a dependency.
      drive(dut, pc, BigInt("3001d2f3", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.Csr)
      dut.io.decoded.system.csrOp.expect(CsrOp.Write)
      dut.io.decoded.system.csrAddress.expect("h300".U)
      dut.io.decoded.system.csrUseImmediate.expect(true.B)
      dut.io.decoded.system.csrImmediate.expect(3.U)
      dut.io.decoded.usesRs1.expect(false.B)
      dut.io.dispatch.executionClass.expect(ExecutionClass.System)

      drive(dut, pc + 4, BigInt("00000073", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.Ecall)
      drive(dut, pc + 8, BigInt("00100073", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.Ebreak)
      drive(dut, pc + 12, BigInt("10500073", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.Wfi)
      drive(dut, pc + 16, BigInt("30200073", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.Xret)
      dut.io.decoded.system.xret.expect(XRetOp.Machine)
      drive(dut, pc + 20, BigInt("10200073", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.Xret)
      dut.io.decoded.system.xret.expect(XRetOp.Supervisor)

      // SFENCE.VMA x1,x2 is recognized as a translation fence even though the
      // legacy Decoder intentionally leaves it to the VM/privileged layer. Its
      // rs1/rs2 operand dependencies remain explicit even while F6 currently
      // implements the conservative whole-TLB flush form.
      drive(dut, pc + 24, BigInt("12208073", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.SfenceVma)
      dut.io.decoded.ordering.expect(OrderingClass.TranslationFence)
      dut.io.decoded.rs1.expect(1.U)
      dut.io.decoded.rs2.expect(2.U)
      dut.io.decoded.usesRs1.expect(true.B)
      dut.io.decoded.usesRs2.expect(true.B)
      dut.io.decoded.exception.valid.expect(false.B)
      dut.io.dispatch.executionClass.expect(ExecutionClass.System)

      drive(dut, pc + 28, BigInt("0000000f", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.Fence)
      dut.io.decoded.ordering.expect(OrderingClass.MemoryFence)

      // This RV64 production profile lacks Zifencei, so FENCE.I is a precise
      // illegal-instruction fact rather than an unsupported backend hang.
      drive(dut, pc + 32, BigInt("0000100f", 16))
      dut.io.decoded.exception.valid.expect(true.B)
      dut.io.decoded.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      dut.io.dispatch.producesValue.expect(false.B)

      // Fetch-side architectural fault wins over decoder illegality.
      val faultValue = BigInt("81234567", 16)
      drive(
        dut,
        pc + 36,
        BigInt("ffffffff", 16),
        fetchException = Some(MachineExceptionCode.InstructionPageFault -> faultValue)
      )
      dut.io.decoded.exception.valid.expect(true.B)
      dut.io.decoded.exception.cause.expect(MachineExceptionCode.InstructionPageFault.U)
      dut.io.decoded.exception.value.expect(faultValue.U)
      dut.io.decoded.usesRs1.expect(false.B)
      dut.io.decoded.usesRs2.expect(false.B)
      dut.io.decoded.writesRd.expect(false.B)
      dut.io.dispatch.producesValue.expect(false.B)
    }
  }

  it should "preserve RV32 Zifencei and compressed-length facts across the same boundary" in {
    val config = CoreProfiles.rv32imasuSv32PmpSoftware
    simulate(new TinyDecodeBoundaryHarness(config.isa)) { dut =>
      val pc = BigInt("80003000", 16)

      drive(dut, pc, BigInt("0000100f", 16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.FenceI)
      dut.io.decoded.exception.valid.expect(false.B)
      dut.io.dispatch.executionClass.expect(ExecutionClass.System)

      // Canonical ADDI supplied by the compressed parcel controller; raw bits
      // and pc+2 architectural length remain independent semantic facts.
      drive(
        dut,
        pc + 2,
        BigInt("00108093", 16),
        rawInst = BigInt("0085", 16),
        instBytes = 2
      )
      dut.io.decoded.rawInst.expect(BigInt("0085", 16).U)
      dut.io.decoded.instBytes.expect(2.U)
      dut.io.decoded.immediate.expect(1.U)
      dut.io.dispatch.executionClass.expect(ExecutionClass.Integer)

      drive(dut, pc + 4, BigInt("ffffffff", 16))
      dut.io.decoded.exception.valid.expect(true.B)
      dut.io.decoded.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      dut.io.decoded.exception.value.expect(BigInt("ffffffff", 16).U)
    }
  }
}
