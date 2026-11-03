package aethercore

import aethercore.common.{MachineExceptionCode, MemSize, PageTableReadBusIO, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.PmpConstants
import aethercore.core.product._
import aethercore.core.semantic.SemanticDecode
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}
import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Only source-level semantic decode is outside the product core under test.
  * The DUT owns the entire integer/ROB -> shared Sv39 resolver -> LSQ ->
  * physical AetherMem request/response -> precise retirement path.
  */
private class TranslatedProductHarness extends Module {
  private val config = ProductCoreProfiles.smallEngineering
  private val g = config.geometry
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val T = log2Ceil(g.loadQueueEntries + g.storeQueueEntries)
  private val PA = config.platform.paddrBits
  private val Sv39 = PageTableGeometry.Sv39

  val io = IO(new Bundle {
    val pc = Input(UInt(64.W))
    val inst = Input(UInt(32.W))
    val valid = Input(Bool())
    val ready = Output(Bool())
    val context = Input(new ProductDataTranslationContext(Sv39))
    val pmpConfig = Input(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Input(Vec(PmpConstants.MaxEntries,
      UInt(aethercore.core.PmpGeometry(64, PA).encodedAddressBits.W)))
    val translationFence = Input(Bool())
    val pte = new PageTableReadBusIO(PA, Sv39.pteBits)
    val pmaValid = Output(Bool())
    val pmaAddress = Output(UInt(PA.W))
    val physicalRequest = Decoupled(new AetherMemRequest(PA, 64, T))
    val physicalResponse = Flipped(Decoupled(new AetherMemResponse(64, T)))
    val retirement = Decoupled(new ProductRetirement(64, I, P, 16))
    val trap = Valid(new ProductRetirement(64, I, P, 16))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
    val loadCount = Output(UInt(log2Ceil(g.loadQueueEntries + 1).W))
    val storeCount = Output(UInt(log2Ceil(g.storeQueueEntries + 1).W))
    val outstanding = Output(UInt(log2Ceil(g.loadQueueEntries + g.storeQueueEntries + 1).W))
    val resolverPending = Output(UInt(log2Ceil(g.loadQueueEntries + g.storeQueueEntries + 1).W))
  })

  private val decoder = Module(new SemanticDecode(config.software.isa))
  decoder.io.pc := io.pc
  decoder.io.inst := io.inst
  decoder.io.rawInst := io.inst
  decoder.io.instBytes := 4.U
  decoder.io.fetchException := 0.U.asTypeOf(decoder.io.fetchException)

  private val core = Module(new ProductSmallTranslatedCore(config))
  core.io.dispatch.valid := io.valid
  core.io.dispatch.bits.decoded := decoder.io.decoded
  core.io.dispatch.bits.predictedNextPc := io.pc + 4.U
  io.ready := core.io.dispatch.ready
  core.io.context := io.context
  core.io.pmpConfig := io.pmpConfig
  core.io.pmpAddress := io.pmpAddress
  core.io.translationFence := io.translationFence

  io.pte.valid := core.io.pte.valid
  io.pte.addr := core.io.pte.addr
  core.io.pte.ready := io.pte.ready
  core.io.pte.rdata := io.pte.rdata
  core.io.pte.fault := io.pte.fault
  io.pmaValid := core.io.pmaValid
  io.pmaAddress := core.io.pmaAddress
  core.io.pmaAttributes.cacheable := true.B
  core.io.pmaAttributes.idempotent := true.B
  core.io.pmaAttributes.sideEffecting := false.B
  core.io.pmaAttributes.ordered := false.B
  core.io.pmaAttributes.executable := true.B
  core.io.pmaAttributes.supportsAtomic := true.B
  core.io.pmaAttributes.supportsPartial := true.B

  io.physicalRequest <> core.io.memoryRequest
  core.io.memoryResponse <> io.physicalResponse
  io.retirement <> core.io.retire
  io.trap := core.io.trap
  io.occupancy := core.io.occupancy
  io.loadCount := core.io.loadCount
  io.storeCount := core.io.storeCount
  io.outstanding := core.io.outstandingTransactions
  io.resolverPending := core.io.resolverPending
}

class ProductSmallTranslatedCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product SmallOoO end-to-end translated memory core"

