package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common._
import aethercore.config.IsaConfig
import aethercore.core.{Decoder, Immediate}

/**
  * Architectural semantic decode boundary.
  *
  * The qualified Decoder owns ISA legality/field decoding. Its legacy
  * pipeline-oriented selectors terminate here. The only output of this module
  * is DecodedInstruction: architectural meaning with no ROB identity,
  * execution class, issue-port choice, queue identity, predictor metadata or
  * backend value-production policy.
  */
class TinySemanticDecode(val isa: IsaConfig) extends Module {
  private val xlen = isa.xlen

  val io = IO(new Bundle {
    val pc = Input(UInt(xlen.W))
    val inst = Input(UInt(32.W))
    val rawInst = Input(UInt(32.W))
    val instBytes = Input(UInt(3.W))
    val fetchException = Input(new TrapInfo(xlen))

    val decoded = Output(new DecodedInstruction(xlen))
  })

  val decoder = Module(new Decoder(isa))
  decoder.io.inst := io.inst

  private val ctrl = decoder.io.ctrl
  private val opcode = io.inst(6, 0)
  private val funct3 = io.inst(14, 12)

  // Decoder intentionally leaves SFENCE.VMA to the privileged/VM composition
  // because rs1/rs2 are semantic operands. Recognize only the architectural
  // encoding family here; privilege legality remains owned by F6/F7 commit.
  private val sfenceVma =
    (isa.hasS && isa.hasPagedVirtualMemory).B &&
      ((io.inst & "hfe007fff".U) === "h12000073".U)
  private val fence = opcode === "b0001111".U && funct3 === 0.U
  private val fenceI = opcode === "b0001111".U && funct3 === 1.U

  private val decodedImm = WireDefault(0.U(xlen.W))
  switch(ctrl.immSel) {
    is(ImmSel.I) { decodedImm := Immediate.i(io.inst, xlen) }
    is(ImmSel.S) { decodedImm := Immediate.s(io.inst, xlen) }
    is(ImmSel.B) { decodedImm := Immediate.b(io.inst, xlen) }
    is(ImmSel.U) { decodedImm := Immediate.u(io.inst, xlen) }
    is(ImmSel.J) { decodedImm := Immediate.j(io.inst, xlen) }
  }

  private val systemKind = WireDefault(SystemOperationKind.None)
  when(ctrl.csrOp =/= CsrOp.None) {
    systemKind := SystemOperationKind.Csr
  }.elsewhen(ctrl.trap && io.inst === "h00000073".U) {
    systemKind := SystemOperationKind.Ecall
  }.elsewhen(ctrl.trap && io.inst === "h00100073".U) {
    systemKind := SystemOperationKind.Ebreak
  }.elsewhen(ctrl.wfi) {
    systemKind := SystemOperationKind.Wfi
  }.elsewhen(ctrl.xret =/= XRetOp.None) {
    systemKind := SystemOperationKind.Xret
  }.elsewhen(sfenceVma) {
    systemKind := SystemOperationKind.SfenceVma
  }.elsewhen(fenceI) {
    systemKind := SystemOperationKind.FenceI
  }.elsewhen(fence) {
    systemKind := SystemOperationKind.Fence
  }

  private val memoryKind = WireDefault(MemoryOperationKind.None)
  when(ctrl.atomicOp =/= AtomicOp.None) {
    memoryKind := MemoryOperationKind.Atomic
  }.elsewhen(ctrl.memRead) {
    memoryKind := MemoryOperationKind.Load
  }.elsewhen(ctrl.memWrite) {
    memoryKind := MemoryOperationKind.Store
  }

  private val controlFlowKind = WireDefault(ControlFlowKind.None)
  when(ctrl.branch =/= BranchType.None) {
    controlFlowKind := ControlFlowKind.Conditional
  }.elsewhen(ctrl.jump && ctrl.jalr) {
    controlFlowKind := ControlFlowKind.IndirectJump
  }.elsewhen(ctrl.jump) {
    controlFlowKind := ControlFlowKind.DirectJump
  }

