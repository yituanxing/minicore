package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{AluOp, BranchType, CommitTrace, MachineExceptionCode}

/**
  * F3 oldest-only issue owner.
  *
  * Readiness remains owned by F2. This module owns only the policy decision
  * that the current oldest ready uOp may issue once. The issued RobToken stays
  * remembered until the ROB head changes, so a completed-but-not-yet-retired
  * head cannot be dispatched a second time.
  */
class TinyOldestIssue(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"tiny issue XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val head = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    val headDependenciesValid = Input(Bool())
    val headRs1 = Input(new OperandState(xlen, IdentityBits, GenerationBits))
    val headRs2 = Input(new OperandState(xlen, IdentityBits, GenerationBits))
    val headOperandsReady = Input(Bool())
    val request = Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits))
  })

  private val issuedValid = RegInit(false.B)
  private val issuedToken = Reg(new RobToken(IdentityBits, GenerationBits))

  private def sameRobToken(a: RobToken, b: RobToken): Bool =
    a.index === b.index && a.generation === b.generation

  private def materializeSource(kind: OperandSourceKind.Type): UInt = {
    val value = WireDefault(0.U(xlen.W))
    switch(kind) {
      is(OperandSourceKind.Zero)      { value := 0.U }
      is(OperandSourceKind.Rs1)       { value := io.headRs1.value }
      is(OperandSourceKind.Rs2)       { value := io.headRs2.value }
      is(OperandSourceKind.Pc)        { value := io.head.bits.decoded.pc }
      is(OperandSourceKind.Immediate) { value := io.head.bits.decoded.immediate }
    }
    value
  }

  private val supportedClass =
    io.head.bits.executionClass === ExecutionClass.Integer ||
      io.head.bits.executionClass === ExecutionClass.Branch ||
      io.head.bits.executionClass === ExecutionClass.MulDiv
  private val alreadyIssued = issuedValid && sameRobToken(issuedToken, io.head.bits.robToken)

  io.request.valid := io.head.valid &&
    io.headDependenciesValid &&
    io.headOperandsReady &&
    supportedClass &&
    !alreadyIssued
  io.request.bits := 0.U.asTypeOf(new ExecutionRequest(xlen, IdentityBits, GenerationBits))
  io.request.bits.robToken := io.head.bits.robToken
  io.request.bits.producerTag := io.head.bits.producerTag
  io.request.bits.valueRef := io.head.bits.valueRef
  io.request.bits.executionClass := io.head.bits.executionClass
  io.request.bits.aluOp := io.head.bits.decoded.aluOp
  io.request.bits.wordOp := io.head.bits.decoded.wordOp
  io.request.bits.controlFlowKind := io.head.bits.decoded.controlFlow.kind
  io.request.bits.branchType := io.head.bits.decoded.controlFlow.branchType
  io.request.bits.lhs := materializeSource(io.head.bits.decoded.lhsSource)
  io.request.bits.rhs := materializeSource(io.head.bits.decoded.rhsSource)
  io.request.bits.pc := io.head.bits.decoded.pc
  io.request.bits.instBytes := io.head.bits.decoded.instBytes
  io.request.bits.immediate := io.head.bits.decoded.immediate

  // Once-only issue belongs to the current observed head lifetime. Numeric
  // RobTokens are bounded generation tags and may be reused after the head has
  // moved through other lifetimes; keeping the latch across a head change would
  // eventually suppress a genuinely new uOp after generation wrap.
  when(issuedValid &&
       (!io.head.valid || !sameRobToken(issuedToken, io.head.bits.robToken))) {
    issuedValid := false.B
  }
  // If a replacement head issues on the same cycle that the stale latch is
  // cleared, the new request wins and becomes the current once-only owner.
  when(io.request.fire) {
    issuedValid := true.B
    issuedToken := io.head.bits.robToken
  }
}