  private def init(d: TranslatedProductHarness): Unit = {
    d.io.pc.poke("h80000000".U)
    d.io.inst.poke("h00000013".U)
    d.io.valid.poke(false.B)
    d.io.context.effectivePrivilege.poke(PrivilegeMode.Machine.U)
    d.io.context.satpEnabled.poke(false.B)
    d.io.context.rootPpn.poke(1.U)
    d.io.context.sum.poke(false.B)
    d.io.context.mxr.poke(false.B)
    d.io.pmpConfig.poke(0.U.asTypeOf(d.io.pmpConfig))
    d.io.pmpAddress.poke(0.U.asTypeOf(d.io.pmpAddress))
    d.io.translationFence.poke(false.B)
    d.io.pte.ready.poke(false.B)
    d.io.pte.rdata.poke(0.U)
    d.io.pte.fault.poke(false.B)
    d.io.physicalRequest.ready.poke(true.B)
    d.io.physicalResponse.valid.poke(false.B)
    d.io.physicalResponse.bits.poke(0.U.asTypeOf(d.io.physicalResponse.bits))
    d.io.retirement.ready.poke(true.B)
    d.clock.step()
  }

  private def dispatch(d: TranslatedProductHarness, pc: BigInt, inst: BigInt): Unit = {
    d.io.pc.poke(pc.U)
    d.io.inst.poke(inst.U)
    d.io.valid.poke(true.B)
    d.io.ready.expect(true.B)
    d.clock.step()
    d.io.valid.poke(false.B)
  }

  private def awaitRequest(d: TranslatedProductHarness, cycles: Int = 70): Unit = {
    for (_ <- 0 until cycles) {
      if (!d.io.physicalRequest.valid.peek().litToBoolean) d.clock.step()
    }
    d.io.physicalRequest.valid.expect(true.B)
  }

  private def response(d: TranslatedProductHarness, txn: BigInt,
                       data: BigInt, fault: Boolean = false): Unit = {
    d.io.physicalResponse.bits.txnId.poke(txn.U)
    d.io.physicalResponse.bits.rdata.poke(data.U)
    d.io.physicalResponse.bits.fault.poke(fault.B)
    d.io.physicalResponse.bits.last.poke(true.B)
    d.io.physicalResponse.valid.poke(true.B)
    d.io.physicalResponse.ready.expect(true.B)
    d.clock.step()
    d.io.physicalResponse.valid.poke(false.B)
  }

  private def awaitRetirement(d: TranslatedProductHarness, cycles: Int = 70): Unit = {
    for (_ <- 0 until cycles) {
      if (!d.io.retirement.valid.peek().litToBoolean) d.clock.step()
    }
    d.io.retirement.valid.expect(true.B)
  }

  private def drained(d: TranslatedProductHarness): Unit = {
    d.io.occupancy.expect(0.U)
    d.io.loadCount.expect(0.U)
    d.io.storeCount.expect(0.U)
    d.io.outstanding.expect(0.U)
    d.io.resolverPending.expect(0.U)
  }

  private def allowAllPmp(d: TranslatedProductHarness): Unit = {
    d.io.pmpConfig(0).poke("h1f".U)
    d.io.pmpAddress(0).poke(((BigInt(1) << d.io.pmpAddress(0).getWidth) - 1).U)
  }

  private def supplyPte(d: TranslatedProductHarness, pte: BigInt): Unit = {
    for (_ <- 0 until 35) {
      if (!d.io.pte.valid.peek().litToBoolean) d.clock.step()
    }
    d.io.pte.valid.expect(true.B)
    d.io.pte.rdata.poke(pte.U)
    d.io.pte.ready.poke(true.B)
    d.clock.step()
    d.io.pte.ready.poke(false.B)
  }

