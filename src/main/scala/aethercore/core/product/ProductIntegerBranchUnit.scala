package aethercore.core.product

import chisel3._
import chisel3.util._
import aethercore.common.{AluOp, BranchType, MachineExceptionCode}
import aethercore.core.ALU
import aethercore.core.semantic.ControlFlowKind

/** Registered RV64I integer and branch FU. Deliberately no M/F/D or memory
  * datapaths: M uses a separate future iterative unit. The shared ALU has its
  * multiplier/divider elaboration physically disabled for FPGA efficiency.
  */
class ProductIntegerBranchUnit(val geometry: ProductCoreGeometry, val hasCompressed: Boolean)
  extends Module {
  private val I = log2Ceil(geometry.robEntries)
  private val P = log2Ceil(geometry.integerPhysicalRegisters)
  private val G = 16
  private val Xlen = 64
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ProductIssue(Xlen, I, P, G)))
    val response = Decoupled(new ProductCompletion(Xlen, I, P, G))
    val unsupportedM = Output(Bool())
  })

  val req = io.request.bits
  val mInstruction =
    req.aluOp === AluOp.Mul || req.aluOp === AluOp.Mulh ||
    req.aluOp === AluOp.Mulhsu || req.aluOp === AluOp.Mulhu ||
    req.aluOp === AluOp.Div || req.aluOp === AluOp.Divu ||
    req.aluOp === AluOp.Rem || req.aluOp === AluOp.Remu
  io.unsupportedM := io.request.valid && mInstruction

  val alu = Module(new ALU(Xlen, withMulDiv = false))
  alu.io.a := req.lhs
  alu.io.b := req.rhs
  alu.io.op := req.aluOp
  alu.io.wordOp := req.wordOp

  val conditional = req.controlFlow === ControlFlowKind.Conditional
  val branch = req.controlFlow =/= ControlFlowKind.None
  val takenCondition = WireDefault(false.B)
  switch(req.branchType) {
    is(BranchType.Eq)  { takenCondition := req.lhs === req.rhs }
    is(BranchType.Ne)  { takenCondition := req.lhs =/= req.rhs }
    is(BranchType.Lt)  { takenCondition := req.lhs.asSInt < req.rhs.asSInt }
    is(BranchType.Ge)  { takenCondition := req.lhs.asSInt >= req.rhs.asSInt }
    is(BranchType.Ltu) { takenCondition := req.lhs < req.rhs }
    is(BranchType.Geu) { takenCondition := req.lhs >= req.rhs }
  }
  val taken = branch && (!conditional || takenCondition)
  val jumpTarget = Mux(req.controlFlow === ControlFlowKind.IndirectJump,
    (req.lhs + req.immediate) & (~1.U(Xlen.W)),
    req.pc + req.immediate)
  val sequentialPc = req.pc + req.instBytes
  val nextPc = Mux(taken, jumpTarget, sequentialPc)
  val link = req.controlFlow === ControlFlowKind.DirectJump ||
    req.controlFlow === ControlFlowKind.IndirectJump
  val alignmentMask = (if (hasCompressed) BigInt(1) else BigInt(3)).U(Xlen.W)
  val misaligned = taken && ((jumpTarget & alignmentMask) =/= 0.U)

  private val held = RegInit(false.B)
  private val responseReg = Reg(new ProductCompletion(Xlen, I, P, G))
  io.request.ready := (!held || io.response.ready) && !mInstruction
  io.response.valid := held
  io.response.bits := responseReg

  when(io.request.fire) {
    held := true.B
    responseReg := 0.U.asTypeOf(responseReg)
    responseReg.token := req.token
    responseReg.destination := req.destination
    responseReg.hasValue := req.hasDestination
    responseReg.value := Mux(link, sequentialPc, alu.io.out)
    responseReg.branchValid := branch
    responseReg.nextPc := nextPc
    responseReg.exception.valid := misaligned
    responseReg.exception.cause := MachineExceptionCode.InstructionAddressMisaligned.U
    responseReg.exception.value := jumpTarget
  }.elsewhen(io.response.fire) {
    held := false.B
  }
}

/** First executable same-source product *integer subset* (no fetch/LSU/CSR).
  *
  * Uses the real ProductSmallOoOBackend, canonical DecodedInstruction,
  * and the shared FPGA-slimmed RV64I ALU. This is not a Linux-capable CPU.
  */
class ProductSmallIntegerCore(val config: ProductCoreConfig) extends Module {
  private val g = config.geometry
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val G = 16

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new ProductDispatch(64)))
    val retire = Decoupled(new ProductRetirement(64, I, P, G))
    val redirect = Valid(new ProductRedirect(64, I, G))
    val trap = Valid(new ProductRetirement(64, I, P, G))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
    val unsupported = Output(Bool())
    val mUnitNeeded = Output(Bool())
  })

  private val backend = Module(new ProductSmallOoOBackend(g))
  private val execute = Module(new ProductIntegerBranchUnit(g, config.software.isa.hasC))

  backend.io.dispatch.valid := io.dispatch.valid
  backend.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := backend.io.dispatch.ready

  execute.io.request <> backend.io.issue
  backend.io.completion <> execute.io.response

  io.retire.valid := backend.io.retire.valid
  io.retire.bits := backend.io.retire.bits
  backend.io.retire.ready := io.retire.ready
  io.redirect := backend.io.redirect
  io.trap := backend.io.trap
  io.occupancy := backend.io.occupancy
  io.unsupported := backend.io.unsupported
  io.mUnitNeeded := execute.io.unsupportedM
}
