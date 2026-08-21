package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, BranchType, MemSize}
import aethercore.core.v2._
import aethercore.memory._

private class V2IdentityHarness extends Module {
  val io = IO(new Bundle {
    val tokenIn = Input(new RobToken(3, 2))
    val tokenOut = Output(new RobToken(3, 2))
    val producerIn = Input(new ProducerTag(3, 2))
    val producerOut = Output(new ProducerTag(3, 2))
    val valueRefIn = Input(new ValueRef(3, 2))
    val valueRefOut = Output(new ValueRef(3, 2))
  })

  io.tokenOut := io.tokenIn
  io.producerOut := io.producerIn
  io.valueRefOut := io.valueRefIn
}

private class V2ExecutionHarness(val xlen: Int) extends Module {
  val io = IO(new Bundle {
    val requestIn = Input(new ExecutionRequest(xlen, 3, 2))
    val requestOut = Output(new ExecutionRequest(xlen, 3, 2))
    val responseIn = Input(new ExecutionResponse(xlen, 3, 2))
    val responseOut = Output(new ExecutionResponse(xlen, 3, 2))
    val decodedIn = Input(new DecodedInstruction(xlen))
    val decodedOut = Output(new DecodedInstruction(xlen))
    val uopIn = Input(new BackendUop(xlen, 3, 2))
    val uopOut = Output(new BackendUop(xlen, 3, 2))
  })

  io.requestOut := io.requestIn
  io.responseOut := io.responseIn
  io.decodedOut := io.decodedIn
  io.uopOut := io.uopIn
}

private class AetherMemLinkHarness extends Module {
  val io = IO(new Bundle {
    val requestIn = Input(new AetherMemRequest(addrBits = 56, dataBits = 64, txnIdBits = 2))
    val requestOut = Output(new AetherMemRequest(addrBits = 56, dataBits = 64, txnIdBits = 2))
    val responseIn = Input(new AetherMemResponse(dataBits = 64, txnIdBits = 2))
    val responseOut = Output(new AetherMemResponse(dataBits = 64, txnIdBits = 2))
  })

  io.requestOut := io.requestIn
  io.responseOut := io.responseIn
}

