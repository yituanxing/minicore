package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.config.PageTableGeometry
import aethercore.core.{PmpConstants, PmpGeometry}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * Safety wrapper for the first conservative pre-head Load experiment.
  *
  * The qualified TinyBlockingLsu remains the sole transaction/completion owner.
  * This wrapper only distinguishes an accepted younger Load lifetime until it
  * either completes or becomes the exact ROB head. While that lifetime is still
  * speculative it prevents externally visible page-table traffic and permits a
  * physical read only when PMA says the region is idempotent, non-side-effecting
  * and non-ordered. Device/ordered reads therefore wait for exact-head
  * permission without adding another outstanding transaction or an LSQ.
  *
  * A speculative TLB miss may occupy the LSU's internal walker state, but no PTE
  * memory transaction is allowed to leave this wrapper before exact-head
  * permission. This distinction keeps the mechanism bounded and architectural
  * side effects precise while reusing the already-qualified walker/LSU state.
  */
class TinyPreHeadLoadLsu(
    val geometry: PageTableGeometry,
    val paddrBits: Int = -1,
    val tlbEntries: Int = 8,
    val txnIdBits: Int = 2,
    val allowAtomics: Boolean = false
) extends Module {
  private val Xlen = geometry.xlen
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val PhysicalBits =
    if (paddrBits > 0) paddrBits else geometry.architecturalPhysicalAddressBits
  private val PmpAddressBits = PmpGeometry(Xlen, PhysicalBits).encodedAddressBits

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits)))
    // Sampled only when request.fire. true means the accepted lifetime was not
    // the ROB head at launch.
    val requestPreHead = Input(Bool())
    // Current exact Memory-head lifetime. A held speculative request becomes
    // ordinary as soon as this token matches its accepted lifetime.
    val headPermit = Flipped(Valid(new RobToken(IdentityBits, GenerationBits)))

    val completion = Decoupled(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
    val memoryTrace = Valid(new TinyMemoryTrace(Xlen, PhysicalBits, IdentityBits, GenerationBits))

    val storePermit = Flipped(Valid(new RobToken(IdentityBits, GenerationBits)))
    val reservationClear = if (allowAtomics) Some(Input(Bool())) else None

    val effectivePrivilege = Input(UInt(2.W))
    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(geometry.ppnBits.W))
    val supervisorSum = Input(Bool())
    val supervisorMxr = Input(Bool())
    val translationFlush = Input(Bool())

    val pmpEnabled = Input(Bool())
    val pmpConfig = Input(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Input(Vec(PmpConstants.MaxEntries, UInt(PmpAddressBits.W)))

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PhysicalBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(PhysicalBits.W))
    val resolvedAttributes = Input(new MemoryAttributes)

    val memoryRequest = Decoupled(new AetherMemRequest(PhysicalBits, Xlen, txnIdBits))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(Xlen, txnIdBits)))

    val busy = Output(Bool())
  })

  private def sameRobToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  val inner = Module(new TinyBlockingLsu(
    geometry,
    paddrBits = PhysicalBits,
    tlbEntries = tlbEntries,
    txnIdBits = txnIdBits,
    allowAtomics = allowAtomics
  ))

  inner.io.request <> io.request
  io.completion <> inner.io.completion
  io.memoryTrace := inner.io.memoryTrace
  inner.io.storePermit := io.storePermit
  if (allowAtomics) {
    inner.io.reservationClear.get := io.reservationClear.get
  }

  inner.io.effectivePrivilege := io.effectivePrivilege
  inner.io.satpTranslationEnabled := io.satpTranslationEnabled
  inner.io.satpRootPpn := io.satpRootPpn
  inner.io.supervisorSum := io.supervisorSum
  inner.io.supervisorMxr := io.supervisorMxr
  inner.io.translationFlush := io.translationFlush
  inner.io.pmpEnabled := io.pmpEnabled
  inner.io.pmpConfig := io.pmpConfig
  inner.io.pmpAddress := io.pmpAddress

  // Remember only the currently accepted blocking-LSU lifetime. If a safe
  // younger hit completes before reaching head, the tracking bit is cleared
  // together with completion; there is no second speculative-memory queue.
  val activePreHead = RegInit(false.B)
  val activeToken = Reg(new RobToken(IdentityBits, GenerationBits))
  when(inner.io.request.fire) {
    activePreHead := io.requestPreHead
    activeToken := inner.io.request.bits.robToken
    when(io.requestPreHead) {
      assert(inner.io.request.bits.kind === MemoryOperationKind.Load,
        "pre-head LSU intake must be an ordinary Load")
      assert(inner.io.request.bits.atomicOp.asUInt === 0.U,
        "pre-head LSU intake must not carry an Atomic operation")
    }
  }
  when(inner.io.completion.fire) {
    activePreHead := false.B
  }

  // The intake-flow-through LSU can expose translation/memory signals on the
  // same cycle as request.fire, before activePreHead/activeToken registers have
  // updated. Use the incoming lifetime while idle and the captured lifetime
  // while busy so the safety gate covers both phases.
  val intakePreHead = inner.io.request.fire && io.requestPreHead
  val workingPreHead = Mux(inner.io.busy, activePreHead, intakePreHead)
  val workingToken = Wire(new RobToken(IdentityBits, GenerationBits))
  workingToken := activeToken
  when(!inner.io.busy) {
    workingToken := inner.io.request.bits.robToken
  }
  val exactHeadMatches = io.headPermit.valid && sameRobToken(io.headPermit.bits, workingToken)
  val speculative = workingPreHead && !exactHeadMatches

  // A speculative miss may reserve internal walker state, but page-table memory
  // traffic remains invisible until this lifetime becomes the exact Memory head.
  io.pteValid := inner.io.pteValid && !speculative
  io.pteAddress := inner.io.pteAddress
  inner.io.pteReady := io.pteReady && !speculative
  inner.io.pteData := io.pteData
  inner.io.pteFault := io.pteFault && !speculative

  io.resolvedPhysicalValid := inner.io.resolvedPhysicalValid
  io.resolvedPhysicalAddress := inner.io.resolvedPhysicalAddress
  inner.io.resolvedAttributes := io.resolvedAttributes

  // PMA is resolved at the platform boundary from the translated physical
  // address. Only replay-safe ordinary reads may cross that boundary early.
  // Cacheability is deliberately not required: idempotence/side-effect/order are
  // the properties needed for this bounded speculation contract.
  val speculativeReadSafe =
    io.resolvedAttributes.idempotent &&
      !io.resolvedAttributes.sideEffecting &&
      !io.resolvedAttributes.ordered
  val speculativeMemoryPermit = !speculative || (
    inner.io.memoryRequest.bits.op === AetherMemOp.Read && speculativeReadSafe
  )

  io.memoryRequest.valid := inner.io.memoryRequest.valid && speculativeMemoryPermit
  io.memoryRequest.bits := inner.io.memoryRequest.bits
  inner.io.memoryRequest.ready := io.memoryRequest.ready && speculativeMemoryPermit

  inner.io.memoryResponse.valid := io.memoryResponse.valid
  inner.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := inner.io.memoryResponse.ready
  io.busy := inner.io.busy

  when(speculative && inner.io.memoryRequest.valid) {
    assert(inner.io.memoryRequest.bits.op === AetherMemOp.Read,
      "only an ordinary read may reach the speculative memory gate")
  }
  when(io.memoryRequest.fire && speculative) {
    assert(speculativeReadSafe,
      "pre-head physical read must be idempotent, non-side-effecting and non-ordered")
  }
  when(io.pteValid) {
    assert(!speculative, "pre-head lifetime must not externalize PTW traffic")
  }
}
