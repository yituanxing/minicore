package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.{MachineExceptionCode, AluOp, AtomicOp}
import aethercore.config.CoreProfiles
import aethercore.core.semantic._

/** Qualified v2 and new ProductCore must consume the same ISA decoder owner.
  * These are direct semantic-port tests, without TinyRob or any backend.
  */
class SharedSemanticDecodeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Shared architectural decode"

  private def drive(dut: SemanticDecode, pc: BigInt, inst: BigInt,
                    raw: BigInt = -1, bytes: Int = 4,
                    fetchFault: Option[(Int, BigInt)] = None): Unit = {
    dut.io.pc.poke(pc.U)
    dut.io.inst.poke((inst & 0xffffffffL).U)
    dut.io.rawInst.poke(((if (raw < 0) inst else raw) & 0xffffffffL).U)
    dut.io.instBytes.poke(bytes.U)
    dut.io.fetchException.valid.poke(fetchFault.isDefined.B)
    dut.io.fetchException.cause.poke(fetchFault.map(_._1).getOrElse(0).U)
    dut.io.fetchException.value.poke(fetchFault.map(_._2).getOrElse(BigInt(0)).U)
    dut.clock.step()
  }

  it should "preserve register, compressed and atomic semantics without any v2 Uop" in {
    simulate(new SemanticDecode(CoreProfiles.rv64imasuSv39PmpSoftware.isa)) { dut =>
      val pc = BigInt("80001000", 16)
      drive(dut, pc, BigInt("fff08113", 16))
      dut.io.decoded.aluOp.expect(AluOp.Add)
      dut.io.decoded.lhsSource.expect(OperandSourceKind.Rs1)
      dut.io.decoded.rhsSource.expect(OperandSourceKind.Immediate)
      dut.io.decoded.immediate.expect(BigInt("ffffffffffffffff",16).U)
      dut.io.decoded.usesRs1.expect(true.B)
      dut.io.decoded.usesRs2.expect(false.B)
      dut.io.decoded.writesRd.expect(true.B)

      drive(dut, pc+2, BigInt("00108093",16), raw=BigInt("0085",16), bytes=2)
      dut.io.decoded.rawInst.expect("h0085".U)
      dut.io.decoded.instBytes.expect(2.U)

      drive(dut, pc+4, BigInt("0620a1af",16))
      dut.io.decoded.memory.kind.expect(MemoryOperationKind.Atomic)
      dut.io.decoded.memory.atomicOp.expect(AtomicOp.Add)
      dut.io.decoded.memory.acquire.expect(true.B)
      dut.io.decoded.memory.release.expect(true.B)
      dut.io.decoded.ordering.expect(OrderingClass.SerializeBoth)
    }
  }

  it should "keep VM fences and fetched page faults independent from the scheduler" in {
    simulate(new SemanticDecode(CoreProfiles.rv64imasuSv39PmpSoftware.isa)) { dut =>
      val pc = BigInt("80002000",16)
      drive(dut, pc, BigInt("12208073",16))
      dut.io.decoded.system.kind.expect(SystemOperationKind.SfenceVma)
      dut.io.decoded.ordering.expect(OrderingClass.TranslationFence)
      dut.io.decoded.usesRs1.expect(true.B)
      dut.io.decoded.usesRs2.expect(true.B)

      val mtval = BigInt("81234567",16)
      drive(dut, pc+4, BigInt("ffffffff",16),
        fetchFault=Some(MachineExceptionCode.InstructionPageFault -> mtval))
      dut.io.decoded.exception.valid.expect(true.B)
      dut.io.decoded.exception.cause.expect(MachineExceptionCode.InstructionPageFault.U)
      dut.io.decoded.exception.value.expect(mtval.U)
      dut.io.decoded.usesRs1.expect(false.B)
      dut.io.decoded.usesRs2.expect(false.B)
      dut.io.decoded.writesRd.expect(false.B)
    }
  }
}
