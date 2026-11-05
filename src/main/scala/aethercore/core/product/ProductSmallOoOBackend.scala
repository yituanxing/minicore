package aethercore.core.product

import chisel3._
import chisel3.util._
import aethercore.common.{AluOp, AtomicOp, BranchType, TrapInfo}
import aethercore.core.semantic._
import aethercore.common.MemSize

/** Architectural destination register-file domain, independent of the ROB ID. */
object ProductRegisterFile extends ChiselEnum {
  val Integer, Floating = Value
}

/** Exact physical operation observed only when its ROB lifetime retires.
  * Store data/mask use the AetherMem address-relative byte-lane convention.
  */
class ProductMemoryTrace(val paddrBits: Int) extends Bundle {
  val valid = Bool()
  val write = Bool()
  val paddr = UInt(paddrBits.W)
  val wdata = UInt(64.W)
  val wmask = UInt(8.W)
}

/** Lifetime identity is owned by ROB and never inferred from a PRF address. */
class ProductRobId(val indexBits: Int, val generationBits: Int) extends Bundle {
  val index = UInt(indexBits.W)
  val generation = UInt(generationBits.W)
}

/** Physical value identity is independent of ROB lifetime. */
class ProductPhysicalTag(val physicalBits: Int, val generationBits: Int) extends Bundle {
  val index = UInt(physicalBits.W)
  val generation = UInt(generationBits.W)
}

class ProductDispatch(val xlen: Int) extends Bundle {
  val decoded = new DecodedInstruction(xlen)
  val predictedNextPc = UInt(xlen.W)
}

class ProductIssue(val xlen: Int, val indexBits: Int, val physicalBits: Int, val generationBits: Int)
  extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val destinationFile = ProductRegisterFile()
  val hasDestination = Bool()
  val storeData = UInt(xlen.W)
  val aluOp = AluOp()
  val wordOp = Bool()
  val memoryKind = MemoryOperationKind()
  val memorySize = MemSize()
  val memoryUnsigned = Bool()
  val atomicOp = AtomicOp()
  val controlFlow = ControlFlowKind()
  val branchType = BranchType()
  val lhs = UInt(xlen.W)
  val rhs = UInt(xlen.W)
  val immediate = UInt(xlen.W)
  val pc = UInt(xlen.W)
  val instBytes = UInt(3.W)
}

/** One FP uOp uses the *same* ROB lifetime, but owns independent FPR sources.
  * The optional port does not make the Small backend multi-issue.
  */
class ProductFpIssue(val xlen: Int, val indexBits: Int, val physicalBits: Int,
                     val generationBits: Int) extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val destinationFile = ProductRegisterFile()
  val hasDestination = Bool()
  val floating = new DecodedFloatingOperation
  val fpLhs = UInt(xlen.W)
  val fpRhs = UInt(xlen.W)
  val fpRs3 = UInt(xlen.W)
  val intLhs = UInt(xlen.W)
  val pc = UInt(xlen.W)
  val rawInst = UInt(32.W)
  val instBytes = UInt(3.W)
}

class ProductCompletion(val xlen: Int, val indexBits: Int, val physicalBits: Int,
                        val generationBits: Int, val paddrBits: Int = 56)
  extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val destinationFile = ProductRegisterFile()
  val hasValue = Bool()
  val value = UInt(xlen.W)
  val fflags = UInt(5.W)
  val fpStateDirty = Bool()
  val branchValid = Bool()
  val nextPc = UInt(xlen.W)
  val exception = new TrapInfo(xlen)
  // Evaluated once at exact ROB head; only the retirement owner may apply it.
  val privileged = new PendingPrivilegedEffect(xlen)
  val memoryTrace = new ProductMemoryTrace(paddrBits)
}

/** An exact-head SYSTEM uOp, with architectural sources read from its original
  * pre-rename PRF mappings. The CSR file itself is owned above this backend.
  */
class ProductSystemHead(val xlen: Int, val indexBits: Int, val physicalBits: Int,
                        val generationBits: Int) extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val hasDestination = Bool()
  val decoded = new DecodedInstruction(xlen)
  val rs1Value = UInt(xlen.W)
  val rs2Value = UInt(xlen.W)
}

class ProductRetirement(val xlen: Int, val indexBits: Int, val physicalBits: Int,
                        val generationBits: Int, val paddrBits: Int = 56)
  extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val rawInst = UInt(32.W)
  val instBytes = UInt(3.W)
  val rd = UInt(5.W)
  val destinationFile = ProductRegisterFile()
  val wroteRegister = Bool()
  val wroteFloatingRegister = Bool()
  val value = UInt(xlen.W)
  val fflags = UInt(5.W)
  val fpDirty = Bool()
  val exception = new TrapInfo(xlen)
  val privileged = new PendingPrivilegedEffect(xlen)
  val memoryTrace = new ProductMemoryTrace(paddrBits)
}

class ProductRedirect(val xlen: Int, val indexBits: Int, val generationBits: Int)
  extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val target = UInt(xlen.W)
}

private class ProductRobSlot(
  val xlen: Int, val indexBits: Int, val physicalBits: Int, val generationBits: Int,
  val paddrBits: Int
) extends Bundle {
  val valid = Bool()
  val complete = Bool()
  val tokenGeneration = UInt(generationBits.W)
  // ROB owns retirement and ordering only.  Execution operands live in the
  // IQ; the one serialized SYSTEM payload is stored once, outside the ROB.
  // Keeping a full decoded uOp per slot duplicates wide immediates, ALU and
  // memory control across every ROB entry without a retirement consumer.
  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val rawInst = UInt(32.W)
  val instBytes = UInt(3.W)
  val rd = UInt(5.W)
  val destinationFile = ProductRegisterFile()
  val controlFlowKind = ControlFlowKind()
  val memoryKind = MemoryOperationKind()
  val isSystem = Bool()
  val predictedNextPc = UInt(xlen.W)
  val writes = Bool()
  val oldPhysical = UInt(physicalBits.W)
  val newPhysical = new ProductPhysicalTag(physicalBits, generationBits)
  val result = UInt(xlen.W)
  val fflags = UInt(5.W)
  val fpStateDirty = Bool()
  val exception = new TrapInfo(xlen)
  val privileged = new PendingPrivilegedEffect(xlen)
  val memoryTrace = new ProductMemoryTrace(paddrBits)
  val systemIssued = Bool()
}