/** One-entry registered integer unit; execution latency is one cycle. */
class V2IntegerUnit(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"integer unit XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val response = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  })

  private val responseValid = RegInit(false.B)
  private val responseBits = Reg(new ExecutionResponse(xlen, IdentityBits, GenerationBits))

  io.request.ready := !responseValid || io.response.ready
  io.response.valid := responseValid
  io.response.bits := responseBits

  private val fullResult = WireDefault(0.U(xlen.W))
  private val fullShamt = io.request.bits.rhs(log2Ceil(xlen) - 1, 0)
  switch(io.request.bits.aluOp) {
    is(AluOp.Add)  { fullResult := io.request.bits.lhs + io.request.bits.rhs }
    is(AluOp.Sub)  { fullResult := io.request.bits.lhs - io.request.bits.rhs }
    is(AluOp.Sll)  { fullResult := io.request.bits.lhs << fullShamt }
    is(AluOp.Slt)  { fullResult := (io.request.bits.lhs.asSInt < io.request.bits.rhs.asSInt).asUInt }
    is(AluOp.Sltu) { fullResult := (io.request.bits.lhs < io.request.bits.rhs).asUInt }
    is(AluOp.Xor)  { fullResult := io.request.bits.lhs ^ io.request.bits.rhs }
    is(AluOp.Srl)  { fullResult := io.request.bits.lhs >> fullShamt }
    is(AluOp.Sra)  { fullResult := (io.request.bits.lhs.asSInt >> fullShamt).asUInt }
    is(AluOp.Or)   { fullResult := io.request.bits.lhs | io.request.bits.rhs }
    is(AluOp.And)  { fullResult := io.request.bits.lhs & io.request.bits.rhs }
  }

  private val result = if (xlen == 64) {
    val lhs32 = io.request.bits.lhs(31, 0)
    val rhs32 = io.request.bits.rhs(31, 0)
    val shamt32 = rhs32(4, 0)
    val result32 = WireDefault(0.U(32.W))
    switch(io.request.bits.aluOp) {
      is(AluOp.Add)  { result32 := lhs32 + rhs32 }
      is(AluOp.Sub)  { result32 := lhs32 - rhs32 }
      is(AluOp.Sll)  { result32 := lhs32 << shamt32 }
      is(AluOp.Slt)  { result32 := (lhs32.asSInt < rhs32.asSInt).asUInt }
      is(AluOp.Sltu) { result32 := (lhs32 < rhs32).asUInt }
      is(AluOp.Xor)  { result32 := lhs32 ^ rhs32 }
      is(AluOp.Srl)  { result32 := lhs32 >> shamt32 }
      is(AluOp.Sra)  { result32 := (lhs32.asSInt >> shamt32).asUInt }
      is(AluOp.Or)   { result32 := lhs32 | rhs32 }
      is(AluOp.And)  { result32 := lhs32 & rhs32 }
    }
    Mux(io.request.bits.wordOp, Cat(Fill(32, result32(31)), result32), fullResult)
  } else {
    when(io.request.valid) {
      assert(!io.request.bits.wordOp, "RV32 execution cannot consume an RV64 word operation")
    }
    fullResult
  }

  when(io.request.fire) {
    responseValid := true.B
    responseBits := 0.U.asTypeOf(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    responseBits.robToken := io.request.bits.robToken
    responseBits.producerTag := io.request.bits.producerTag
    responseBits.valueRef := io.request.bits.valueRef
    responseBits.hasValue := true.B
    responseBits.value := result
  }.elsewhen(io.response.fire) {
    responseValid := false.B
  }
}

/** One-entry registered multiplier. DIV/REM deliberately do not enter this unit. */
class V2MulUnit(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"multiply unit XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val response = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  })

  private val responseValid = RegInit(false.B)
  private val responseBits = Reg(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  io.request.ready := !responseValid || io.response.ready
  io.response.valid := responseValid
  io.response.bits := responseBits

  private val signedLhs = Cat(io.request.bits.lhs(xlen - 1), io.request.bits.lhs).asSInt
  private val signedRhs = Cat(io.request.bits.rhs(xlen - 1), io.request.bits.rhs).asSInt
  private val unsignedLhs = Cat(0.U(1.W), io.request.bits.lhs).asSInt
  private val unsignedRhs = Cat(0.U(1.W), io.request.bits.rhs).asSInt
  private val productSS = (signedLhs * signedRhs).asUInt
  private val productSU = (signedLhs * unsignedRhs).asUInt
  private val productUU = (unsignedLhs * unsignedRhs).asUInt

  private val fullResult = WireDefault(productUU(xlen - 1, 0))
  switch(io.request.bits.aluOp) {
    is(AluOp.Mul)    { fullResult := productUU(xlen - 1, 0) }
    is(AluOp.Mulh)   { fullResult := productSS(2 * xlen - 1, xlen) }
    is(AluOp.Mulhsu) { fullResult := productSU(2 * xlen - 1, xlen) }
    is(AluOp.Mulhu)  { fullResult := productUU(2 * xlen - 1, xlen) }
  }

  private val result = if (xlen == 64) {
    val product32 = io.request.bits.lhs(31, 0) * io.request.bits.rhs(31, 0)
    val wordResult = Cat(Fill(32, product32(31)), product32(31, 0))
    Mux(io.request.bits.wordOp, wordResult, fullResult)
  } else {
    when(io.request.valid) {
      assert(!io.request.bits.wordOp, "RV32 execution cannot consume an RV64 word multiply")
    }
    fullResult
  }

  when(io.request.fire) {
    responseValid := true.B
    responseBits := 0.U.asTypeOf(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    responseBits.robToken := io.request.bits.robToken
    responseBits.producerTag := io.request.bits.producerTag
    responseBits.valueRef := io.request.bits.valueRef
    responseBits.hasValue := true.B
    responseBits.value := result
  }.elsewhen(io.response.fire) {
    responseValid := false.B
  }
}

/**
  * Restoring iterative divider/remainder unit.
  *
  * One quotient bit is produced per cycle. Architectural divide-by-zero falls
  * out of the restoring recurrence except for signed quotient polarity, which
  * is explicitly overridden to all ones. Signed overflow naturally produces
  * the RISC-V minimum-integer quotient and zero remainder.
  */
class V2IterativeDivider(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"divider XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val CountBits = log2Ceil(xlen + 1)

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val response = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  })

  private val busy = RegInit(false.B)
  private val responseValid = RegInit(false.B)
  private val responseBits = Reg(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  private val requestBits = Reg(new ExecutionRequest(xlen, IdentityBits, GenerationBits))
  private val quotient = Reg(UInt(xlen.W))
  private val divisor = Reg(UInt(xlen.W))
  private val remainder = Reg(UInt((xlen + 1).W))
  private val count = Reg(UInt(CountBits.W))
  private val negateQuotient = Reg(Bool())
  private val negateRemainder = Reg(Bool())
  private val chooseRemainder = Reg(Bool())
  private val divideByZero = Reg(Bool())

  io.request.ready := !busy && !responseValid
  io.response.valid := responseValid
  io.response.bits := responseBits

  private val signedOperation =
    io.request.bits.aluOp === AluOp.Div || io.request.bits.aluOp === AluOp.Rem
  private val remainderOperation =
    io.request.bits.aluOp === AluOp.Rem || io.request.bits.aluOp === AluOp.Remu

  private val normalizedLhs = Wire(UInt(xlen.W))
  private val normalizedRhs = Wire(UInt(xlen.W))
  if (xlen == 64) {
    val signedWordLhs = Cat(Fill(32, io.request.bits.lhs(31)), io.request.bits.lhs(31, 0))
    val signedWordRhs = Cat(Fill(32, io.request.bits.rhs(31)), io.request.bits.rhs(31, 0))
    val unsignedWordLhs = Cat(0.U(32.W), io.request.bits.lhs(31, 0))
    val unsignedWordRhs = Cat(0.U(32.W), io.request.bits.rhs(31, 0))
    normalizedLhs := Mux(
      io.request.bits.wordOp,
      Mux(signedOperation, signedWordLhs, unsignedWordLhs),
      io.request.bits.lhs
    )
    normalizedRhs := Mux(
      io.request.bits.wordOp,
      Mux(signedOperation, signedWordRhs, unsignedWordRhs),
      io.request.bits.rhs
    )
  } else {
    normalizedLhs := io.request.bits.lhs
    normalizedRhs := io.request.bits.rhs
    when(io.request.valid) {
      assert(!io.request.bits.wordOp, "RV32 execution cannot consume an RV64 word divide")
    }
  }

  private val lhsNegative = signedOperation && normalizedLhs(xlen - 1)
  private val rhsNegative = signedOperation && normalizedRhs(xlen - 1)
  private val lhsMagnitude = Mux(lhsNegative, (~normalizedLhs).asUInt + 1.U, normalizedLhs)
  private val rhsMagnitude = Mux(rhsNegative, (~normalizedRhs).asUInt + 1.U, normalizedRhs)

  when(io.request.fire) {
    requestBits := io.request.bits
    quotient := lhsMagnitude
    divisor := rhsMagnitude
    remainder := 0.U
    count := 0.U
    negateQuotient := lhsNegative ^ rhsNegative
    negateRemainder := lhsNegative
    chooseRemainder := remainderOperation
    divideByZero := normalizedRhs === 0.U
    busy := true.B
  }

  when(busy) {
    val shiftedRemainder = Cat(remainder(xlen - 1, 0), quotient(xlen - 1))
    val shiftedQuotient = Cat(quotient(xlen - 2, 0), 0.U(1.W))
    val extendedDivisor = Cat(0.U(1.W), divisor)
    val subtract = shiftedRemainder >= extendedDivisor
    val nextRemainder = Mux(subtract, shiftedRemainder - extendedDivisor, shiftedRemainder)
    val nextQuotient = Mux(subtract, shiftedQuotient | 1.U, shiftedQuotient)

    when(count === (xlen - 1).U) {
      val unsignedRemainder = nextRemainder(xlen - 1, 0)
      val signedQuotient = Mux(
        negateQuotient,
        (~nextQuotient).asUInt + 1.U,
        nextQuotient
      )
      val signedRemainder = Mux(
        negateRemainder,
        (~unsignedRemainder).asUInt + 1.U,
        unsignedRemainder
      )
      val quotientResult = Mux(divideByZero, Fill(xlen, 1.U(1.W)), signedQuotient)
      val selectedResult = Mux(chooseRemainder, signedRemainder, quotientResult)
      val architecturalResult = if (xlen == 64) {
        Mux(
          requestBits.wordOp,
          Cat(Fill(32, selectedResult(31)), selectedResult(31, 0)),
          selectedResult
        )
      } else selectedResult

      responseBits := 0.U.asTypeOf(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
      responseBits.robToken := requestBits.robToken
      responseBits.producerTag := requestBits.producerTag
      responseBits.valueRef := requestBits.valueRef
      responseBits.hasValue := true.B
      responseBits.value := architecturalResult
      responseValid := true.B
      busy := false.B
    }.otherwise {
      remainder := nextRemainder
      quotient := nextQuotient
      count := count + 1.U
    }
  }

  when(io.response.fire) {
    responseValid := false.B
  }
}

/**
  * One-entry branch/jump unit with P8 same-cycle response flow-through.
  *
  * A fresh branch result may leave in the same cycle as request acceptance. If
  * the downstream completion fabric backpressures that cycle, the exact result
  * is captured into a held register and remains stable until accepted. This
  * removes the deterministic response register bubble without changing branch
  * issue policy, ROB ownership, recovery validation or completion bandwidth.
  */
class V2BranchUnit(val xlen: Int, val hasCompressed: Boolean) extends Module {
  require(xlen == 32 || xlen == 64, s"branch unit XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val response = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  })

  private val heldValid = RegInit(false.B)
  private val heldBits = Reg(new ExecutionResponse(xlen, IdentityBits, GenerationBits))

  private val branchCondition = WireDefault(false.B)
  switch(io.request.bits.branchType) {
    is(BranchType.Eq)  { branchCondition := io.request.bits.lhs === io.request.bits.rhs }
    is(BranchType.Ne)  { branchCondition := io.request.bits.lhs =/= io.request.bits.rhs }
    is(BranchType.Lt)  { branchCondition := io.request.bits.lhs.asSInt < io.request.bits.rhs.asSInt }
    is(BranchType.Ge)  { branchCondition := io.request.bits.lhs.asSInt >= io.request.bits.rhs.asSInt }
    is(BranchType.Ltu) { branchCondition := io.request.bits.lhs < io.request.bits.rhs }
    is(BranchType.Geu) { branchCondition := io.request.bits.lhs >= io.request.bits.rhs }
  }

  private val branchValid = io.request.bits.controlFlowKind =/= ControlFlowKind.None
  private val taken = Mux(
    io.request.bits.controlFlowKind === ControlFlowKind.Conditional,
    branchCondition,
    branchValid
  )
  private val directTarget = io.request.bits.pc + io.request.bits.immediate
  private val jalrMask = ((BigInt(1) << xlen) - 2).U(xlen.W)
  private val indirectTarget = (io.request.bits.lhs + io.request.bits.immediate) & jalrMask
  private val target = Mux(
    io.request.bits.controlFlowKind === ControlFlowKind.IndirectJump,
    indirectTarget,
    directTarget
  )
  private val alignmentMask = (if (hasCompressed) BigInt(1) else BigInt(3)).U(xlen.W)
  private val misaligned = branchValid && taken && ((target & alignmentMask) =/= 0.U)
  private val producesLink =
    io.request.bits.controlFlowKind === ControlFlowKind.DirectJump ||
      io.request.bits.controlFlowKind === ControlFlowKind.IndirectJump

  private val freshBits = Wire(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  freshBits := 0.U.asTypeOf(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  freshBits.robToken := io.request.bits.robToken
  freshBits.producerTag := io.request.bits.producerTag
  freshBits.valueRef := io.request.bits.valueRef
  freshBits.hasValue := producesLink
  freshBits.value := io.request.bits.pc + io.request.bits.instBytes
  freshBits.branchValid := branchValid
  freshBits.branchTaken := taken
  freshBits.branchTarget := target
  freshBits.exception.valid := misaligned
  freshBits.exception.cause := MachineExceptionCode.InstructionAddressMisaligned.U(xlen.W)
  freshBits.exception.value := target

  // With no held result, the branch response is a pure function of request.valid
  // and request.bits. request.ready deliberately does not feed response.valid,
  // avoiding a ready/valid combinational loop through the response arbiters.
  io.response.valid := heldValid || (!heldValid && io.request.valid)
  io.response.bits := Mux(heldValid, heldBits, freshBits)

  // A held response blocks a replacement request unless that response is being
  // accepted on this cycle; in that case a new request may replace it in the
  // one-entry hold register without dropping throughput.
  io.request.ready := !heldValid || io.response.ready

  when(heldValid) {
    when(io.response.ready) {
      when(io.request.fire) {
        heldBits := freshBits
        heldValid := true.B
      }.otherwise {
        heldValid := false.B
      }
    }
  }.otherwise {
    when(io.request.fire && !io.response.ready) {
      heldBits := freshBits
      heldValid := true.B
    }
  }
}

/**
  * F3 execution cluster. The first issue policy permits only one live issued
  * head, but each unit already uses Decoupled request/response contracts so a
  * later ready-select policy does not require an execution-interface rewrite.
  * A8 uses round-robin response arbitration so the future selective-issue path
  * cannot starve a held long-latency result behind short-latency completions.
  */
class TinyExecutionCluster(val xlen: Int, val hasCompressed: Boolean) extends Module {
  require(xlen == 32 || xlen == 64, s"execution cluster XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val response = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  })

  val integer = Module(new V2IntegerUnit(xlen))
  val branch = Module(new V2BranchUnit(xlen, hasCompressed))
  val multiply = Module(new V2MulUnit(xlen))
  val divide = Module(new V2IterativeDivider(xlen))

  private val mulOperation =
    io.request.bits.aluOp === AluOp.Mul ||
      io.request.bits.aluOp === AluOp.Mulh ||
      io.request.bits.aluOp === AluOp.Mulhsu ||
      io.request.bits.aluOp === AluOp.Mulhu
  private val divOperation =
    io.request.bits.aluOp === AluOp.Div ||
      io.request.bits.aluOp === AluOp.Divu ||
      io.request.bits.aluOp === AluOp.Rem ||
      io.request.bits.aluOp === AluOp.Remu

  private val routeInteger = io.request.bits.executionClass === ExecutionClass.Integer
  private val routeBranch = io.request.bits.executionClass === ExecutionClass.Branch
  private val routeMultiply = io.request.bits.executionClass === ExecutionClass.MulDiv && mulOperation
  private val routeDivide = io.request.bits.executionClass === ExecutionClass.MulDiv && divOperation

  integer.io.request.valid := io.request.valid && routeInteger
  integer.io.request.bits := io.request.bits
  branch.io.request.valid := io.request.valid && routeBranch
  branch.io.request.bits := io.request.bits
  multiply.io.request.valid := io.request.valid && routeMultiply
  multiply.io.request.bits := io.request.bits
  divide.io.request.valid := io.request.valid && routeDivide
  divide.io.request.bits := io.request.bits

  io.request.ready := MuxCase(false.B, Seq(
    routeInteger -> integer.io.request.ready,
    routeBranch -> branch.io.request.ready,
    routeMultiply -> multiply.io.request.ready,
    routeDivide -> divide.io.request.ready
  ))

  val responses = Module(new RRArbiter(new ExecutionResponse(xlen, IdentityBits, GenerationBits), 4))
  responses.io.in(0) <> integer.io.response
  responses.io.in(1) <> branch.io.response
  responses.io.in(2) <> multiply.io.response
  responses.io.in(3) <> divide.io.response
  io.response <> responses.io.out
}

/** Thin F3 composition: frozen F2 substrate + oldest-only issue + execution. */
class TinyExecutionBackend(val xlen: Int, val hasCompressed: Boolean = false) extends Module {
  require(xlen == 32 || xlen == 64, s"v2 F3 backend XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val commit = Output(new CommitTrace(xlen = xlen))
    val occupancy = Output(UInt(log2Ceil(TinyRobGeometry.Entries + 1).W))
  })

  val dependencyBackend = Module(new TinyDependencyBackend(xlen))
  val issue = Module(new TinyOldestIssue(xlen))
  val execution = Module(new TinyExecutionCluster(xlen, hasCompressed))

  dependencyBackend.io.dispatch.valid := io.dispatch.valid
  dependencyBackend.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := dependencyBackend.io.dispatch.ready
  io.allocated := dependencyBackend.io.allocated
  io.commit := dependencyBackend.io.commit
  io.occupancy := dependencyBackend.io.occupancy

  issue.io.head := dependencyBackend.io.head
  issue.io.headDependenciesValid := dependencyBackend.io.headDependenciesValid
  issue.io.headRs1 := dependencyBackend.io.headRs1
  issue.io.headRs2 := dependencyBackend.io.headRs2
  issue.io.headOperandsReady := dependencyBackend.io.headOperandsReady
  execution.io.request <> issue.io.request

  dependencyBackend.io.completion.valid := execution.io.response.valid
  dependencyBackend.io.completion.bits := execution.io.response.bits
  execution.io.response.ready := true.B
}
