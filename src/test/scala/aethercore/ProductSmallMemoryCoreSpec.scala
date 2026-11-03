package aethercore

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.product._
import aethercore.core.semantic.SemanticDecode
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}

private class PhysicalProductHarness extends Module {
  private val config = ProductCoreProfiles.smallEngineering
  private val I = log2Ceil(config.geometry.robEntries)
  private val P = log2Ceil(config.geometry.integerPhysicalRegisters)
  private val T = log2Ceil(config.geometry.loadQueueEntries + config.geometry.storeQueueEntries)
  private val PA = config.platform.paddrBits
  val io = IO(new Bundle {
    val pc = Input(UInt(64.W))
    val inst = Input(UInt(32.W))
    val valid = Input(Bool())
    val ready = Output(Bool())
    val execute = Decoupled(new ProductIssue(64, I, P, 16))
    val executeAvailable = Input(Bool())
    val resolved = Flipped(Decoupled(new ProductResolvedMem(PA, I, 16)))
    val physicalRequest = Decoupled(new AetherMemRequest(PA, 64, T))
    val physicalResponse = Flipped(Decoupled(new AetherMemResponse(64, T)))
    val retirement = Decoupled(new ProductRetirement(64, I, P, 16))
    val trap = Valid(new ProductRetirement(64, I, P, 16))
    val occupancy = Output(UInt(log2Ceil(config.geometry.robEntries + 1).W))
  })
  private val decoder = Module(new SemanticDecode(config.software.isa))
  decoder.io.pc := io.pc
  decoder.io.inst := io.inst
  decoder.io.rawInst := io.inst
  decoder.io.instBytes := 4.U
  decoder.io.fetchException := 0.U.asTypeOf(decoder.io.fetchException)

  private val core = Module(new ProductSmallMemoryCore(config))
  core.io.dispatch.valid := io.valid
  core.io.dispatch.bits.decoded := decoder.io.decoded
  core.io.dispatch.bits.predictedNextPc := io.pc + 4.U
  io.ready := core.io.dispatch.ready
  io.execute.valid := core.io.memoryExecute.valid
  io.execute.bits := core.io.memoryExecute.bits
  core.io.memoryExecute.ready := io.execute.ready
  core.io.memoryResourceAvailable := io.executeAvailable
  core.io.memoryResolved.valid := io.resolved.valid
  core.io.memoryResolved.bits := io.resolved.bits
  io.resolved.ready := core.io.memoryResolved.ready
  io.physicalRequest.valid := core.io.memoryRequest.valid
  io.physicalRequest.bits := core.io.memoryRequest.bits
  core.io.memoryRequest.ready := io.physicalRequest.ready
  core.io.memoryResponse.valid := io.physicalResponse.valid
  core.io.memoryResponse.bits := io.physicalResponse.bits
  io.physicalResponse.ready := core.io.memoryResponse.ready
  io.retirement.valid := core.io.retire.valid
  io.retirement.bits := core.io.retire.bits
  io.trap := core.io.trap
  core.io.retire.ready := io.retirement.ready
  io.occupancy := core.io.occupancy
}

class ProductSmallMemoryCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product SmallOoO decoded memory -> LSQ -> AetherMem -> precise ROB retirement"

  private def init(d: PhysicalProductHarness): Unit = {
    d.io.valid.poke(false.B)
    d.io.pc.poke("h80000000".U)
    d.io.inst.poke("h00000013".U)
    d.io.execute.ready.poke(true.B)
    d.io.executeAvailable.poke(true.B)
    d.io.resolved.valid.poke(false.B)
    d.io.resolved.bits.poke(0.U.asTypeOf(d.io.resolved.bits))
    d.io.physicalRequest.ready.poke(true.B)
    d.io.physicalResponse.valid.poke(false.B)
    d.io.physicalResponse.bits.poke(0.U.asTypeOf(d.io.physicalResponse.bits))
    d.io.retirement.ready.poke(true.B)
    d.clock.step()
  }

  private def decode(d: PhysicalProductHarness, pc: BigInt, inst: BigInt): Unit = {
    d.io.pc.poke(pc.U)
    d.io.inst.poke(inst.U)
    d.io.valid.poke(true.B)
    d.io.ready.expect(true.B)
    d.clock.step()
    d.io.valid.poke(false.B)
  }

  private def resolve(d: PhysicalProductHarness, index: BigInt, gen: BigInt,
                      address: BigInt, storeData: BigInt = 0,
                      virtualAddress: BigInt = 0): Unit = {
    d.io.resolved.bits.token.index.poke(index.U)
    d.io.resolved.bits.token.generation.poke(gen.U)
    d.io.resolved.bits.virtualAddress.poke(virtualAddress.U)
    d.io.resolved.bits.paddr.poke(address.U)
    d.io.resolved.bits.storeData.poke(storeData.U)
    d.io.resolved.bits.attributes.cacheable.poke(true.B)
    d.io.resolved.bits.attributes.idempotent.poke(true.B)
    d.io.resolved.bits.attributes.sideEffecting.poke(false.B)
    d.io.resolved.bits.attributes.ordered.poke(false.B)
    d.io.resolved.bits.attributes.executable.poke(true.B)
    d.io.resolved.bits.attributes.supportsAtomic.poke(true.B)
    d.io.resolved.bits.attributes.supportsPartial.poke(true.B)
    d.io.resolved.bits.fault.valid.poke(false.B)
    d.io.resolved.bits.fault.cause.poke(0.U)
    d.io.resolved.bits.fault.value.poke(0.U)
    d.io.resolved.valid.poke(true.B)
    d.io.resolved.ready.expect(true.B)
    d.clock.step()
    d.io.resolved.valid.poke(false.B)
  }

  private def physicalResponse(d: PhysicalProductHarness, txn: BigInt, data: BigInt,
                               fault: Boolean = false): Unit = {
    d.io.physicalResponse.bits.txnId.poke(txn.U)
    d.io.physicalResponse.bits.rdata.poke(data.U)
    d.io.physicalResponse.bits.fault.poke(fault.B)
    d.io.physicalResponse.bits.last.poke(true.B)
    d.io.physicalResponse.valid.poke(true.B)
    d.io.physicalResponse.ready.expect(true.B)
    d.clock.step()
    d.io.physicalResponse.valid.poke(false.B)
  }

  it should "execute a decoded LW across the physical resolver and commit its sign-extended data" in {
    simulate(new PhysicalProductHarness) { d =>
      init(d)
      decode(d, BigInt("80000000", 16), BigInt("00002183", 16)) // lw x3,0(x0)
      // Translation capacity is a separate FU resource. It may block issue
      // even when the Decoupled consumer has ready asserted.
      d.io.executeAvailable.poke(false.B)
      d.io.execute.valid.expect(false.B)
      d.clock.step()
      d.io.executeAvailable.poke(true.B)
      d.io.execute.valid.expect(true.B)
      d.io.execute.bits.memoryKind.expect(aethercore.core.semantic.MemoryOperationKind.Load)
      val idx = d.io.execute.bits.token.index.peek().litValue
      val gen = d.io.execute.bits.token.generation.peek().litValue
      d.io.execute.ready.poke(false.B)
      for (_ <- 0 until 2) {
        d.io.execute.valid.expect(true.B) // valid independent of downstream ready
        d.io.execute.bits.token.index.expect(idx.U)
        d.clock.step()
      }
      d.io.execute.ready.poke(true.B)
      d.clock.step()
      resolve(d, idx, gen, BigInt("80001000", 16))
      d.io.physicalRequest.valid.expect(true.B)
      d.io.physicalRequest.bits.op.expect(AetherMemOp.Read)
      val tx = d.io.physicalRequest.bits.txnId.peek().litValue
      d.clock.step()
      physicalResponse(d, tx, BigInt("ffffff80", 16))
      var values = List.empty[(BigInt, BigInt)]
      for (_ <- 0 until 15) {
        if (d.io.retirement.valid.peek().litToBoolean)
          values :+= (d.io.retirement.bits.rd.peek().litValue,
            d.io.retirement.bits.value.peek().litValue)
        d.clock.step()
      }
      values shouldBe List((BigInt(3), BigInt("ffffffffffffff80", 16)))
      d.io.occupancy.expect(0.U)
    }
  }

  it should "run ADDI -> SW and externalize Store only at exact ROB head" in {
    simulate(new PhysicalProductHarness) { d =>
      init(d)
      decode(d, BigInt("80000000",16), BigInt("00500093",16)) // addi x1,x0,5
      var addiRetired = false
      for (_ <- 0 until 12) {
        if (d.io.retirement.valid.peek().litToBoolean) {
          d.io.retirement.bits.value.expect(5.U)
          addiRetired = true
        }
        d.clock.step()
      }
      addiRetired shouldBe true
      decode(d, BigInt("80000004",16), BigInt("00102023",16)) // sw x1,0(x0)
      d.io.execute.valid.expect(true.B)
      d.io.execute.bits.storeData.expect(5.U)
      val idx = d.io.execute.bits.token.index.peek().litValue
      val gen = d.io.execute.bits.token.generation.peek().litValue
      d.clock.step()
      resolve(d, idx, gen, BigInt("80001000",16), storeData = 5)
      d.io.physicalRequest.valid.expect(true.B)
      d.io.physicalRequest.bits.op.expect(AetherMemOp.Write)
      d.io.physicalRequest.bits.wdata.expect(5.U)
      d.io.physicalRequest.bits.wmask.expect(15.U)
      val tx = d.io.physicalRequest.bits.txnId.peek().litValue
      d.clock.step()
      physicalResponse(d, tx, 0)
      var storeRetired = false
      for (_ <- 0 until 15) {
        if (d.io.retirement.valid.peek().litToBoolean) {
          d.io.retirement.bits.wroteRegister.expect(false.B)
          storeRetired = true
        }
        d.clock.step()
      }
      storeRetired shouldBe true
      d.io.occupancy.expect(0.U)
    }
  }
  it should "turn a physical Load access fault into a precise ROB trap, without PRF write" in {
    simulate(new PhysicalProductHarness) { d =>
      init(d)
      decode(d, BigInt("80000000", 16), BigInt("00002183", 16)) // lw x3,0(x0)
      d.io.execute.valid.expect(true.B)
      val idx = d.io.execute.bits.token.index.peek().litValue
      val gen = d.io.execute.bits.token.generation.peek().litValue
      d.clock.step()
      resolve(d, idx, gen, BigInt("80005000", 16))
      d.io.physicalRequest.valid.expect(true.B)
      val tx = d.io.physicalRequest.bits.txnId.peek().litValue
      d.clock.step()
      physicalResponse(d, tx, 0, fault = true)
      var faulted = false
      for (_ <- 0 until 25) {
        if (d.io.retirement.valid.peek().litToBoolean) {
          d.io.retirement.bits.exception.valid.expect(true.B)
          d.io.retirement.bits.wroteRegister.expect(false.B)
          d.io.retirement.bits.exception.cause.expect(
            aethercore.common.MachineExceptionCode.LoadAccessFault.U)
          d.io.retirement.bits.exception.value.expect(0.U)
          d.io.trap.valid.expect(true.B)
          faulted = true
        }
        d.clock.step()
      }
      faulted shouldBe true
      d.io.occupancy.expect(0.U)
    }
  }

  it should "trap an older faulting Store without retiring a completed younger Load" in {
    simulate(new PhysicalProductHarness) { d =>
      init(d)
      decode(d, BigInt("80000000", 16), BigInt("00002023", 16)) // sw x0,0(x0)
      d.io.execute.valid.expect(true.B)
      val storeIdx = d.io.execute.bits.token.index.peek().litValue
      val storeGen = d.io.execute.bits.token.generation.peek().litValue
      d.clock.step()
      resolve(d, storeIdx, storeGen, BigInt("80001000", 16))
      d.io.physicalRequest.valid.expect(true.B)
      d.io.physicalRequest.bits.op.expect(AetherMemOp.Write)
      val storeTx = d.io.physicalRequest.bits.txnId.peek().litValue
      d.clock.step()

      decode(d, BigInt("80000004", 16), BigInt("00002183", 16)) // lw x3,0(x0)
      d.io.execute.valid.expect(true.B)
      val loadIdx = d.io.execute.bits.token.index.peek().litValue
      val loadGen = d.io.execute.bits.token.generation.peek().litValue
      d.clock.step()
      resolve(d, loadIdx, loadGen, BigInt("80002000", 16))
      d.io.physicalRequest.valid.expect(true.B)
      d.io.physicalRequest.bits.op.expect(AetherMemOp.Read)
      val loadTx = d.io.physicalRequest.bits.txnId.peek().litValue
      loadTx should not be storeTx
      d.clock.step()

      physicalResponse(d, loadTx, 42)
      for (_ <- 0 until 5) {
        d.io.retirement.valid.expect(false.B)
        d.clock.step()
      }
      physicalResponse(d, storeTx, 0, fault = true)
      var storeTrapSeen = false
      var youngerRetired = false
      for (_ <- 0 until 40) {
        if (d.io.retirement.valid.peek().litToBoolean) {
          if (d.io.retirement.bits.exception.valid.peek().litToBoolean) {
            d.io.retirement.bits.token.index.expect(storeIdx.U)
            d.io.retirement.bits.exception.cause.expect(
              aethercore.common.MachineExceptionCode.StoreAccessFault.U)
            d.io.retirement.bits.exception.value.expect(0.U)
            d.io.trap.valid.expect(true.B)
            storeTrapSeen = true
          } else {
            youngerRetired = true
          }
        }
        d.clock.step()
      }
      storeTrapSeen shouldBe true
      youngerRetired shouldBe false
      d.io.occupancy.expect(0.U)
    }
  }

}