/** At most one SYSTEM uOp can be live: its ROB lifetime prevents any younger
  * dispatch.  Source tags and architectural CSR operands therefore need one
  * generation-qualified sidecar, not N copies inside the ROB.
  */
private class ProductSystemPayload(val indexBits: Int, val generationBits: Int,
                                   val physicalBits: Int) extends Bundle {
  val valid = Bool()
  val token = new ProductRobId(indexBits, generationBits)
  val rs1Physical = UInt(physicalBits.W)
  val rs2Physical = UInt(physicalBits.W)
  val rs1 = UInt(5.W)
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val system = new DecodedSystemOperation
}

private class ProductIqSlot(
  val xlen: Int, val indexBits: Int, val physicalBits: Int,
  val generationBits: Int, val enableFloating: Boolean
) extends Bundle {
  val valid = Bool()
  val token = new ProductRobId(indexBits, generationBits)
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val destinationFile = ProductRegisterFile()
  val writes = Bool()
  val src1 = UInt(physicalBits.W)
  val src2 = UInt(physicalBits.W)
  // A software target without F/D elaborates exactly the integer issue
  // queue: no per-entry FP tags, dependencies or wide decoded FP payload.
  val fpSrc1 = if (enableFloating) Some(UInt(physicalBits.W)) else None
  val fpSrc2 = if (enableFloating) Some(UInt(physicalBits.W)) else None
  val fpSrc3 = if (enableFloating) Some(UInt(physicalBits.W)) else None
  val useRs1 = Bool()
  val useRs2 = Bool()
  val useFpRs1 = if (enableFloating) Some(Bool()) else None
  val useFpRs2 = if (enableFloating) Some(Bool()) else None
  val useFpRs3 = if (enableFloating) Some(Bool()) else None
  val floating = if (enableFloating) Some(new DecodedFloatingOperation) else None
  val lhsSource = OperandSourceKind()
  val rhsSource = OperandSourceKind()
  val aluOp = AluOp()
  val wordOp = Bool()
  val memoryKind = MemoryOperationKind()
  val memorySize = MemSize()
  val memoryUnsigned = Bool()
  val atomicOp = AtomicOp()
  val controlFlow = ControlFlowKind()
  val branchType = BranchType()
  val immediate = UInt(xlen.W)
  val pc = UInt(xlen.W)
  val rawInst = UInt(32.W)
  val instBytes = UInt(3.W)
}

/** First real SmallOoO *integer/branch back end*.
  *
  * One dispatch, one issue, one precise retirement; IQ4, ROB8, PRF48 by profile.
  * An independent execution cluster must implement ProductIssue -> ProductCompletion.
  * Load/Store/Atomic enter a reserved LSQ via ProductSmallMemoryCore.
  * An optional exact-head SYSTEM lane computes pending privileged effects,
  * but the CSR owner and fetch/interrupt/F/D paths are external or absent.
  * This is not a complete CPU and cannot claim Linux or Debian support.
  *
  * Arbitrary-age branch recovery uses youngest-first ROB undo, one slot/cycle.
  * While recovering, allocate/issue/retire stop. FU completions are
  * backpressured until undo ends; then stale lifetimes are rejected. Every ROB and physical destination reuse changes a
  * generation; external physical MemTxnId ownership is in ProductPhysicalLsq.
  */
