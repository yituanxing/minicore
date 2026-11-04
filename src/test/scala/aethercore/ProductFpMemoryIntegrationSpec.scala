package aethercore

import aethercore.common.PrivilegeMode
import aethercore.core.product._
import aethercore.core.semantic.{MemoryOperationKind, SemanticDecode}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}
import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Isolated F/D memory-core qualification. The full paged product still
  * refuses F/D until the IEEE arithmetic and privileged paths are qualified.
  * This harness deliberately preserves the real external translation and
  * physical memory interfaces instead of inventing an identity mapping.
  */
private class ProductFpMemoryHarness extends Module {
  private val config = ProductCoreProfiles.smallDebianTarget
  private val g = config.geometry
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val Tx = log2Ceil(g.loadQueueEntries + g.storeQueueEntries)
  private val PA = config.platform.paddrBits

  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val instructionValid = Input(Bool())
    val instructionReady = Output(Bool())
    val issue = Decoupled(new ProductIssue(64, I, P, 16))
    val resolved = Flipped(Decoupled(new ProductResolvedMem(PA, I, 16)))
    val physical = Decoupled(new AetherMemRequest(PA, 64, Tx))
    val physicalResponse = Flipped(Decoupled(new AetherMemResponse(64, Tx)))
    val retirement = Decoupled(new ProductRetirement(64, I, P, 16, PA))
  })

  private val decode = Module(new SemanticDecode(config.software.isa))
  decode.io.pc := "h80000000".U
  decode.io.inst := io.instruction
  decode.io.rawInst := io.instruction
  decode.io.instBytes := 4.U
  decode.io.fetchException := 0.U.asTypeOf(decode.io.fetchException)

  private val core = Module(new ProductSmallMemoryCore(config))
  core.io.dispatch.valid := io.instructionValid
  core.io.dispatch.bits.decoded := decode.io.decoded
  core.io.dispatch.bits.predictedNextPc := "h80000004".U
  io.instructionReady := core.io.dispatch.ready
  core.io.memoryResourceAvailable := true.B
  io.issue.valid := core.io.memoryExecute.valid
  io.issue.bits := core.io.memoryExecute.bits
  core.io.memoryExecute.ready := io.issue.ready
  core.io.memoryResolved.valid := io.resolved.valid
  core.io.memoryResolved.bits := io.resolved.bits
  io.resolved.ready := core.io.memoryResolved.ready
  io.physical.valid := core.io.memoryRequest.valid
  io.physical.bits := core.io.memoryRequest.bits
  core.io.memoryRequest.ready := io.physical.ready
  core.io.memoryResponse.valid := io.physicalResponse.valid
  core.io.memoryResponse.bits := io.physicalResponse.bits
  io.physicalResponse.ready := core.io.memoryResponse.ready
  io.retirement.valid := core.io.retire.valid
  io.retirement.bits := core.io.retire.bits
  core.io.retire.ready := io.retirement.ready
  core.io.systemCsrReadData := 0.U
  core.io.systemCsrReadImplemented := false.B
  core.io.systemCsrReadWritable := false.B
  core.io.systemCurrentPrivilege := PrivilegeMode.Machine.U
}

class ProductFpMemoryIntegrationSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Small optional floating execution and shared physical memory ownership"

  private def init(d: ProductFpMemoryHarness): Unit = {
    d.io.instructionValid.poke(false.B)
    d.io.instruction.poke("h00000013".U)
    d.io.issue.ready.poke(true.B)
    d.io.resolved.valid.poke(false.B)
    d.io.resolved.bits.poke(0.U.asTypeOf(d.io.resolved.bits))
    d.io.physical.ready.poke(false.B)
    d.io.physicalResponse.valid.poke(false.B)
    d.io.physicalResponse.bits.poke(0.U.asTypeOf(d.io.physicalResponse.bits))
    d.io.retirement.ready.poke(false.B)
    d.clock.step()
  }

  private def dispatch(d: ProductFpMemoryHarness, instruction: BigInt): Unit = {
    d.io.instruction.poke(instruction.U)
    d.io.instructionValid.poke(true.B)
    d.io.instructionReady.expect(true.B)
    d.clock.step()
    d.io.instructionValid.poke(false.B)
  }

  private def issueMem(d: ProductFpMemoryHarness, kind: MemoryOperationKind.Type):
      (BigInt, BigInt) = {
    d.io.issue.valid.expect(true.B)
    d.io.issue.bits.memoryKind.expect(kind)
    val index = d.io.issue.bits.token.index.peek().litValue
    val generation = d.io.issue.bits.token.generation.peek().litValue
    d.clock.step()
    (index, generation)
  }

  private def resolve(d: ProductFpMemoryHarness, index: BigInt, generation: BigInt,
                      virtualAddress: BigInt, physicalAddress: BigInt,
                      storeData: BigInt = 0): Unit = {
    val r = d.io.resolved.bits
    r.poke(0.U.asTypeOf(r))
    r.token.index.poke(index.U)
    r.token.generation.poke(generation.U)
    r.virtualAddress.poke(virtualAddress.U)
    r.paddr.poke(physicalAddress.U)
    r.storeData.poke(storeData.U)
    r.attributes.cacheable.poke(true.B)
    r.attributes.idempotent.poke(true.B)
    r.attributes.supportsPartial.poke(true.B)
    d.io.resolved.valid.poke(true.B)
    d.io.resolved.ready.expect(true.B)
    d.clock.step()
    d.io.resolved.valid.poke(false.B)
  }

  private def transact(d: ProductFpMemoryHarness, operation: AetherMemOp.Type,
                       responseData: BigInt): Unit = {
    d.io.physical.valid.expect(true.B)
    d.io.physical.bits.op.expect(operation)
    val txn = d.io.physical.bits.txnId.peek().litValue
    d.io.physical.ready.poke(true.B)
    d.clock.step()
    d.io.physical.ready.poke(false.B)
    d.io.physicalResponse.bits.poke(0.U.asTypeOf(d.io.physicalResponse.bits))
    d.io.physicalResponse.bits.txnId.poke(txn.U)
    d.io.physicalResponse.bits.rdata.poke(responseData.U)
    d.io.physicalResponse.bits.last.poke(true.B)
    d.io.physicalResponse.valid.poke(true.B)
    d.io.physicalResponse.ready.expect(true.B)
    d.clock.step()
    d.io.physicalResponse.valid.poke(false.B)
  }

  private def waitRetirement(d: ProductFpMemoryHarness): Unit = {
    var tries = 0
    while (tries < 25 && !d.io.retirement.valid.peek().litToBoolean) {
      d.clock.step()
      tries += 1
    }
    d.io.retirement.valid.expect(true.B)
  }

  private def retire(d: ProductFpMemoryHarness): Unit = {
    d.io.retirement.valid.expect(true.B)
    d.io.retirement.ready.poke(true.B)
    d.clock.step()
    d.io.retirement.ready.poke(false.B)
  }

  it should "load FLW into writable f0 with NaN boxing and store the raw lower word" in {
    simulate(new ProductFpMemoryHarness) { d =>
      init(d)
      dispatch(d, BigInt("00002007", 16)) // flw f0,0(x0)
      d.io.issue.bits.lhs.expect(0.U)
      d.io.issue.bits.immediate.expect(0.U)
      val (index, generation) = issueMem(d, MemoryOperationKind.Load)
      resolve(d, index, generation, 0, BigInt("80001000", 16))
      transact(d, AetherMemOp.Read, BigInt("40400000", 16))
      waitRetirement(d)
      d.io.retirement.bits.rd.expect(0.U)
      d.io.retirement.bits.wroteRegister.expect(false.B)
      d.io.retirement.bits.wroteFloatingRegister.expect(true.B)
      d.io.retirement.bits.destinationFile.expect(ProductRegisterFile.Floating)
      d.io.retirement.bits.value.expect("hffffffff40400000".U)
      retire(d)

      dispatch(d, BigInt("00002227", 16)) // fsw f0,4(x0)
      d.io.issue.valid.expect(true.B)
      d.io.issue.bits.lhs.expect(0.U)
      d.io.issue.bits.immediate.expect(4.U)
      d.io.issue.bits.storeData.expect("hffffffff40400000".U)
      val (storeIndex, storeGeneration) = issueMem(d, MemoryOperationKind.Store)
      resolve(d, storeIndex, storeGeneration, 4, BigInt("80001004", 16),
        BigInt("ffffffff40400000", 16))
      d.io.physical.valid.expect(true.B)
      d.io.physical.bits.wmask.expect("hf".U)
      d.io.physical.bits.wdata.expect("hffffffff40400000".U)
      transact(d, AetherMemOp.Write, 0)
      waitRetirement(d)
      d.io.retirement.bits.wroteFloatingRegister.expect(false.B)
      retire(d)
    }
  }

  it should "run FLD through the FP sign unit and FSD without truncating 64-bit data" in {
    simulate(new ProductFpMemoryHarness) { d =>
      init(d)
      dispatch(d, BigInt("00003007", 16)) // fld f0,0(x0)
      val (index, generation) = issueMem(d, MemoryOperationKind.Load)
      resolve(d, index, generation, 0, BigInt("80002000", 16))
      transact(d, AetherMemOp.Read, BigInt("3ff0000000000000", 16))
      waitRetirement(d)
      d.io.retirement.bits.wroteFloatingRegister.expect(true.B)
      d.io.retirement.bits.value.expect("h3ff0000000000000".U)
      retire(d)

      dispatch(d, BigInt("220000d3", 16)) // fsgnj.d f1,f0,f0
      waitRetirement(d)
      d.io.retirement.bits.rd.expect(1.U)
      d.io.retirement.bits.value.expect("h3ff0000000000000".U)
      retire(d)

      dispatch(d, BigInt("00103427", 16)) // fsd f1,8(x0)
      d.io.issue.valid.expect(true.B)
      d.io.issue.bits.immediate.expect(8.U)
      d.io.issue.bits.storeData.expect("h3ff0000000000000".U)
      val (storeIndex, storeGeneration) = issueMem(d, MemoryOperationKind.Store)
      resolve(d, storeIndex, storeGeneration, 8, BigInt("80002008", 16),
        BigInt("3ff0000000000000", 16))
      d.io.physical.bits.wmask.expect("hff".U)
      d.io.physical.bits.wdata.expect("h3ff0000000000000".U)
      transact(d, AetherMemOp.Write, 0)
      waitRetirement(d)
      retire(d)
    }
  }
}
