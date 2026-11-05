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
class ProductIntegerBranchUnit(val geometry: ProductCoreGeometry, val hasCompressed: Boolean,
                               val paddrBits: Int = 56)
  extends Module {
  private val I = log2Ceil(geometry.robEntries)
  private val P = log2Ceil(geometry.integerPhysicalRegisters)
  private val G = 16
  private val Xlen = 64
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ProductIssue(Xlen, I, P, G)))
    val response = Decoupled(new ProductCompletion(Xlen, I, P, G, paddrBits))
    val unsupportedM = Output(Bool())
    val available = Output(Bool())
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
  private val responseReg = Reg(new ProductCompletion(Xlen, I, P, G, paddrBits))
  io.available := !held || io.response.ready
  io.request.ready := io.available && !mInstruction
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