class ProductSmallOoOBackend(val geometry: ProductCoreGeometry,
                             val allowSystem: Boolean = false,
                             val paddrBits: Int = 56,
                             val enableFloating: Boolean = false) extends Module {
  require(geometry.decodeWidth == 1 && geometry.dispatchWidth == 1 &&
    geometry.issueWidth == 1 && geometry.retireWidth == 1,
    "this first hardware slice implements the Small one-wide backend only")
  require(geometry.integerReadPorts == 2 && geometry.integerWritePorts == 1 &&
    geometry.floatingReadPorts == 3 && geometry.floatingWritePorts == 1,
    "Product SmallOoO must not silently ignore requested physical register ports")
  private val Xlen = 64
  private val N = geometry.robEntries
  private val Q = geometry.iqEntries
  private val P = geometry.integerPhysicalRegisters
  private val FpP = geometry.floatingPhysicalRegisters
  private val I = log2Ceil(N)
  private val F = log2Ceil(P)
  private val G = 16
  require(!enableFloating || log2Ceil(FpP) <= F,
    "floating PRF index must fit the Product physical-tag width")

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new ProductDispatch(Xlen)))
    val issue = Decoupled(new ProductIssue(Xlen, I, F, G))
    val fpIssue = if (enableFloating) Some(Decoupled(new ProductFpIssue(Xlen, I, F, G))) else None
    // FU state is observed independently from issue.bits; do not close a
    // combinational loop through request.ready or completion arbitration.
    val integerAvailable = Input(Bool())
    val mAvailable = Input(Bool())
    val fpAvailable = if (enableFloating) Some(Input(Bool())) else None
    val memoryAvailable = Input(Bool())
    val memoryReserve = Decoupled(new ProductMemReservation(I, F, G))
    val memoryHead = Valid(new ProductRobId(I, G))
    val memoryKill = Valid(new ProductRobId(I, G))
    // Exact victim token, not a global redirect pulse. A mispredicted branch
    // must not cancel an older long-latency floating-point operation.
    val fpKill = if (enableFloating) Some(Valid(new ProductRobId(I, G))) else None
    val systemHead = Decoupled(new ProductSystemHead(Xlen, I, F, G))
    val completion = Flipped(Decoupled(new ProductCompletion(Xlen, I, F, G, paddrBits)))
    val retire = Decoupled(new ProductRetirement(Xlen, I, F, G, paddrBits))
    val redirect = Valid(new ProductRedirect(Xlen, I, G))
    val trap = Valid(new ProductRetirement(Xlen, I, F, G, paddrBits))
    val occupancy = Output(UInt(log2Ceil(N + 1).W))
    val recovering = Output(Bool())
    val acceptedCompletion = Output(Bool())
    val outOfOrderCompletion = Output(Bool())
    val staleCompletion = Output(Bool())
    val unsupported = Output(Bool())
  })

  private val rob = RegInit(VecInit(Seq.fill(N)(
    0.U.asTypeOf(new ProductRobSlot(Xlen, I, F, G, paddrBits)))))
  private val systemPayload = RegInit(0.U.asTypeOf(new ProductSystemPayload(I, G, F)))
  private val iq = RegInit(VecInit(Seq.fill(Q)(
    0.U.asTypeOf(new ProductIqSlot(Xlen, I, F, G, enableFloating)))))
  private val generations = RegInit(VecInit(Seq.fill(N)(0.U(G.W))))
  private val physicalGenerations = RegInit(VecInit(Seq.fill(P)(0.U(G.W))))
  private val speculativeRat = RegInit(VecInit((0 until 32).map(_.U(F.W))))
  private val committedRat = RegInit(VecInit((0 until 32).map(_.U(F.W))))
  private val free = RegInit(VecInit((0 until P).map(i => (i >= 32).B)))
  private val ready = RegInit(VecInit((0 until P).map(i => (i < 32).B)))
  // Value RAM is shared by integer IQ issue and exact-head SYSTEM reads.
  // Reset/rename readiness stays here; the RAM only owns value and whether a
  // physical lifetime has ever received its exact completion.
  private val prf = Module(new ProductPhysicalValueStore(
    entries = P, readPorts = 2, immutableZero = true))
  // There is exactly one architectural f0..f31 mapping, including f0 (which
  // is writable, unlike x0). FP state is absent from integer-only RTLs.
  private val fpSpeculativeRat = if (enableFloating)
    Some(RegInit(VecInit((0 until 32).map(_.U(F.W))))) else None
  private val fpCommittedRat = if (enableFloating)
    Some(RegInit(VecInit((0 until 32).map(_.U(F.W))))) else None
  private val fpFree = if (enableFloating)
    Some(RegInit(VecInit((0 until FpP).map(i => (i >= 32).B)))) else None
  private val fpReady = if (enableFloating)
    Some(RegInit(VecInit((0 until FpP).map(i => (i < 32).B)))) else None
  private val fpPhysicalGenerations = if (enableFloating)
    Some(RegInit(VecInit(Seq.fill(FpP)(0.U(G.W))))) else None
  private val fpPrf = if (enableFloating)
    Some(Module(new ProductPhysicalValueStore(
      entries = FpP, readPorts = 3))) else None
  private val head = RegInit(0.U(I.W))
  private val tail = RegInit(0.U(I.W))
  private val count = RegInit(0.U(log2Ceil(N + 1).W))
  private val recovering = RegInit(false.B)
  private val flushingTrap = RegInit(false.B)
  private val recoveryCut = Reg(UInt(I.W))
  // FP div/sqrt readiness may depend on the selected operation even while
  // the FP lane advertises resource availability. Once an IQ entry has been
  // offered, lock its complete operands and token until the FU accepts it.
  // Other IQ entries can become ready during that stall; they cannot replace
  // a Decoupled offer already visible at the issue boundary.
  private val issueHolding = RegInit(false.B)
  private val heldIqIndex = Reg(UInt(math.max(1, log2Ceil(Q)).W))
  private val heldIssue = Reg(new ProductIssue(Xlen, I, F, G))
  private val heldFloating = if (enableFloating) Some(Reg(Bool())) else None
  private val heldFpIssue = if (enableFloating)
    Some(Reg(new ProductFpIssue(Xlen, I, F, G))) else None

  io.occupancy := count
  io.recovering := recovering
  io.unsupported := false.B
  io.acceptedCompletion := false.B
  io.outOfOrderCompletion := false.B
  io.staleCompletion := false.B
  io.redirect := 0.U.asTypeOf(io.redirect)
  io.trap := 0.U.asTypeOf(io.trap)
  io.memoryReserve.valid := false.B
  io.memoryReserve.bits := 0.U.asTypeOf(io.memoryReserve.bits)
  io.memoryHead.valid := false.B
  io.memoryHead.bits := 0.U.asTypeOf(io.memoryHead.bits)
  io.memoryKill.valid := false.B
  io.memoryKill.bits := 0.U.asTypeOf(io.memoryKill.bits)
  if (enableFloating) {
    io.fpKill.get.valid := false.B
    io.fpKill.get.bits := 0.U.asTypeOf(io.fpKill.get.bits)
  }
  io.systemHead.valid := false.B
  io.systemHead.bits := 0.U.asTypeOf(io.systemHead.bits)

  val dispatchDecoded = io.dispatch.bits.decoded
  val writesInteger = dispatchDecoded.writesRd && dispatchDecoded.rd =/= 0.U
  val floatingDispatch = dispatchDecoded.floating.kind =/= FloatingOperationKind.None
  val writesFloating = enableFloating.B && dispatchDecoded.floating.writesRd
  val writes = writesInteger || writesFloating
  val destinationFile = Mux(writesFloating,
    ProductRegisterFile.Floating, ProductRegisterFile.Integer)
  val architecturalRd = Mux(writesFloating, dispatchDecoded.floating.rd,
    dispatchDecoded.rd)
  val exceptionOnly = dispatchDecoded.exception.valid
  val memoryDispatch = dispatchDecoded.memory.kind === MemoryOperationKind.Load ||
    dispatchDecoded.memory.kind === MemoryOperationKind.Store ||
    dispatchDecoded.memory.kind === MemoryOperationKind.Atomic
  val atomicDispatch = dispatchDecoded.memory.kind === MemoryOperationKind.Atomic
  val systemDispatch = dispatchDecoded.system.kind =/= SystemOperationKind.None
  // All atomic memory operations execute only at the exact ROB head in the
  // LSQ, a conservative SerializeBoth implementation of aq/rl semantics.
  // System/FENCE operations still require a separate privileged owner.
  val permittedOrdering = dispatchDecoded.ordering === OrderingClass.Normal ||
    (atomicDispatch && (dispatchDecoded.ordering === OrderingClass.SerializeBefore ||
      dispatchDecoded.ordering === OrderingClass.SerializeAfter ||
      dispatchDecoded.ordering === OrderingClass.SerializeBoth))
  val supported = Mux(systemDispatch,
    allowSystem.B && dispatchDecoded.memory.kind === MemoryOperationKind.None,
    (dispatchDecoded.memory.kind === MemoryOperationKind.None || memoryDispatch) &&
      permittedOrdering && (enableFloating.B || !floatingDispatch))
  io.unsupported := io.dispatch.valid && !supported && !exceptionOnly

  val integerFreePhysicalMask = free.asUInt
  val floatingFreePhysicalMask = if (enableFloating) fpFree.get.asUInt else 0.U(P.W)
  val freePhysicalMask = Mux(writesFloating,
    floatingFreePhysicalMask, integerFreePhysicalMask)
  val freeIqMask = VecInit(iq.map(s => !s.valid)).asUInt
  val hasPhysical = freePhysicalMask.orR
  val hasIq = freeIqMask.orR
  val chosenPhysical = PriorityEncoder(freePhysicalMask)
  val chosenGeneration = Mux(writesFloating,
    (if (enableFloating) fpPhysicalGenerations.get(chosenPhysical) else 0.U(G.W)) + 1.U,
    physicalGenerations(chosenPhysical) + 1.U)
  val chosenIq = PriorityEncoder(freeIqMask)
  private val headEntry = rob(head)
  // A serializing uOp is a physical ROB ownership boundary, not just a fetch
  // hint. Blocking younger dispatch also protects SATP/PMP context snapshots
  // used by the independently running data resolver.
  private val serializedLive = rob.map(s => s.valid &&
    s.isSystem).reduce(_ || _)

  // Completion matches all lifetime fields. Completion is always drained;
  // stale or killed responses never mutate a new ROB slot or destination.
  val c = io.completion.bits
  private val completionEntry = rob(c.token.index)
  val completionPhysicalGeneration = if (enableFloating) {
    Mux(c.destinationFile === ProductRegisterFile.Floating,
      fpPhysicalGenerations.get(c.destination.index), physicalGenerations(c.destination.index))
  } else physicalGenerations(c.destination.index)
  val sameLifetime = completionEntry.valid &&
    !completionEntry.complete &&
    completionEntry.tokenGeneration === c.token.generation &&
    completionEntry.writes === c.hasValue &&
    (!completionEntry.writes ||
      (completionEntry.destinationFile === c.destinationFile &&
        completionEntry.newPhysical.index === c.destination.index &&
        completionEntry.newPhysical.generation === c.destination.generation &&
        completionPhysicalGeneration === c.destination.generation))
  val accepted = io.completion.valid && !recovering && sameLifetime
  val branchMismatch = accepted && c.branchValid &&
    completionEntry.controlFlowKind =/= ControlFlowKind.None &&
    !c.exception.valid &&
    c.nextPc =/= completionEntry.predictedNextPc
  io.completion.ready := !recovering
  io.acceptedCompletion := accepted
  io.staleCompletion := io.completion.fire && !sameLifetime
  io.outOfOrderCompletion := accepted && c.token.index =/= head

  io.redirect.valid := branchMismatch
  io.redirect.bits.token := c.token
  io.redirect.bits.target := c.nextPc

  // Dispatch and retirement are blocked on any redirect boundary. No two
  // divergent owners mutate the speculative RAT in one cycle.
  val trapCandidate = count =/= 0.U && headEntry.valid &&
    headEntry.complete && !recovering && !branchMismatch &&
    io.retire.ready && headEntry.exception.valid
  val canDispatch = !recovering && !branchMismatch && !trapCandidate &&
    !serializedLive && count < N.U && (!writes || hasPhysical) &&
    (exceptionOnly || systemDispatch || hasIq)
  io.memoryReserve.valid := io.dispatch.valid && canDispatch &&
    supported && !exceptionOnly && memoryDispatch
  io.memoryReserve.bits.token.index := tail
  io.memoryReserve.bits.token.generation := generations(tail)
  io.memoryReserve.bits.kind := dispatchDecoded.memory.kind
  io.memoryReserve.bits.size := dispatchDecoded.memory.size
  io.memoryReserve.bits.unsigned := dispatchDecoded.memory.unsigned
  io.memoryReserve.bits.atomicOp := dispatchDecoded.memory.atomicOp
  io.memoryReserve.bits.destination.index := Mux(writes, chosenPhysical, 0.U)
  io.memoryReserve.bits.destination.generation :=
    Mux(writes, chosenGeneration, 0.U)
  io.memoryReserve.bits.destinationFile := destinationFile
  io.memoryReserve.bits.hasDestination := writes
  io.dispatch.ready := canDispatch && (supported || exceptionOnly) &&
    (!memoryDispatch || exceptionOnly || io.memoryReserve.ready)
  io.memoryHead.valid := count =/= 0.U && headEntry.valid &&
    !recovering && !branchMismatch
  io.memoryHead.bits.index := head
  io.memoryHead.bits.generation := headEntry.tokenGeneration

  // System semantics are a separate head-only completion lane. An enqueue
  // handshake marks the ROB lifetime once, even if the completion arbiter
  // stalls while live timer/CSR inputs change.
  val headIsSystemLifetime = systemPayload.valid &&
    systemPayload.token.index === head &&
    systemPayload.token.generation === headEntry.tokenGeneration
  val systemRs1Ready = !systemPayload.usesRs1 ||
    ready(systemPayload.rs1Physical)
  val systemRs2Ready = !systemPayload.usesRs2 ||
    ready(systemPayload.rs2Physical)
  io.systemHead.valid := allowSystem.B && count =/= 0.U && headEntry.valid &&
    !headEntry.complete && !headEntry.systemIssued &&
    headEntry.isSystem && headIsSystemLifetime &&
    systemRs1Ready && systemRs2Ready &&
    !recovering && !branchMismatch && !trapCandidate
  io.systemHead.bits.token.index := head
  io.systemHead.bits.token.generation := headEntry.tokenGeneration
  io.systemHead.bits.destination := headEntry.newPhysical
  io.systemHead.bits.hasDestination := headEntry.writes
  // The shared evaluator consumes only SYSTEM/CSR operands, PC, raw opcode
  // and predecoded trap.  The other decoded fields have no semantic reader.
  io.systemHead.bits.decoded.pc := headEntry.pc
  io.systemHead.bits.decoded.rawInst := headEntry.rawInst
  io.systemHead.bits.decoded.rs1 := systemPayload.rs1
  io.systemHead.bits.decoded.system := systemPayload.system
  io.systemHead.bits.decoded.exception := headEntry.exception

  val retiringFault = headEntry.exception.valid
  io.retire.valid := count =/= 0.U && headEntry.valid &&
    headEntry.complete && !recovering && !branchMismatch
  io.retire.bits := 0.U.asTypeOf(io.retire.bits)
  io.retire.bits.token.index := head
  io.retire.bits.token.generation := headEntry.tokenGeneration
  io.retire.bits.pc := headEntry.pc
  io.retire.bits.inst := headEntry.inst
  io.retire.bits.rawInst := headEntry.rawInst
  io.retire.bits.instBytes := headEntry.instBytes
  io.retire.bits.rd := headEntry.rd
  io.retire.bits.destinationFile := headEntry.destinationFile
  io.retire.bits.wroteRegister := headEntry.writes &&
    headEntry.destinationFile === ProductRegisterFile.Integer && !retiringFault
  io.retire.bits.wroteFloatingRegister := headEntry.writes &&
    headEntry.destinationFile === ProductRegisterFile.Floating && !retiringFault
  io.retire.bits.value := headEntry.result
  io.retire.bits.fflags := Mux(retiringFault, 0.U(5.W), headEntry.fflags)
  io.retire.bits.fpDirty := !retiringFault &&
    (io.retire.bits.wroteFloatingRegister || headEntry.fpStateDirty)
  io.retire.bits.exception := headEntry.exception
  io.retire.bits.privileged := headEntry.privileged
  io.retire.bits.memoryTrace := headEntry.memoryTrace
  io.retire.bits.memoryTrace.valid := headEntry.memoryTrace.valid && !retiringFault
  val trapFire = io.retire.fire && retiringFault
  io.trap.valid := trapFire
  io.trap.bits := io.retire.bits

  // IQ owns only dependency/scheduling state, never architectural order.
  val iqReady = Wire(Vec(Q, Bool()))
  for (j <- 0 until Q) {
    val e = iq(j)
    val floatingExecute = if (enableFloating) {
      e.floating.get.kind =/= FloatingOperationKind.None &&
        e.memoryKind === MemoryOperationKind.None
    } else false.B
    val fuAvailable = Mux(e.memoryKind =/= MemoryOperationKind.None,
      io.memoryAvailable, Mux(floatingExecute,
        (if (enableFloating) io.fpAvailable.get else false.B),
        Mux(ProductMOperation.isM(e.aluOp), io.mAvailable, io.integerAvailable)))
    val fpSourcesReady = if (enableFloating) {
      (!e.useFpRs1.get || fpReady.get(e.fpSrc1.get)) &&
      (!e.useFpRs2.get || fpReady.get(e.fpSrc2.get)) &&
      (!e.useFpRs3.get || fpReady.get(e.fpSrc3.get))
    } else true.B
    iqReady(j) := e.valid && fuAvailable &&
      (!e.useRs1 || ready(e.src1)) &&
      (!e.useRs2 || ready(e.src2)) && fpSourcesReady
  }
  val readyMask = iqReady.asUInt
  val chooseIssue = PriorityEncoder(readyMask)
  private val selected = iq(chooseIssue)
  val selectedFloatingExecute = if (enableFloating) {
    selected.floating.get.kind =/= FloatingOperationKind.None &&
      selected.memoryKind === MemoryOperationKind.None
  } else false.B
  val selectedReady = readyMask.orR && !recovering && !branchMismatch && !trapFire
  io.issue.valid := !recovering && !branchMismatch && !trapFire &&
    Mux(issueHolding,
      !(if (enableFloating) heldFloating.get else false.B),
      selectedReady && !selectedFloatingExecute)
  // Exact-head SYSTEM and normal issue are mutually exclusive: younger
  // dispatch is barred by serializedLive and all older IQ work has issued
  // before SYSTEM can become ROB head. Reuse the same two PRF read muxes.
  val readSystem = io.systemHead.valid
  val readAddr1 = Mux(readSystem, systemPayload.rs1Physical, selected.src1)
  val readAddr2 = Mux(readSystem, systemPayload.rs2Physical, selected.src2)
  prf.io.readAddress(0) := readAddr1
  prf.io.readAddress(1) := readAddr2
  val readValue1 = prf.io.readValue(0)
  val readValue2 = prf.io.readValue(1)
  if (enableFloating) {
    fpPrf.get.io.readAddress(0) := selected.fpSrc1.get
    fpPrf.get.io.readAddress(1) := selected.fpSrc2.get
    fpPrf.get.io.readAddress(2) := selected.fpSrc3.get
  }
  val fpRead1 = if (enableFloating) fpPrf.get.io.readValue(0) else 0.U(Xlen.W)
  val fpRead2 = if (enableFloating) fpPrf.get.io.readValue(1) else 0.U(Xlen.W)
  val fpRead3 = if (enableFloating) fpPrf.get.io.readValue(2) else 0.U(Xlen.W)
  io.systemHead.bits.rs1Value := readValue1
  io.systemHead.bits.rs2Value := readValue2
  io.issue.bits := 0.U.asTypeOf(io.issue.bits)
  io.issue.bits.token := selected.token
  io.issue.bits.destination := selected.destination
  io.issue.bits.destinationFile := selected.destinationFile
  io.issue.bits.hasDestination := selected.writes
  io.issue.bits.aluOp := selected.aluOp
  io.issue.bits.wordOp := selected.wordOp
  io.issue.bits.memoryKind := selected.memoryKind
  io.issue.bits.memorySize := selected.memorySize
  io.issue.bits.memoryUnsigned := selected.memoryUnsigned
  io.issue.bits.atomicOp := selected.atomicOp
  val selectedFpStore = if (enableFloating)
    selected.floating.get.kind === FloatingOperationKind.Store else false.B
  io.issue.bits.storeData := Mux(selectedFpStore,
    fpRead2, readValue2)
  io.issue.bits.controlFlow := selected.controlFlow
  io.issue.bits.branchType := selected.branchType
  io.issue.bits.pc := selected.pc
  io.issue.bits.instBytes := selected.instBytes
  io.issue.bits.immediate := selected.immediate
  io.issue.bits.lhs := MuxLookup(selected.lhsSource.asUInt, 0.U(Xlen.W))(Seq(
    OperandSourceKind.Rs1.asUInt -> readValue1,
    OperandSourceKind.Pc.asUInt -> selected.pc,
    OperandSourceKind.Zero.asUInt -> 0.U(Xlen.W)))
  io.issue.bits.rhs := Mux(selected.rhsSource === OperandSourceKind.Immediate,
    selected.immediate, readValue2)
  when(issueHolding) {
    io.issue.bits := heldIssue
  }

  if (enableFloating) {
    val issue = io.fpIssue.get
    issue.valid := !recovering && !branchMismatch && !trapFire &&
      Mux(issueHolding, heldFloating.get, selectedReady && selectedFloatingExecute)
    issue.bits := 0.U.asTypeOf(issue.bits)
    issue.bits.token := selected.token
    issue.bits.destination := selected.destination
    issue.bits.destinationFile := selected.destinationFile
    issue.bits.hasDestination := selected.writes
    issue.bits.floating := selected.floating.get
    issue.bits.fpLhs := fpRead1
    issue.bits.fpRhs := fpRead2
    issue.bits.fpRs3 := fpRead3
    issue.bits.intLhs := readValue1
    issue.bits.pc := selected.pc
    issue.bits.rawInst := selected.rawInst
    issue.bits.instBytes := selected.instBytes
    when(issueHolding) {
      issue.bits := heldFpIssue.get
    }
  }

  val normalRetire = io.retire.fire && !retiringFault

  // Age-precise recovery is serialized. A branch is preserved; younger work
  // is undone from tail backwards. A synchronous trap discards all uncommitted.
  when(recovering) {
    val finished = Mux(flushingTrap, count === 0.U, tail === (recoveryCut + 1.U)(I - 1, 0))
    when(finished) {
      recovering := false.B
      flushingTrap := false.B
    }.otherwise {
      val victimIndex = (tail - 1.U)(I - 1, 0)
      val victim = rob(victimIndex)
      assert(victim.valid, "recovery victim must belong to a live ROB lifetime")
      io.memoryKill.valid := victim.memoryKind =/= MemoryOperationKind.None
      io.memoryKill.bits.index := victimIndex
      io.memoryKill.bits.generation := victim.tokenGeneration
      if (enableFloating) {
        // The FP lane locally compares this lifetime against its outstanding
        // token. Sending every victim needs no extra per-ROB FP classification
        // bit and handles FP->XPR operations just as safely as FP->FPR ones.
        io.fpKill.get.valid := true.B
        io.fpKill.get.bits.index := victimIndex
        io.fpKill.get.bits.generation := victim.tokenGeneration
      }
      when(victim.writes && victim.destinationFile === ProductRegisterFile.Integer) {
        speculativeRat(victim.rd) := victim.oldPhysical
        free(victim.newPhysical.index) := true.B
        ready(victim.newPhysical.index) := false.B
      }
      if (enableFloating) {
        when(victim.writes && victim.destinationFile === ProductRegisterFile.Floating) {
          fpSpeculativeRat.get(victim.rd) := victim.oldPhysical
          fpFree.get(victim.newPhysical.index) := true.B
          fpReady.get(victim.newPhysical.index) := false.B
        }
      }
      for (j <- 0 until Q) {
        when(iq(j).valid && iq(j).token.index === victimIndex &&
             iq(j).token.generation === victim.tokenGeneration) {
          iq(j).valid := false.B
        }
      }
      rob(victimIndex).valid := false.B
      when(victim.isSystem && systemPayload.valid &&
           systemPayload.token.index === victimIndex &&
           systemPayload.token.generation === victim.tokenGeneration) {
        systemPayload.valid := false.B
      }
      generations(victimIndex) := generations(victimIndex) + 1.U
      tail := victimIndex
      count := count - 1.U
    }
  }.otherwise {
    when(branchMismatch) {
      recovering := true.B
      flushingTrap := false.B
      recoveryCut := c.token.index
      // A wrong-path stalled offer has not entered its FU. Keep the IQ slot
      // for any older instruction and reselect it after rollback; younger
      // held offers are removed by the ROB victim walk.
      issueHolding := false.B
    }.elsewhen(trapFire) {
      recovering := true.B
      flushingTrap := true.B
      issueHolding := false.B
    }.otherwise {
      when(io.dispatch.fire) {
        val d = io.dispatch.bits.decoded
        val newGeneration = chosenGeneration
        val prior = if (enableFloating) {
          Mux(writesFloating, fpSpeculativeRat.get(d.floating.rd), speculativeRat(d.rd))
        } else speculativeRat(d.rd)
        rob(tail) := 0.U.asTypeOf(rob(tail))
        rob(tail).valid := true.B
        rob(tail).complete := exceptionOnly
        rob(tail).tokenGeneration := generations(tail)
        rob(tail).pc := d.pc
        rob(tail).inst := d.inst
        rob(tail).rawInst := d.rawInst
        rob(tail).instBytes := d.instBytes
        rob(tail).rd := architecturalRd
        rob(tail).destinationFile := destinationFile
        rob(tail).controlFlowKind := d.controlFlow.kind
        rob(tail).memoryKind := d.memory.kind
        rob(tail).isSystem := systemDispatch
        rob(tail).predictedNextPc := io.dispatch.bits.predictedNextPc
        rob(tail).writes := writes
        rob(tail).oldPhysical := prior
        rob(tail).newPhysical.index := Mux(writes, chosenPhysical, 0.U)
        rob(tail).newPhysical.generation := Mux(writes, newGeneration, 0.U)
        rob(tail).exception := d.exception
        // Capture sources BEFORE the destination rename. CSRRW x1,...,x1
        // must read the old architectural x1 value, never the new PRF tag.
        when(systemDispatch && !exceptionOnly) {
          assert(!systemPayload.valid, "one serialized SYSTEM payload per live ROB")
          systemPayload.valid := true.B
          systemPayload.token.index := tail
          systemPayload.token.generation := generations(tail)
          systemPayload.rs1Physical := speculativeRat(d.rs1)
          systemPayload.rs2Physical := speculativeRat(d.rs2)
          systemPayload.rs1 := d.rs1
          systemPayload.usesRs1 := d.usesRs1
          systemPayload.usesRs2 := d.usesRs2
          systemPayload.system := d.system
        }
        when(writesInteger) {
          speculativeRat(d.rd) := chosenPhysical
          free(chosenPhysical) := false.B
          ready(chosenPhysical) := false.B
          physicalGenerations(chosenPhysical) := newGeneration
        }
        if (enableFloating) {
          when(writesFloating) {
            fpSpeculativeRat.get(d.floating.rd) := chosenPhysical
            fpFree.get(chosenPhysical) := false.B
            fpReady.get(chosenPhysical) := false.B
            fpPhysicalGenerations.get(chosenPhysical) := newGeneration
          }
        }
        when(!exceptionOnly && !systemDispatch) {
          iq(chosenIq) := 0.U.asTypeOf(iq(chosenIq))
          iq(chosenIq).valid := true.B
          iq(chosenIq).token.index := tail
          iq(chosenIq).token.generation := generations(tail)
          iq(chosenIq).destination.index := Mux(writes, chosenPhysical, 0.U)
          iq(chosenIq).destination.generation := Mux(writes, newGeneration, 0.U)
          iq(chosenIq).destinationFile := destinationFile
          iq(chosenIq).writes := writes
          iq(chosenIq).src1 := speculativeRat(d.rs1)
          iq(chosenIq).src2 := speculativeRat(d.rs2)
          iq(chosenIq).useRs1 := d.usesRs1
          iq(chosenIq).useRs2 := d.usesRs2
          if (enableFloating) {
            iq(chosenIq).floating.get := d.floating
            iq(chosenIq).fpSrc1.get := fpSpeculativeRat.get(d.floating.rs1)
            iq(chosenIq).fpSrc2.get := fpSpeculativeRat.get(d.floating.rs2)
            iq(chosenIq).fpSrc3.get := fpSpeculativeRat.get(d.floating.rs3)
            iq(chosenIq).useFpRs1.get := d.floating.usesRs1
            iq(chosenIq).useFpRs2.get := d.floating.usesRs2
            iq(chosenIq).useFpRs3.get := d.floating.usesRs3
          }
          iq(chosenIq).lhsSource := d.lhsSource
          iq(chosenIq).rhsSource := d.rhsSource
          iq(chosenIq).aluOp := d.aluOp
          iq(chosenIq).wordOp := d.wordOp
          iq(chosenIq).memoryKind := d.memory.kind
          iq(chosenIq).memorySize := d.memory.size
          iq(chosenIq).memoryUnsigned := d.memory.unsigned
          iq(chosenIq).atomicOp := d.memory.atomicOp
          iq(chosenIq).controlFlow := d.controlFlow.kind
          iq(chosenIq).branchType := d.controlFlow.branchType
          iq(chosenIq).immediate := d.immediate
          iq(chosenIq).pc := d.pc
          iq(chosenIq).rawInst := d.rawInst
          iq(chosenIq).instBytes := d.instBytes
        }
        tail := tail + 1.U
      }
      val issueFired = if (enableFloating) io.issue.fire || io.fpIssue.get.fire
                       else io.issue.fire
      val fpStalled = if (enableFloating)
        io.fpIssue.get.valid && !io.fpIssue.get.ready else false.B
      when(!issueHolding && ((io.issue.valid && !io.issue.ready) || fpStalled)) {
        issueHolding := true.B
        heldIqIndex := chooseIssue
        heldIssue := io.issue.bits
        if (enableFloating) {
          heldFloating.get := io.fpIssue.get.valid
          heldFpIssue.get := io.fpIssue.get.bits
        }
      }.elsewhen(issueHolding && issueFired) {
        issueHolding := false.B
      }
      when(issueFired) {
        iq(Mux(issueHolding, heldIqIndex, chooseIssue)).valid := false.B
      }
      when(io.systemHead.fire) {
        rob(head).systemIssued := true.B
      }
      when(normalRetire) {
        when(headEntry.writes && headEntry.destinationFile === ProductRegisterFile.Integer) {
          assert(headEntry.oldPhysical =/= 0.U)
          committedRat(headEntry.rd) := headEntry.newPhysical.index
          free(headEntry.oldPhysical) := true.B
        }
        if (enableFloating) {
          when(headEntry.writes && headEntry.destinationFile === ProductRegisterFile.Floating) {
            fpCommittedRat.get(headEntry.rd) := headEntry.newPhysical.index
            fpFree.get(headEntry.oldPhysical) := true.B
          }
        }
        when(headEntry.isSystem && headIsSystemLifetime) {
          systemPayload.valid := false.B
        }
        rob(head).valid := false.B
        generations(head) := generations(head) + 1.U
        head := head + 1.U
      }
      when(io.dispatch.fire =/= normalRetire) {
        count := Mux(io.dispatch.fire, count + 1.U, count - 1.U)
      }
    }
  }

  // A validated older completion may write while a younger ROB entry is being
  // undone. Physical register reuse cannot occur until its undo is complete.
  // All RAM writes are behind the exact ROB/physical generation match; a
  // newly allocated tag is invalidated while its ready bit is cleared.
  prf.io.invalidate.valid := io.dispatch.fire && writesInteger
  prf.io.invalidate.bits := chosenPhysical
  prf.io.write.valid := accepted && c.hasValue && !c.exception.valid &&
    c.destinationFile === ProductRegisterFile.Integer
  prf.io.write.bits.index := c.destination.index
  prf.io.write.bits.value := c.value
  if (enableFloating) {
    fpPrf.get.io.invalidate.valid := io.dispatch.fire && writesFloating
    fpPrf.get.io.invalidate.bits := chosenPhysical
    fpPrf.get.io.write.valid := accepted && c.hasValue && !c.exception.valid &&
      c.destinationFile === ProductRegisterFile.Floating
    fpPrf.get.io.write.bits.index := c.destination.index
    fpPrf.get.io.write.bits.value := c.value
  }
  when(accepted) {
    rob(c.token.index).complete := true.B
    rob(c.token.index).result := c.value
    rob(c.token.index).fflags := c.fflags
    rob(c.token.index).fpStateDirty := c.fpStateDirty
    rob(c.token.index).exception := c.exception
    rob(c.token.index).privileged := c.privileged
    rob(c.token.index).memoryTrace := c.memoryTrace
    when(c.hasValue && !c.exception.valid &&
         c.destinationFile === ProductRegisterFile.Integer) {
      ready(c.destination.index) := true.B
    }
    if (enableFloating) {
      when(c.hasValue && !c.exception.valid &&
           c.destinationFile === ProductRegisterFile.Floating) {
        fpReady.get(c.destination.index) := true.B
      }
    }
  }

  assert(speculativeRat(0) === 0.U && committedRat(0) === 0.U &&
    !free(0), "x0 must remain mapped to immutable physical zero")
  when(io.dispatch.fire) {
    assert(!(writesInteger && writesFloating),
      "one RISC-V instruction cannot commit both XPR and FPR destinations")
  }
  if (enableFloating) {
    when(io.fpIssue.get.valid) {
      assert(!io.issue.valid, "the Small CPU has only one issue slot per cycle")
    }
    // FP f0 is writable; no integer-x0 alias or fixed-value bypass applies.
    assert(fpSpeculativeRat.get(0) < FpP.U && fpCommittedRat.get(0) < FpP.U)
  }
  assert(count <= N.U, "ROB cannot over-allocate")
  when(io.dispatch.fire && writes) {
    when(destinationFile === ProductRegisterFile.Integer) {
      assert(chosenPhysical =/= 0.U, "integer physical zero cannot be renamed")
    }
  }
  when(io.retire.fire && !retiringFault) {
    assert(headEntry.valid && headEntry.complete, "retirement must be exact head")
  }
  when(io.systemHead.valid) {
    assert(!io.issue.valid, "exact-head SYSTEM and IQ issue share PRF ports")
    assert(headIsSystemLifetime, "SYSTEM payload must match exact ROB lifetime")
  }
  when(systemPayload.valid) {
    val owner = rob(systemPayload.token.index)
    assert(owner.valid && owner.isSystem &&
      owner.tokenGeneration === systemPayload.token.generation,
      "singleton SYSTEM sidecar must have a live ROB lifetime")
  }
  when(count =/= 0.U && headEntry.valid && headEntry.isSystem &&
       !headEntry.complete) {
    assert(headIsSystemLifetime, "uncompleted head SYSTEM must retain its payload")
  }
}
