package aethercore

import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize, PrivilegeMode}
import aethercore.core.product._
import aethercore.core.semantic.SemanticDecode
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}
import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Machine-code semantic decode and the actual Product ROB/Resolver/LSQ/Bus.
  * Host-supplied memory responses exercise the existing AetherMem atomic
  * contract; the Product never performs a private read-modify-write.
  */
private class ProductAtomicHarness extends Module {
  private val config = ProductCoreProfiles.smallEngineering
  private val g = config.geometry
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val PA = config.platform.paddrBits
  private val Tx = log2Ceil(g.loadQueueEntries + g.storeQueueEntries)

  val io = IO(new Bundle {
    val pc = Input(UInt(64.W))
    val inst = Input(UInt(32.W))
    val dispatchValid = Input(Bool())
    val dispatchReady = Output(Bool())
    val supportsAtomic = Input(Bool())
    val memoryRequest = Decoupled(new AetherMemRequest(PA, 64, Tx))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(64, Tx)))
    val retirement = Decoupled(new ProductRetirement(64, I, P, 16))
    val trap = Valid(new ProductRetirement(64, I, P, 16))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
    val loadCount = Output(UInt(log2Ceil(g.loadQueueEntries + 1).W))
    val storeCount = Output(UInt(log2Ceil(g.storeQueueEntries + 1).W))
    val outstanding = Output(UInt(log2Ceil(g.loadQueueEntries + g.storeQueueEntries + 1).W))
    val translationPending = Output(UInt(log2Ceil(g.loadQueueEntries + g.storeQueueEntries + 1).W))
  })

  private val decode = Module(new SemanticDecode(config.software.isa))
  decode.io.pc := io.pc
  decode.io.inst := io.inst
  decode.io.rawInst := io.inst
  decode.io.instBytes := 4.U
  decode.io.fetchException := 0.U.asTypeOf(decode.io.fetchException)

  private val core = Module(new ProductSmallTranslatedCore(config))
  core.io.systemCsrReadData := 0.U
  core.io.systemCsrReadImplemented := false.B
  core.io.systemCsrReadWritable := false.B
  core.io.systemCurrentPrivilege := PrivilegeMode.Machine.U
  core.io.dispatch.valid := io.dispatchValid
  core.io.dispatch.bits.decoded := decode.io.decoded
  core.io.dispatch.bits.predictedNextPc := io.pc + 4.U
  io.dispatchReady := core.io.dispatch.ready
  core.io.context.effectivePrivilege := PrivilegeMode.Machine.U
  core.io.context.satpEnabled := false.B
  core.io.context.rootPpn := 0.U
  core.io.context.sum := false.B
  core.io.context.mxr := false.B
  core.io.pmpConfig := 0.U.asTypeOf(core.io.pmpConfig)
  core.io.pmpAddress := 0.U.asTypeOf(core.io.pmpAddress)
  core.io.translationFence := false.B
  core.io.pte.ready := false.B
  core.io.pte.rdata := 0.U
  core.io.pte.fault := false.B

  core.io.pmaAttributes.cacheable := true.B
  core.io.pmaAttributes.idempotent := true.B
  core.io.pmaAttributes.sideEffecting := false.B
  core.io.pmaAttributes.ordered := false.B
  core.io.pmaAttributes.executable := true.B
  core.io.pmaAttributes.supportsAtomic := io.supportsAtomic
  core.io.pmaAttributes.supportsPartial := true.B

  io.memoryRequest <> core.io.memoryRequest
  core.io.memoryResponse <> io.memoryResponse
  io.retirement <> core.io.retire
  io.trap := core.io.trap
  io.occupancy := core.io.occupancy
  io.loadCount := core.io.loadCount
  io.storeCount := core.io.storeCount
  io.outstanding := core.io.outstandingTransactions
  io.translationPending := core.io.resolverPending
}

class ProductAtomicCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product SmallOoO exact-head RV64A execution"

  private def atomic(funct5: Int, rd: Int, rs1: Int, rs2: Int,
                     dword: Boolean = false, acquire: Boolean = false,
                     release: Boolean = false): BigInt = {
    (BigInt(funct5) << 27) |
      (BigInt(if (acquire) 1 else 0) << 26) |
      (BigInt(if (release) 1 else 0) << 25) |
      (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(if (dword) 3 else 2) << 12) | (BigInt(rd) << 7) | 0x2f
  }

  private def init(d: ProductAtomicHarness): Unit = {
    d.io.pc.poke("h80000000".U)
    d.io.inst.poke("h00000013".U)
    d.io.dispatchValid.poke(false.B)
    d.io.supportsAtomic.poke(true.B)
    d.io.memoryRequest.ready.poke(true.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.io.retirement.ready.poke(true.B)
    d.clock.step()
  }

  private def dispatch(d: ProductAtomicHarness, pc: BigInt, inst: BigInt): Unit = {
    d.io.pc.poke(pc.U)
    d.io.inst.poke(inst.U)
    d.io.dispatchValid.poke(true.B)
    d.io.dispatchReady.expect(true.B)
    d.clock.step()
    d.io.dispatchValid.poke(false.B)
  }

  private def awaitRetirement(d: ProductAtomicHarness, cycles: Int = 80): Unit = {
    for (_ <- 0 until cycles) {
      if (!d.io.retirement.valid.peek().litToBoolean) d.clock.step()
    }
    d.io.retirement.valid.expect(true.B)
  }

  private def awaitRequest(d: ProductAtomicHarness, cycles: Int = 80): Unit = {
    for (_ <- 0 until cycles) {
      if (!d.io.memoryRequest.valid.peek().litToBoolean) d.clock.step()
    }
    d.io.memoryRequest.valid.expect(true.B)
  }

  private def response(d: ProductAtomicHarness, txn: BigInt, data: BigInt,
                       fault: Boolean = false): Unit = {
    d.io.memoryResponse.bits.txnId.poke(txn.U)
    d.io.memoryResponse.bits.rdata.poke(data.U)
    d.io.memoryResponse.bits.fault.poke(fault.B)
    d.io.memoryResponse.bits.last.poke(true.B)
    d.io.memoryResponse.valid.poke(true.B)
    d.io.memoryResponse.ready.expect(true.B)
    d.clock.step()
    d.io.memoryResponse.valid.poke(false.B)
  }

  private def retireRegister(d: ProductAtomicHarness, rd: Int, value: BigInt): Unit = {
    awaitRetirement(d)
    d.io.retirement.bits.rd.expect(rd.U)
    d.io.retirement.bits.value.expect(value.U)
    d.io.retirement.bits.wroteRegister.expect(true.B)
    d.io.retirement.bits.exception.valid.expect(false.B)
    d.clock.step()
  }

  private def drained(d: ProductAtomicHarness): Unit = {
    d.io.occupancy.expect(0.U)
    d.io.loadCount.expect(0.U)
    d.io.storeCount.expect(0.U)
    d.io.outstanding.expect(0.U)
    d.io.translationPending.expect(0.U)
  }

  it should "execute consecutive OpenSBI AMOSWAP.W.aq with old-value return and one exact-head Atomic request" in {
    simulate(new ProductAtomicHarness) { d =>
      init(d)
      val pc = BigInt("80000000", 16)
      dispatch(d, pc, BigInt("10000093", 16)) // addi x1,x0,0x100
      retireRegister(d, 1, 0x100)
      dispatch(d, pc + 4, BigInt("00100113", 16)) // addi x2,x0,1
      retireRegister(d, 2, 1)

      for ((rd, offset, oldValue) <- Seq((3, 8, BigInt(0)), (4, 12, BigInt(1)))) {
        val instruction = atomic(1, rd, 1, 2, acquire = true)
        instruction shouldBe BigInt(if (rd == 3) "0c20a1af" else "0c20a22f", 16)
        dispatch(d, pc + offset, instruction)
        awaitRequest(d)
        d.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
        d.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Swap)
        d.io.memoryRequest.bits.paddr.expect(0x100.U)
        d.io.memoryRequest.bits.size.expect(MemSize.Word)
        d.io.memoryRequest.bits.wmask.expect("hf".U)
        d.io.memoryRequest.bits.wdata.expect(1.U)
        val txn = d.io.memoryRequest.bits.txnId.peek().litValue
        d.clock.step()
        response(d, txn, oldValue)
        retireRegister(d, rd, oldValue)
      }
      drained(d)
    }
  }

  it should "return sign-extended LR.W value and honor external SC.W status even when reservation fails" in {
    simulate(new ProductAtomicHarness) { d =>
      init(d)
      val pc = BigInt("80000000", 16)
      dispatch(d, pc, BigInt("10000093", 16))
      retireRegister(d, 1, 0x100)
      dispatch(d, pc + 4, BigInt("02a00113", 16)) // addi x2,x0,42
      retireRegister(d, 2, 42)

      dispatch(d, pc + 8, atomic(2, 3, 1, 0)) // lr.w x3,(x1)
      awaitRequest(d)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
      d.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Lr)
      d.io.memoryRequest.bits.wmask.expect(0.U)
      val lrTxn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, lrTxn, BigInt("80000001", 16))
      retireRegister(d, 3, BigInt("ffffffff80000001", 16))

      for ((rd, offset, status) <- Seq((4, 12, BigInt(0)), (5, 16, BigInt(1)))) {
        dispatch(d, pc + offset, atomic(3, rd, 1, 2)) // sc.w rd,x2,(x1)
        awaitRequest(d)
        d.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
        d.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Sc)
        d.io.memoryRequest.bits.wmask.expect("hf".U)
        d.io.memoryRequest.bits.wdata.expect(42.U)
        val txn = d.io.memoryRequest.bits.txnId.peek().litValue
        d.clock.step()
        response(d, txn, status)
        retireRegister(d, rd, status)
      }
      drained(d)
    }
  }

  it should "fault a misaligned AMO.W at the original VA without any physical side effect" in {
    simulate(new ProductAtomicHarness) { d =>
      init(d)
      dispatch(d, BigInt("80000000", 16), BigInt("00100093", 16)) // x1 = 1
      retireRegister(d, 1, 1)
      dispatch(d, BigInt("80000004", 16), atomic(1, 3, 1, 0))
      var faultSeen = false
      for (_ <- 0 until 70) {
        d.io.memoryRequest.valid.expect(false.B)
        if (d.io.retirement.valid.peek().litToBoolean) {
          d.io.retirement.bits.exception.valid.expect(true.B)
          d.io.retirement.bits.exception.cause.expect(MachineExceptionCode.StoreAddressMisaligned.U)
          d.io.retirement.bits.exception.value.expect(1.U)
          d.io.retirement.bits.wroteRegister.expect(false.B)
          d.io.trap.valid.expect(true.B)
          faultSeen = true
        }
        d.clock.step()
      }
      faultSeen shouldBe true
      drained(d)
    }
  }

  it should "report LR access fault when atomic PMA capability is absent" in {
    simulate(new ProductAtomicHarness) { d =>
      init(d)
      d.io.supportsAtomic.poke(false.B)
      dispatch(d, BigInt("80000000", 16), atomic(2, 3, 0, 0))
      var faultSeen = false
      for (_ <- 0 until 70) {
        d.io.memoryRequest.valid.expect(false.B)
        if (d.io.retirement.valid.peek().litToBoolean) {
          d.io.retirement.bits.exception.valid.expect(true.B)
          d.io.retirement.bits.exception.cause.expect(MachineExceptionCode.LoadAccessFault.U)
          d.io.retirement.bits.exception.value.expect(0.U)
          d.io.retirement.bits.wroteRegister.expect(false.B)
          faultSeen = true
        }
        d.clock.step()
      }
      faultSeen shouldBe true
      drained(d)
    }
  }

  it should "retire a physical AMO fault without a register write or later transaction alias" in {
    simulate(new ProductAtomicHarness) { d =>
      init(d)
      val pc = BigInt("80000000", 16)
      dispatch(d, pc, BigInt("10000093", 16)) // x1 = effective VA 0x100
      retireRegister(d, 1, 0x100)
      dispatch(d, pc + 4, atomic(1, 3, 1, 0, dword = true))
      awaitRequest(d)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Atomic)
      d.io.memoryRequest.bits.atomicOp.expect(AtomicOp.Swap)
      d.io.memoryRequest.bits.size.expect(MemSize.DWord)
      val txn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      response(d, txn, 0, fault = true)
      awaitRetirement(d)
      d.io.retirement.bits.exception.valid.expect(true.B)
      d.io.retirement.bits.exception.cause.expect(MachineExceptionCode.StoreAccessFault.U)
      d.io.retirement.bits.exception.value.expect(0x100.U)
      d.io.retirement.bits.wroteRegister.expect(false.B)
      d.io.trap.valid.expect(true.B)
      d.clock.step()
      for (_ <- 0 until 16) d.clock.step()
      drained(d)
    }
  }
}