  it should "execute decoded ADDI -> SW -> LW through the real resolver and physical LSQ" in {
    simulate(new TranslatedProductHarness) { d =>
      init(d)
      dispatch(d, BigInt("80000000", 16), BigInt("00500093", 16)) // addi x1,x0,5
      awaitRetirement(d)
      d.io.retirement.bits.rd.expect(1.U)
      d.io.retirement.bits.value.expect(5.U)
      d.io.retirement.bits.exception.valid.expect(false.B)
      d.clock.step()

      dispatch(d, BigInt("80000004", 16), BigInt("00102023", 16)) // sw x1,0(x0)
      awaitRequest(d)
      d.io.physicalRequest.bits.op.expect(AetherMemOp.Write)
      d.io.physicalRequest.bits.paddr.expect(0.U)
      d.io.physicalRequest.bits.size.expect(MemSize.Word)
      d.io.physicalRequest.bits.wmask.expect(15.U)
      d.io.physicalRequest.bits.wdata.expect(5.U)
      val storeTxn = d.io.physicalRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, storeTxn, 0)
      awaitRetirement(d)
      d.io.retirement.bits.exception.valid.expect(false.B)
      d.io.retirement.bits.wroteRegister.expect(false.B)
      d.clock.step()

      dispatch(d, BigInt("80000008", 16), BigInt("00002183", 16)) // lw x3,0(x0)
      awaitRequest(d)
      d.io.physicalRequest.bits.op.expect(AetherMemOp.Read)
      d.io.physicalRequest.bits.paddr.expect(0.U)
      val loadTxn = d.io.physicalRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, loadTxn, 5)
      awaitRetirement(d)
      d.io.retirement.bits.rd.expect(3.U)
      d.io.retirement.bits.value.expect(5.U)
      d.io.retirement.bits.exception.valid.expect(false.B)
      d.clock.step()
      drained(d)
    }
  }

  it should "raise a precise original-VA alignment fault without issuing a physical request" in {
    simulate(new TranslatedProductHarness) { d =>
      init(d)
      dispatch(d, BigInt("80000000", 16), BigInt("00102183", 16)) // lw x3,1(x0)
      var sawFault = false
      for (_ <- 0 until 55) {
        d.io.physicalRequest.valid.expect(false.B)
        if (d.io.retirement.valid.peek().litToBoolean) {
          d.io.retirement.bits.exception.valid.expect(true.B)
          d.io.retirement.bits.exception.cause.expect(MachineExceptionCode.LoadAddressMisaligned.U)
          d.io.retirement.bits.exception.value.expect(1.U)
          d.io.retirement.bits.wroteRegister.expect(false.B)
          d.io.trap.valid.expect(true.B)
          sawFault = true
        }
        d.clock.step()
      }
      sawFault shouldBe true
      drained(d)
    }
  }

  it should "walk shared Sv39 PTW and issue a physical load to its translated PA" in {
    simulate(new TranslatedProductHarness) { d =>
      init(d)
      allowAllPmp(d)
      d.io.context.effectivePrivilege.poke(PrivilegeMode.Supervisor.U)
      d.io.context.satpEnabled.poke(true.B)
      dispatch(d, BigInt("80000000", 16), BigInt("00002183", 16)) // lw x3,0(x0)
      // Sv39 VA 0: root PPN1 -> level1 PPN2 -> level0 PPN3.
      // The leaf PTE maps the original virtual address to PA 0x80000000.
      supplyPte(d, (BigInt(2) << 10) | 1)
      supplyPte(d, (BigInt(3) << 10) | 1)
      supplyPte(d, (BigInt("80000", 16) << 10) | BigInt("c7", 16))
      awaitRequest(d)
      d.io.physicalRequest.bits.op.expect(AetherMemOp.Read)
      d.io.physicalRequest.bits.paddr.expect("h80000000".U)
      val txn = d.io.physicalRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, txn, BigInt("12345678", 16))
      awaitRetirement(d)
      d.io.retirement.bits.exception.valid.expect(false.B)
      d.io.retirement.bits.rd.expect(3.U)
      d.io.retirement.bits.value.expect("h12345678".U)
      d.clock.step()
      drained(d)
    }
  }
}