  private val decodedException = WireDefault(0.U.asTypeOf(new TrapInfo(xlen)))
  when(io.fetchException.valid) {
    decodedException := io.fetchException
  }.elsewhen(ctrl.illegal && !sfenceVma) {
    decodedException.valid := true.B
    decodedException.cause := MachineExceptionCode.IllegalInstruction.U(xlen.W)
    decodedException.value :=
      (if (xlen == 32) io.rawInst else Cat(0.U((xlen - 32).W), io.rawInst))
  }

  private val atomic = ctrl.atomicOp =/= AtomicOp.None
  private val acquire = atomic && io.inst(26)
  private val release = atomic && io.inst(25)
  private val ordering = WireDefault(OrderingClass.Normal)
  when(systemKind === SystemOperationKind.SfenceVma) {
    ordering := OrderingClass.TranslationFence
  }.elsewhen(systemKind === SystemOperationKind.Fence) {
    ordering := OrderingClass.MemoryFence
  }.elsewhen(systemKind =/= SystemOperationKind.None) {
    ordering := OrderingClass.SerializeBoth
  }.elsewhen(atomic && acquire && release) {
    ordering := OrderingClass.SerializeBoth
  }.elsewhen(atomic && release) {
    ordering := OrderingClass.SerializeBefore
  }.elsewhen(atomic && acquire) {
    ordering := OrderingClass.SerializeAfter
  }

  private val lhsSource = WireDefault(OperandSourceKind.Rs1)
  switch(ctrl.opASel) {
    is(OpASel.Pc)   { lhsSource := OperandSourceKind.Pc }
    is(OpASel.Zero) { lhsSource := OperandSourceKind.Zero }
    is(OpASel.Rs1)  { lhsSource := OperandSourceKind.Rs1 }
  }
  private val rhsSource =
    Mux(ctrl.opBSel === OpBSel.Imm, OperandSourceKind.Immediate, OperandSourceKind.Rs2)

  // SFENCE.VMA is recognized above the legacy Decoder, so its architectural
  // rs1/rs2 operands must be restored here. Preserve the dependency facts so
  // later selective invalidation never has to re-decode raw opcode bits.
  private val semanticUsesRs1 = ctrl.usesRs1 || sfenceVma
  private val semanticUsesRs2 = ctrl.usesRs2 || sfenceVma

  private val hasDecodeException = decodedException.valid
  io.decoded := 0.U.asTypeOf(new DecodedInstruction(xlen))
  io.decoded.pc := io.pc
  io.decoded.inst := io.inst
  io.decoded.rawInst := io.rawInst
  io.decoded.instBytes := io.instBytes
  io.decoded.aluOp := ctrl.aluOp
  io.decoded.wordOp := ctrl.wordOp
  io.decoded.lhsSource := lhsSource
  io.decoded.rhsSource := rhsSource
  io.decoded.rs1 := decoder.io.rs1
  io.decoded.rs2 := decoder.io.rs2
  io.decoded.rd := decoder.io.rd
  io.decoded.usesRs1 := semanticUsesRs1 && !hasDecodeException
  io.decoded.usesRs2 := semanticUsesRs2 && !hasDecodeException
  io.decoded.writesRd := ctrl.regWrite && !hasDecodeException
  io.decoded.immediate := decodedImm
  io.decoded.controlFlow.kind := controlFlowKind
  io.decoded.controlFlow.branchType := ctrl.branch
  io.decoded.memory.kind := memoryKind
  io.decoded.memory.size := ctrl.memSize
  io.decoded.memory.unsigned := ctrl.memUnsigned
  io.decoded.memory.atomicOp := ctrl.atomicOp
  io.decoded.memory.acquire := acquire
  io.decoded.memory.release := release
  io.decoded.system.kind := systemKind
  io.decoded.system.csrOp := ctrl.csrOp
  io.decoded.system.csrAddress := io.inst(31, 20)
  io.decoded.system.csrUseImmediate := ctrl.csrUseImm
  io.decoded.system.csrImmediate := decoder.io.rs1
  io.decoded.system.xret := ctrl.xret
  io.decoded.ordering := ordering
  io.decoded.exception := decodedException
}
