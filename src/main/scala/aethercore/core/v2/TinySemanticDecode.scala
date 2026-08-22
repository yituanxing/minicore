package aethercore.core.v2

import chisel3._
import aethercore.common._
import aethercore.config.IsaConfig
import aethercore.core.{Decoder, Immediate}

/**
  * F7 architectural decode bridge.
  *
  * The qualified v1 Decoder is reused as the ISA legality/field decoder, but
  * its pipeline-oriented selectors terminate here. Nothing downstream of this
  * module receives OpASel/OpBSel/WbSel/ImmSel or a fixed execution-port choice;
  * it receives only architectural semantics in RobDispatch.
  */
class TinySemanticDecode(val isa: IsaConfig) extends Module {
  private val xlen = isa.xlen

  val io = IO(new Bundle {
    val pc = Input(UInt(xlen.W))
    val inst = Input(UInt(32.W))
    val rawInst = Input(UInt(32.W))
    val instBytes = Input(UInt(3.W))
    // Instruction-side VM/PMP/bus faults are architectural facts by the time
    // they reach decode. F7 frontend integration will drive this input.
    val fetchException = Input(new TrapInfo(xlen))

    val dispatch = Output(new RobDispatch(xlen))
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

  private val mulDiv =
    ctrl.aluOp === AluOp.Mul ||
      ctrl.aluOp === AluOp.Mulh ||
      ctrl.aluOp === AluOp.Mulhsu ||
      ctrl.aluOp === AluOp.Mulhu ||
      ctrl.aluOp === AluOp.Div ||
      ctrl.aluOp === AluOp.Divu ||
      ctrl.aluOp === AluOp.Rem ||
      ctrl.aluOp === AluOp.Remu

  private val executionClass = WireDefault(ExecutionClass.Integer)
  when(systemKind =/= SystemOperationKind.None) {
    executionClass := ExecutionClass.System
  }.elsewhen(memoryKind =/= MemoryOperationKind.None) {
    executionClass := ExecutionClass.Memory
  }.elsewhen(controlFlowKind =/= ControlFlowKind.None) {
    executionClass := ExecutionClass.Branch
  }.elsewhen(mulDiv) {
    executionClass := ExecutionClass.MulDiv
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

  private val hasDecodeException = decodedException.valid
  io.dispatch := 0.U.asTypeOf(new RobDispatch(xlen))
  io.dispatch.decoded.pc := io.pc
  io.dispatch.decoded.inst := io.inst
  io.dispatch.decoded.rawInst := io.rawInst
  io.dispatch.decoded.instBytes := io.instBytes
  io.dispatch.decoded.aluOp := ctrl.aluOp
  io.dispatch.decoded.wordOp := ctrl.wordOp
  io.dispatch.decoded.lhsSource := lhsSource
  io.dispatch.decoded.rhsSource := rhsSource
  io.dispatch.decoded.rs1 := decoder.io.rs1
  io.dispatch.decoded.rs2 := decoder.io.rs2
  io.dispatch.decoded.rd := decoder.io.rd
  io.dispatch.decoded.usesRs1 := ctrl.usesRs1 && !hasDecodeException
  io.dispatch.decoded.usesRs2 := ctrl.usesRs2 && !hasDecodeException
  io.dispatch.decoded.writesRd := ctrl.regWrite && !hasDecodeException
  io.dispatch.decoded.immediate := decodedImm
  io.dispatch.decoded.controlFlow.kind := controlFlowKind
  io.dispatch.decoded.controlFlow.branchType := ctrl.branch
  io.dispatch.decoded.memory.kind := memoryKind
  io.dispatch.decoded.memory.size := ctrl.memSize
  io.dispatch.decoded.memory.unsigned := ctrl.memUnsigned
  io.dispatch.decoded.memory.atomicOp := ctrl.atomicOp
  io.dispatch.decoded.memory.acquire := acquire
  io.dispatch.decoded.memory.release := release
  io.dispatch.decoded.system.kind := systemKind
  io.dispatch.decoded.system.csrOp := ctrl.csrOp
  io.dispatch.decoded.system.csrAddress := io.inst(31, 20)
  io.dispatch.decoded.system.csrUseImmediate := ctrl.csrUseImm
  io.dispatch.decoded.system.csrImmediate := decoder.io.rs1
  io.dispatch.decoded.system.xret := ctrl.xret
  io.dispatch.decoded.ordering := ordering
  io.dispatch.decoded.exception := decodedException
  io.dispatch.executionClass := executionClass
  io.dispatch.producesValue := ctrl.regWrite && decoder.io.rd =/= 0.U && !hasDecodeException
}
