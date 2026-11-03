package aethercore

import aethercore.common.{MachineExceptionCode, PrivilegeMode}
import aethercore.core.product._
import aethercore.core.semantic.SemanticDecode
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}
import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The real Product completion fabric is under test; CSR state is read-only
  * external input so effects can be proven to arise only at precise retire.
  */
private class ProductSystemHarness extends Module {
  private val cfg = ProductCoreProfiles.smallEngineering
  private val g = cfg.geometry
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val PA = cfg.platform.paddrBits
  private val Tx = log2Ceil(g.loadQueueEntries + g.storeQueueEntries)

  val io = IO(new Bundle {
    val pc = Input(UInt(64.W))
    val inst = Input(UInt(32.W))
    val dispatchValid = Input(Bool())
    val dispatchReady = Output(Bool())
    val csrReadAddr = Output(UInt(12.W))
    val csrReadData = Input(UInt(64.W))
    val csrImplemented = Input(Bool())
    val csrWritable = Input(Bool())
    val privilege = Input(UInt(2.W))
    val memoryRequest = Decoupled(new AetherMemRequest(PA, 64, Tx))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(64, Tx)))
    val retirement = Decoupled(new ProductRetirement(64, I, P, 16))
    val trap = Valid(new ProductRetirement(64, I, P, 16))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
  })

  private val decode = Module(new SemanticDecode(cfg.software.isa))
  decode.io.pc := io.pc
  decode.io.inst := io.inst
  decode.io.rawInst := io.inst
  decode.io.instBytes := 4.U
  decode.io.fetchException := 0.U.asTypeOf(decode.io.fetchException)

  private val core = Module(new ProductSmallTranslatedCore(cfg))
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
  core.io.pmaAttributes.supportsAtomic := true.B
  core.io.pmaAttributes.supportsPartial := true.B

  io.csrReadAddr := core.io.systemCsrReadAddr
  core.io.systemCsrReadData := io.csrReadData
  core.io.systemCsrReadImplemented := io.csrImplemented
  core.io.systemCsrReadWritable := io.csrWritable
  core.io.systemCurrentPrivilege := io.privilege
  io.memoryRequest <> core.io.memoryRequest
  core.io.memoryResponse <> io.memoryResponse
  io.retirement <> core.io.retire
  io.trap := core.io.trap
  io.occupancy := core.io.occupancy
}

class ProductSystemCoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product head-only SYSTEM semantic completion and ordered commit"

  private def init(d: ProductSystemHarness): Unit = {
    d.io.pc.poke("h80000000".U)
    d.io.inst.poke("h00000013".U)
    d.io.dispatchValid.poke(false.B)
    d.io.csrReadData.poke("h1800".U)
    d.io.csrImplemented.poke(true.B)
    d.io.csrWritable.poke(true.B)
    d.io.privilege.poke(PrivilegeMode.Machine.U)
    d.io.memoryRequest.ready.poke(true.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.io.retirement.ready.poke(true.B)
    d.clock.step()
  }

  private def dispatch(d: ProductSystemHarness, pc: BigInt, inst: BigInt): Unit = {
    d.io.pc.poke(pc.U)
    d.io.inst.poke(inst.U)
    d.io.dispatchValid.poke(true.B)
    d.io.dispatchReady.expect(true.B)
    d.clock.step()
    d.io.dispatchValid.poke(false.B)
  }

  private def awaitRetire(d: ProductSystemHarness, cycles: Int = 90): Unit = {
    for (_ <- 0 until cycles) {
      if (!d.io.retirement.valid.peek().litToBoolean) d.clock.step()
    }
    d.io.retirement.valid.expect(true.B)
  }

  private def normalRetire(d: ProductSystemHarness, rd: Int, value: BigInt): Unit = {
    awaitRetire(d)
    d.io.retirement.bits.exception.valid.expect(false.B)
    d.io.retirement.bits.rd.expect(rd.U)
    d.io.retirement.bits.value.expect(value.U)
    d.clock.step()
  }

  it should "read pre-rename rs1 when CSRRW destination equals source and commit only a pending effect" in {
    simulate(new ProductSystemHarness) { d =>
      init(d)
      val pc = BigInt("80000000", 16)
      dispatch(d, pc, BigInt("00500093", 16)) // addi x1,x0,5
      normalRetire(d, 1, 5)
      dispatch(d, pc + 4, BigInt("300090f3", 16)) // csrrw x1,mstatus,x1
      awaitRetire(d)
      d.io.csrReadAddr.expect("h300".U)
      d.io.retirement.bits.rd.expect(1.U)
      d.io.retirement.bits.wroteRegister.expect(true.B)
      d.io.retirement.bits.value.expect("h1800".U)
      d.io.retirement.bits.privileged.csrWriteValid.expect(true.B)
      d.io.retirement.bits.privileged.csrAddress.expect("h300".U)
      d.io.retirement.bits.privileged.csrWriteData.expect(5.U)
      d.clock.step()
      dispatch(d, pc + 8, BigInt("00108113", 16)) // addi x2,x1,1
      normalRetire(d, 2, BigInt("1801", 16))
      d.io.occupancy.expect(0.U)
    }
  }

  it should "hold younger dispatch behind an older Load and a head-only CSR barrier" in {
    simulate(new ProductSystemHarness) { d =>
      init(d)
      val pc = BigInt("80000000", 16)
      dispatch(d, pc, BigInt("00002183", 16)) // lw x3,0(x0)
      dispatch(d, pc + 4, BigInt("30002273", 16)) // csrrs x4,mstatus,x0
      d.io.pc.poke((pc + 8).U)
      d.io.inst.poke("h00100293".U) // addi x5,x0,1
      d.io.dispatchValid.poke(true.B)
      for (_ <- 0 until 5) {
        d.io.dispatchReady.expect(false.B)
        d.io.retirement.valid.expect(false.B)
        d.clock.step()
      }
      d.io.dispatchValid.poke(false.B)
      for (_ <- 0 until 50) {
        if (!d.io.memoryRequest.valid.peek().litToBoolean) d.clock.step()
      }
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
      val txn = d.io.memoryRequest.bits.txnId.peek().litValue
      d.clock.step()
      d.io.memoryResponse.bits.txnId.poke(txn.U)
      d.io.memoryResponse.bits.rdata.poke(42.U)
      d.io.memoryResponse.bits.fault.poke(false.B)
      d.io.memoryResponse.bits.last.poke(true.B)
      d.io.memoryResponse.valid.poke(true.B)
      d.io.memoryResponse.ready.expect(true.B)
      d.clock.step()
      d.io.memoryResponse.valid.poke(false.B)
      normalRetire(d, 3, 42)
      awaitRetire(d)
      d.io.retirement.bits.rd.expect(4.U)
      d.io.retirement.bits.value.expect("h1800".U)
      d.io.retirement.bits.privileged.csrWriteValid.expect(false.B)
      d.clock.step()
      dispatch(d, pc + 8, BigInt("00100293", 16))
      normalRetire(d, 5, 1)
      d.io.occupancy.expect(0.U)
    }
  }

  it should "represent xRET, SFENCE, FENCE.I and WFI as retirement effects" in {
    simulate(new ProductSystemHarness) { d =>
      init(d)
      val pc = BigInt("80000000", 16)
      dispatch(d, pc, BigInt("30200073", 16)) // mret
      awaitRetire(d)
      d.io.retirement.bits.privileged.trapReturn.expect(true.B)
      d.io.retirement.bits.privileged.trapReturnSupervisor.expect(false.B)
      d.clock.step()
      dispatch(d, pc + 4, BigInt("12000073", 16)) // sfence.vma x0,x0
      awaitRetire(d)
      d.io.retirement.bits.privileged.sfenceVma.expect(true.B)
      d.clock.step()
      dispatch(d, pc + 8, BigInt("0000100f", 16)) // fence.i
      awaitRetire(d)
      d.io.retirement.bits.privileged.fenceI.expect(true.B)
      d.clock.step()
      dispatch(d, pc + 12, BigInt("10500073", 16)) // wfi
      awaitRetire(d)
      d.io.retirement.bits.privileged.wfi.expect(true.B)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }

  it should "reject CSR writes to a read-only register without losing the allocated ROB destination" in {
    simulate(new ProductSystemHarness) { d =>
      init(d)
      d.io.csrWritable.poke(false.B)
      dispatch(d, BigInt("80000000", 16), BigInt("300090f3", 16))
      awaitRetire(d)
      d.io.retirement.bits.exception.valid.expect(true.B)
      d.io.retirement.bits.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
      d.io.retirement.bits.exception.value.expect("h300090f3".U)
      d.io.retirement.bits.wroteRegister.expect(false.B)
      d.io.retirement.bits.privileged.csrWriteValid.expect(false.B)
      d.io.trap.valid.expect(true.B)
      d.clock.step(20)
      d.io.occupancy.expect(0.U)
    }
  }
}
