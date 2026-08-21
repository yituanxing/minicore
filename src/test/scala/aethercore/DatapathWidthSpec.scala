package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, XRetOp}
import aethercore.core.{ALU, RegisterFile}
import aethercore.core.v2._
import aethercore.memory.{AetherMemOp, AetherMemRequest}

private class V2FoundationWidthSmoke(val xlen: Int) extends Module {
  private val paddrBits = if (xlen == 32) 34 else 56

  val io = IO(new Bundle {
    val uopIn = Input(new BackendUop(xlen, identityBits = 3, generationBits = 2))
    val uopOut = Output(new BackendUop(xlen, identityBits = 3, generationBits = 2))
    val memIn = Input(new AetherMemRequest(paddrBits, dataBits = xlen, txnIdBits = 2))
    val memOut = Output(new AetherMemRequest(paddrBits, dataBits = xlen, txnIdBits = 2))
  })

  io.uopOut := io.uopIn
  io.memOut := io.memIn
}

class DatapathWidthSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F1RobCommitChecks
    with V2F2DependencyChecks {
  behavior of "parameterized integer datapath components"

  private def pokeF1Dispatch(dut: TinyRobCommitBackend, pc: BigInt, rd: Int): Unit = {
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.bits.executionClass.poke(ExecutionClass.Integer)
    dut.io.dispatch.bits.producesValue.poke(true.B)
    dut.io.dispatch.bits.decoded.pc.poke(pc.U)
    dut.io.dispatch.bits.decoded.inst.poke("h002081b3".U)
    dut.io.dispatch.bits.decoded.rawInst.poke("h002081b3".U)
    dut.io.dispatch.bits.decoded.instBytes.poke(4.U)
    dut.io.dispatch.bits.decoded.aluOp.poke(AluOp.Add)
    dut.io.dispatch.bits.decoded.rs1.poke(1.U)
    dut.io.dispatch.bits.decoded.rs2.poke(2.U)
    dut.io.dispatch.bits.decoded.rd.poke(rd.U)
    dut.io.dispatch.bits.decoded.usesRs1.poke(true.B)
    dut.io.dispatch.bits.decoded.usesRs2.poke(true.B)
    dut.io.dispatch.bits.decoded.writesRd.poke(true.B)
    dut.io.dispatch.bits.decoded.immediate.poke(0.U)
    dut.io.dispatch.bits.decoded.controlFlow.kind.poke(ControlFlowKind.None)
    dut.io.dispatch.bits.decoded.controlFlow.branchType.poke(BranchType.None)
    dut.io.dispatch.bits.decoded.memory.kind.poke(MemoryOperationKind.None)
    dut.io.dispatch.bits.decoded.memory.size.poke(MemSize.Word)
    dut.io.dispatch.bits.decoded.memory.unsigned.poke(false.B)
    dut.io.dispatch.bits.decoded.memory.atomicOp.poke(AtomicOp.None)
    dut.io.dispatch.bits.decoded.memory.acquire.poke(false.B)
    dut.io.dispatch.bits.decoded.memory.release.poke(false.B)
    dut.io.dispatch.bits.decoded.system.kind.poke(SystemOperationKind.None)
    dut.io.dispatch.bits.decoded.system.csrOp.poke(CsrOp.None)
    dut.io.dispatch.bits.decoded.system.csrAddress.poke(0.U)
    dut.io.dispatch.bits.decoded.system.csrUseImmediate.poke(false.B)
    dut.io.dispatch.bits.decoded.system.xret.poke(XRetOp.None)
    dut.io.dispatch.bits.decoded.ordering.poke(OrderingClass.Normal)
    dut.io.dispatch.bits.decoded.exception.valid.poke(false.B)
    dut.io.dispatch.bits.decoded.exception.cause.poke(0.U)
    dut.io.dispatch.bits.decoded.exception.value.poke(0.U)
  }

  it should "execute RV32 I/M arithmetic with 32-bit wraparound" in {
    simulate(new ALU(32)) { dut =>
      dut.io.wordOp.poke(false.B)

      dut.io.a.poke("hffffffff".U)
      dut.io.b.poke(1.U)
      dut.io.op.poke(AluOp.Add)
      dut.io.out.expect(0.U)

      dut.io.a.poke("h80000000".U)
      dut.io.b.poke(1.U)
      dut.io.op.poke(AluOp.Sra)
      dut.io.out.expect("hc0000000".U)

      dut.io.a.poke("hfffffffe".U)
      dut.io.b.poke(3.U)
      dut.io.op.poke(AluOp.Mulh)
      dut.io.out.expect("hffffffff".U)

      dut.io.a.poke("h80000000".U)
      dut.io.b.poke("hffffffff".U)
      dut.io.op.poke(AluOp.Div)
      dut.io.out.expect("h80000000".U)
    }
  }

  it should "store, bypass and preserve x0 at XLEN 32" in {
    simulate(new RegisterFile(32)) { dut =>
      dut.io.rs1Addr.poke(0.U)
      dut.io.rs2Addr.poke(0.U)
      dut.io.writeEnable.poke(false.B)
      dut.io.rdAddr.poke(0.U)
      dut.io.rdData.poke(0.U)
      dut.clock.step()

      dut.io.writeEnable.poke(true.B)
      dut.io.rdAddr.poke(7.U)
      dut.io.rdData.poke("h89abcdef".U)
      dut.io.rs1Addr.poke(7.U)
      dut.io.rs1Data.expect("h89abcdef".U)
      dut.clock.step()

      dut.io.writeEnable.poke(false.B)
      dut.io.rs1Data.expect("h89abcdef".U)

      dut.io.writeEnable.poke(true.B)
      dut.io.rdAddr.poke(0.U)
      dut.io.rdData.poke("hffffffff".U)
      dut.io.rs2Addr.poke(0.U)
      dut.clock.step()
      dut.io.rs2Data.expect(0.U)
    }
  }

  it should "elaborate the v2 semantic, identity and memory contracts at both XLENs" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new V2FoundationWidthSmoke(xlen)) { dut =>
        dut.io.uopIn.executionClass.poke(ExecutionClass.Integer)
        dut.io.uopIn.robToken.index.poke(6.U)
        dut.io.uopIn.robToken.generation.poke(1.U)
        dut.io.uopIn.decoded.pc.poke("h80000000".U)

        dut.io.uopOut.executionClass.expect(ExecutionClass.Integer)
        dut.io.uopOut.robToken.index.expect(6.U)
        dut.io.uopOut.robToken.generation.expect(1.U)
        dut.io.uopOut.decoded.pc.expect("h80000000".U)

        dut.io.memIn.txnId.poke(2.U)
        dut.io.memIn.op.poke(AetherMemOp.Read)
        dut.io.memIn.paddr.poke("h1000".U)

        dut.io.memOut.txnId.expect(2.U)
        dut.io.memOut.op.expect(AetherMemOp.Read)
        dut.io.memOut.paddr.expect("h1000".U)
      }
    }
  }

  it should "allocate, complete and commit one v2 F1 instruction at both XLENs" in {
    for (xlen <- Seq(32, 64)) {
      simulate(new TinyRobCommitBackend(xlen)) { dut =>
        dut.io.rs1Addr.poke(7.U)
        dut.io.rs2Addr.poke(0.U)
        dut.io.completion.valid.poke(false.B)

        pokeF1Dispatch(dut, BigInt("80000000", 16), rd = 7)
        dut.io.dispatch.ready.expect(true.B)
        dut.io.allocated.valid.expect(true.B)
        val tokenIndex = dut.io.allocated.bits.robToken.index.peek().litValue
        val tokenGeneration = dut.io.allocated.bits.robToken.generation.peek().litValue
        val producerId = dut.io.allocated.bits.producerTag.id.peek().litValue
        val producerGeneration = dut.io.allocated.bits.producerTag.generation.peek().litValue
        val valueId = dut.io.allocated.bits.valueRef.id.peek().litValue
        val valueGeneration = dut.io.allocated.bits.valueRef.generation.peek().litValue
        dut.clock.step()

        dut.io.dispatch.valid.poke(false.B)
        dut.io.occupancy.expect(1.U)
        dut.io.commit.valid.expect(false.B)

        dut.io.completion.valid.poke(true.B)
        dut.io.completion.bits.robToken.index.poke(tokenIndex.U)
        dut.io.completion.bits.robToken.generation.poke(tokenGeneration.U)
        dut.io.completion.bits.producerTag.id.poke(producerId.U)
        dut.io.completion.bits.producerTag.generation.poke(producerGeneration.U)
        dut.io.completion.bits.valueRef.id.poke(valueId.U)
        dut.io.completion.bits.valueRef.generation.poke(valueGeneration.U)
        dut.io.completion.bits.hasValue.poke(true.B)
        dut.io.completion.bits.value.poke(42.U)
        dut.io.completion.bits.branchValid.poke(false.B)
        dut.io.completion.bits.branchTaken.poke(false.B)
        dut.io.completion.bits.branchTarget.poke(0.U)
        dut.io.completion.bits.exception.valid.poke(false.B)
        dut.io.completion.bits.exception.cause.poke(0.U)
        dut.io.completion.bits.exception.value.poke(0.U)
        dut.clock.step()

        dut.io.completion.valid.poke(false.B)
        dut.io.commit.valid.expect(true.B)
        dut.io.commit.pc.expect("h80000000".U)
        dut.io.commit.rd.expect(7.U)
        dut.io.commit.rdWrite.expect(true.B)
        dut.io.commit.rdData.expect(42.U)
        dut.io.rs1Data.expect(42.U)
        dut.clock.step()

        dut.io.commit.valid.expect(false.B)
        dut.io.occupancy.expect(0.U)
        dut.io.rs1Data.expect(42.U)
      }
    }
  }
}