class V2FoundationTypesSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore v2 foundation contracts"

  it should "keep order, dependency and value identities type-distinct while permitting the same first implementation bits" in {
    simulate(new V2IdentityHarness) { dut =>
      dut.io.tokenIn.index.poke(5.U)
      dut.io.tokenIn.generation.poke(2.U)
      dut.io.producerIn.id.poke(5.U)
      dut.io.producerIn.generation.poke(2.U)
      dut.io.valueRefIn.id.poke(5.U)
      dut.io.valueRefIn.generation.poke(2.U)

      dut.io.tokenOut.index.expect(5.U)
      dut.io.tokenOut.generation.expect(2.U)
      dut.io.producerOut.id.expect(5.U)
      dut.io.producerOut.generation.expect(2.U)
      dut.io.valueRefOut.id.expect(5.U)
      dut.io.valueRefOut.generation.expect(2.U)
    }
  }

  it should "separate architectural decode semantics from backend execution classification" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new V2ExecutionHarness(xlen)) { dut =>
        val wordOperation = xlen == 64
        val instructionBytes = if (xlen == 32) 2 else 4

        dut.io.uopIn.executionClass.poke(ExecutionClass.Integer)
        dut.io.uopIn.robToken.index.poke(3.U)
        dut.io.uopIn.robToken.generation.poke(1.U)
        dut.io.uopIn.producerTag.id.poke(3.U)
        dut.io.uopIn.producerTag.generation.poke(1.U)
        dut.io.uopIn.valueRef.id.poke(6.U)
        dut.io.uopIn.valueRef.generation.poke(2.U)
        dut.io.uopIn.producesValue.poke(true.B)

        dut.io.uopIn.decoded.pc.poke("h80000000".U)
        dut.io.uopIn.decoded.inst.poke("h002081b3".U)
        dut.io.uopIn.decoded.rawInst.poke("h002081b3".U)
        dut.io.uopIn.decoded.instBytes.poke(instructionBytes.U)
        dut.io.uopIn.decoded.aluOp.poke(AluOp.Add)
        dut.io.uopIn.decoded.wordOp.poke(wordOperation.B)
        dut.io.uopIn.decoded.lhsSource.poke(OperandSourceKind.Rs1)
        dut.io.uopIn.decoded.rhsSource.poke(OperandSourceKind.Rs2)
        dut.io.uopIn.decoded.rs1.poke(1.U)
        dut.io.uopIn.decoded.rs2.poke(2.U)
        dut.io.uopIn.decoded.rd.poke(3.U)
        dut.io.uopIn.decoded.usesRs1.poke(true.B)
        dut.io.uopIn.decoded.usesRs2.poke(true.B)
        dut.io.uopIn.decoded.writesRd.poke(true.B)
        dut.io.uopIn.decoded.immediate.poke(0.U)
        dut.io.uopIn.decoded.controlFlow.kind.poke(ControlFlowKind.None)
        dut.io.uopIn.decoded.controlFlow.branchType.poke(BranchType.None)
        dut.io.uopIn.decoded.memory.kind.poke(MemoryOperationKind.None)
        dut.io.uopIn.decoded.memory.size.poke(MemSize.Word)
        dut.io.uopIn.decoded.memory.unsigned.poke(false.B)
        dut.io.uopIn.decoded.memory.atomicOp.poke(aethercore.common.AtomicOp.None)
        dut.io.uopIn.decoded.memory.acquire.poke(false.B)
        dut.io.uopIn.decoded.memory.release.poke(false.B)
        dut.io.uopIn.decoded.system.kind.poke(SystemOperationKind.None)
        dut.io.uopIn.decoded.system.csrOp.poke(aethercore.common.CsrOp.None)
        dut.io.uopIn.decoded.system.csrAddress.poke(0.U)
        dut.io.uopIn.decoded.system.csrUseImmediate.poke(false.B)
        dut.io.uopIn.decoded.system.csrImmediate.poke(0.U)
        dut.io.uopIn.decoded.system.xret.poke(aethercore.common.XRetOp.None)
        dut.io.uopIn.decoded.ordering.poke(OrderingClass.Normal)
        dut.io.uopIn.decoded.exception.valid.poke(false.B)
        dut.io.uopIn.decoded.exception.cause.poke(0.U)
        dut.io.uopIn.decoded.exception.value.poke(0.U)

        dut.io.uopOut.executionClass.expect(ExecutionClass.Integer)
        dut.io.uopOut.robToken.index.expect(3.U)
        dut.io.uopOut.valueRef.id.expect(6.U)
        dut.io.uopOut.decoded.aluOp.expect(AluOp.Add)
        dut.io.uopOut.decoded.wordOp.expect(wordOperation.B)
        dut.io.uopOut.decoded.lhsSource.expect(OperandSourceKind.Rs1)
        dut.io.uopOut.decoded.rhsSource.expect(OperandSourceKind.Rs2)
        dut.io.uopOut.decoded.instBytes.expect(instructionBytes.U)
        dut.io.uopOut.decoded.rs1.expect(1.U)
        dut.io.uopOut.decoded.rs2.expect(2.U)
        dut.io.uopOut.decoded.rd.expect(3.U)

        dut.io.requestIn.robToken.index.poke(3.U)
        dut.io.requestIn.robToken.generation.poke(1.U)
        dut.io.requestIn.producerTag.id.poke(4.U)
        dut.io.requestIn.producerTag.generation.poke(2.U)
        dut.io.requestIn.valueRef.id.poke(6.U)
        dut.io.requestIn.valueRef.generation.poke(3.U)
        dut.io.requestIn.executionClass.poke(ExecutionClass.Integer)
        dut.io.requestIn.aluOp.poke(AluOp.Add)
        dut.io.requestIn.wordOp.poke(wordOperation.B)
        dut.io.requestIn.controlFlowKind.poke(ControlFlowKind.None)
        dut.io.requestIn.branchType.poke(BranchType.None)
        dut.io.requestIn.lhs.poke(7.U)
        dut.io.requestIn.rhs.poke(9.U)
        dut.io.requestIn.pc.poke("h80000000".U)
        dut.io.requestIn.instBytes.poke(instructionBytes.U)
        dut.io.requestIn.immediate.poke(0.U)

        dut.io.requestOut.robToken.index.expect(3.U)
        dut.io.requestOut.producerTag.id.expect(4.U)
        dut.io.requestOut.valueRef.id.expect(6.U)
        dut.io.requestOut.valueRef.generation.expect(3.U)
        dut.io.requestOut.executionClass.expect(ExecutionClass.Integer)
        dut.io.requestOut.aluOp.expect(AluOp.Add)
        dut.io.requestOut.wordOp.expect(wordOperation.B)
        dut.io.requestOut.lhs.expect(7.U)
        dut.io.requestOut.rhs.expect(9.U)
        dut.io.requestOut.instBytes.expect(instructionBytes.U)

        dut.io.responseIn.robToken.index.poke(3.U)
        dut.io.responseIn.robToken.generation.poke(1.U)
        dut.io.responseIn.producerTag.id.poke(4.U)
        dut.io.responseIn.producerTag.generation.poke(2.U)
        dut.io.responseIn.valueRef.id.poke(6.U)
        dut.io.responseIn.valueRef.generation.poke(3.U)
        dut.io.responseIn.hasValue.poke(true.B)
        dut.io.responseIn.value.poke(16.U)
        dut.io.responseIn.branchValid.poke(false.B)
        dut.io.responseIn.branchTaken.poke(false.B)
        dut.io.responseIn.branchTarget.poke(0.U)
        dut.io.responseIn.exception.valid.poke(false.B)
        dut.io.responseIn.exception.cause.poke(0.U)
        dut.io.responseIn.exception.value.poke(0.U)

        dut.io.responseOut.robToken.index.expect(3.U)
        dut.io.responseOut.producerTag.id.expect(4.U)
        dut.io.responseOut.valueRef.id.expect(6.U)
        dut.io.responseOut.valueRef.generation.expect(3.U)
        dut.io.responseOut.hasValue.expect(true.B)
        dut.io.responseOut.value.expect(16.U)

        dut.io.decodedIn.pc.poke("h80000000".U)
        dut.io.decodedIn.inst.poke("h002081b3".U)
        dut.io.decodedIn.rawInst.poke("h002081b3".U)
        dut.io.decodedIn.instBytes.poke(instructionBytes.U)
        dut.io.decodedIn.aluOp.poke(AluOp.Add)
        dut.io.decodedIn.wordOp.poke(wordOperation.B)
        dut.io.decodedIn.lhsSource.poke(OperandSourceKind.Pc)
        dut.io.decodedIn.rhsSource.poke(OperandSourceKind.Immediate)
        dut.io.decodedIn.rs1.poke(1.U)
        dut.io.decodedIn.rs2.poke(2.U)
        dut.io.decodedIn.rd.poke(3.U)
        dut.io.decodedIn.usesRs1.poke(true.B)
        dut.io.decodedIn.usesRs2.poke(true.B)
        dut.io.decodedIn.writesRd.poke(true.B)
        dut.io.decodedIn.immediate.poke(0.U)
        dut.io.decodedIn.controlFlow.kind.poke(ControlFlowKind.None)
        dut.io.decodedIn.controlFlow.branchType.poke(BranchType.None)
        dut.io.decodedIn.memory.kind.poke(MemoryOperationKind.None)
        dut.io.decodedIn.memory.size.poke(MemSize.Word)
        dut.io.decodedIn.memory.unsigned.poke(false.B)
        dut.io.decodedIn.memory.atomicOp.poke(aethercore.common.AtomicOp.None)
        dut.io.decodedIn.memory.acquire.poke(false.B)
        dut.io.decodedIn.memory.release.poke(false.B)
        dut.io.decodedIn.system.kind.poke(SystemOperationKind.None)
        dut.io.decodedIn.system.csrOp.poke(aethercore.common.CsrOp.None)
        dut.io.decodedIn.system.csrAddress.poke(0.U)
        dut.io.decodedIn.system.csrUseImmediate.poke(true.B)
        dut.io.decodedIn.system.csrImmediate.poke(31.U)
        dut.io.decodedIn.system.xret.poke(aethercore.common.XRetOp.None)
        dut.io.decodedIn.ordering.poke(OrderingClass.Normal)
        dut.io.decodedIn.exception.valid.poke(false.B)
        dut.io.decodedIn.exception.cause.poke(0.U)
        dut.io.decodedIn.exception.value.poke(0.U)

        dut.io.decodedOut.pc.expect("h80000000".U)
        dut.io.decodedOut.wordOp.expect(wordOperation.B)
        dut.io.decodedOut.lhsSource.expect(OperandSourceKind.Pc)
        dut.io.decodedOut.rhsSource.expect(OperandSourceKind.Immediate)
        dut.io.decodedOut.system.csrUseImmediate.expect(true.B)
        dut.io.decodedOut.system.csrImmediate.expect(31.U)
        dut.io.decodedOut.rs1.expect(1.U)
        dut.io.decodedOut.rs2.expect(2.U)
        dut.io.decodedOut.rd.expect(3.U)
      }
    }
  }

  it should "preserve transaction identity across the internal memory seam" in {
    simulate(new AetherMemLinkHarness) { dut =>
      dut.io.requestIn.txnId.poke(2.U)
      dut.io.requestIn.op.poke(AetherMemOp.Read)
      dut.io.requestIn.paddr.poke("h12345678".U)
      dut.io.requestIn.size.poke(MemSize.DWord)
      dut.io.requestIn.wdata.poke(0.U)
      dut.io.requestIn.wmask.poke(0.U)
      dut.io.requestIn.atomicOp.poke(aethercore.common.AtomicOp.None)
      dut.io.requestIn.attributes.cacheable.poke(true.B)
      dut.io.requestIn.attributes.idempotent.poke(true.B)
      dut.io.requestIn.attributes.sideEffecting.poke(false.B)
      dut.io.requestIn.attributes.ordered.poke(false.B)
      dut.io.requestIn.attributes.executable.poke(false.B)
      dut.io.requestIn.attributes.supportsAtomic.poke(true.B)
      dut.io.requestIn.attributes.supportsPartial.poke(true.B)

      dut.io.requestOut.txnId.expect(2.U)
      dut.io.requestOut.op.expect(AetherMemOp.Read)
      dut.io.requestOut.paddr.expect("h12345678".U)
      dut.io.requestOut.attributes.cacheable.expect(true.B)

      dut.io.responseIn.txnId.poke(2.U)
      dut.io.responseIn.rdata.poke("h0123456789abcdef".U)
      dut.io.responseIn.fault.poke(false.B)
      dut.io.responseIn.last.poke(true.B)

      dut.io.responseOut.txnId.expect(2.U)
      dut.io.responseOut.rdata.expect("h0123456789abcdef".U)
      dut.io.responseOut.fault.expect(false.B)
      dut.io.responseOut.last.expect(true.B)
    }
  }
}
