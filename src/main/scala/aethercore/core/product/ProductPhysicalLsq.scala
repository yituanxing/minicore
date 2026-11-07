package aethercore.core.product

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize, TrapInfo}
import aethercore.core.semantic.MemoryOperationKind
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse, MemoryAttributes}

/** Reserved at ROB dispatch, BEFORE any younger memory issue may bypass it.
  * This is essential: issue order is not the same as architectural age.
  */
class ProductMemReservation(val indexBits: Int, val physicalBits: Int, val generationBits: Int) extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  val kind = MemoryOperationKind()
  val size = MemSize()
  val unsigned = Bool()
  val atomicOp = AtomicOp()
  // The ROB lifetime and the physical destination register bank are separate
  // axes. A floating load writes a floating PRF tag; ordinary loads/atomics
  // continue to write the integer PRF. A floating store has no destination.
  val destinationFile = ProductRegisterFile()
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val hasDestination = Bool()
}

/** Translation/PMP/PMA result supplied by an upstream, separately owned unit.
  * This is a PHYSICAL address. Never use VA==PA when SATP is enabled.
  */
class ProductResolvedMem(val paddrBits: Int, val indexBits: Int, val generationBits: Int) extends Bundle {
  val token = new ProductRobId(indexBits, generationBits)
  // Exact effective VA from the issued uOp, echoed by the resolver. Fault
  // tval/stval is architectural VA, never the translated physical address.
  val virtualAddress = UInt(64.W)
  val paddr = UInt(paddrBits.W)
  val storeData = UInt(64.W)
  val attributes = new MemoryAttributes
  val fault = new TrapInfo(64)
}

/** State of exactly one ROB-age memory operation; no memory txn identity here. */
private class ProductMemSlot(val paddrBits: Int, val indexBits: Int,
                             val physicalBits: Int, val generationBits: Int) extends Bundle {
  val valid = Bool()
  val token = new ProductRobId(indexBits, generationBits)
  val kind = MemoryOperationKind()
  val size = MemSize()
  val unsigned = Bool()
  val atomicOp = AtomicOp()
  val destinationFile = ProductRegisterFile()
  val destination = new ProductPhysicalTag(physicalBits, generationBits)
  val hasDestination = Bool()
  val resolved = Bool()
  val launched = Bool()
  val virtualAddress = UInt(64.W)
  val paddr = UInt(paddrBits.W)
  val storeData = UInt(64.W)
  val attributes = new MemoryAttributes
  val fault = new TrapInfo(64)
}

/** External AetherMem lifetime is independent of both ROB and physical PRF. */
private class ProductTxnOwner(val indexBits: Int, val generationBits: Int) extends Bundle {
  val active = Bool()
  val token = new ProductRobId(indexBits, generationBits)
  val isLoad = Bool()
}

/** Correctness-first LQ/SQ: physical-address stage, two independent lifetimes.
  *
  * - Physical storage is sized by LQ+SQ, NOT ROB depth. Each slot carries an
  *   independent ROB token; a larger ROB cannot replicate the wide VA/PA/data
  *   records or the store-dependency network.
  * - Reservation is atomic with ROB dispatch, so older unresolved stores block
  *   younger loads instead of requiring speculative alias prediction/replay.
  * - Full-cover youngest-older store data forwards; partial overlap stalls.
  * - Cacheable idempotent ordinary Loads can run before ROB head; MMIO Loads
  *   and EVERY Store/Atomic require exact ROB head (side effects cannot escape).
  * - Each physical transaction leases an independent ID until LAST response.
  *   Squash removes ROB ownership but never prematurely recycles a leased ID.
  * - At most one physical request and one completion per cycle; completion is
  *   Decoupled and held. No device/Store effect can escape a wrong path.
  *
  * Requires a real upstream virtual->physical + PMP/PMA resolver. This is
  * neither an MMU nor a complete CPU; F/D and privileged ops are not implemented.
  */
class ProductPhysicalLsq(val geometry: ProductCoreGeometry, val paddrBits: Int = 56)
    extends Module {
  require(paddrBits >= 3 && paddrBits <= 64)
  private val N = geometry.robEntries
  private val L = geometry.loadQueueEntries
  private val S = geometry.storeQueueEntries
  private val T = L + S
  private val I = log2Ceil(N)
  private val P = log2Ceil(geometry.integerPhysicalRegisters)
  private val G = 16
  private val TxBits = log2Ceil(T)
  require(T > 1 && T <= (1 << TxBits), "physical transaction IDs must encode the entire pool")

  val io = IO(new Bundle {
    val reserve = Flipped(Decoupled(new ProductMemReservation(I, P, G)))
    val resolve = Flipped(Decoupled(new ProductResolvedMem(paddrBits, I, G)))
    val robHead = Flipped(Valid(new ProductRobId(I, G)))
    val kill = Flipped(Valid(new ProductRobId(I, G)))
    val droppedStaleResolution = Output(Bool())
    val memoryRequest = Decoupled(new AetherMemRequest(paddrBits, 64, TxBits))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(64, TxBits)))
    val completion = Decoupled(new ProductCompletion(64, I, P, G, paddrBits))
    val loadCount = Output(UInt(log2Ceil(L + 1).W))
    val storeCount = Output(UInt(log2Ceil(S + 1).W))
    val outstanding = Output(UInt(log2Ceil(T + 1).W))
    val forwarded = Output(Bool())
    val drainedKilledResponse = Output(Bool())
  })

  private val slots = RegInit(VecInit(Seq.fill(T)(
    0.U.asTypeOf(new ProductMemSlot(paddrBits, I, P, G)))))
  private val transactions = RegInit(VecInit(Seq.fill(T)(
    0.U.asTypeOf(new ProductTxnOwner(I, G)))))
  // AetherMem is a real Decoupled boundary: once VALID is offered, neither
  // the payload nor its leased identity may be replaced just because ready
  // stalls. Keep the *offered* record separate from the changing eligible
  // age tournament and the pool of IDs not yet accepted by downstream.
  private val heldRequest = RegInit(false.B)
  private val heldRequestBits = Reg(new AetherMemRequest(paddrBits, 64, TxBits))
  private val heldRequestSlot = Reg(UInt(TxBits.W))
  private val heldRequestToken = Reg(new ProductRobId(I, G))
  private def equal(a: ProductRobId, b: ProductRobId): Bool =
    a.index === b.index && a.generation === b.generation
  private def bytesOf(size: MemSize.Type): UInt = 1.U(4.W) << size.asUInt
  private def accessMask(size: MemSize.Type): UInt =
    ((1.U(9.W) << bytesOf(size)) - 1.U)(7, 0)
  private def alignedWord(p: UInt): UInt = p(paddrBits - 1, 3)
  private def laneMask(p: UInt, size: MemSize.Type): UInt =
    (accessMask(size) << p(2, 0))(7, 0)
  private def extended(data: UInt, size: MemSize.Type, unsigned: Bool): UInt = {
    val out = WireDefault(data)
    switch(size) {
      is(MemSize.Byte) {
        out := Mux(unsigned, data(7, 0).pad(64), Cat(Fill(56, data(7)), data(7, 0)))
      }
      is(MemSize.Half) {
        out := Mux(unsigned, data(15, 0).pad(64), Cat(Fill(48, data(15)), data(15, 0)))
      }
      is(MemSize.Word) {
        out := Mux(unsigned, data(31, 0).pad(64), Cat(Fill(32, data(31)), data(31, 0)))
      }
    }
    out
  }
  // FLW writes the raw 32-bit memory payload into a 64-bit FP register using
  // mandatory NaN boxing. Integer LW sign-extends and LWU zero-extends as
  // before; FLD preserves all 64 bits. Keep both external replies and store
  // forwarding on this one path so they cannot disagree about FP bit patterns.
  private def loadValue(data: UInt, size: MemSize.Type, unsigned: Bool,
                        destinationFile: ProductRegisterFile.Type): UInt =
    Mux(destinationFile === ProductRegisterFile.Floating && size === MemSize.Word,
      Cat("hffffffff".U(32.W), data(31, 0)), extended(data, size, unsigned))
  // The AetherMem atomic response contains the pre-RMW memory value. Rebuild
  // exactly the successful write value for architectural trace observation;
  // AMO.W arithmetic and comparisons are 32-bit, not sign-extended XLEN ops.
  private def atomicWriteValue(old: UInt, rhs: UInt, size: MemSize.Type,
                               op: AtomicOp.Type): UInt = {
    val oldWord = old(31, 0)
    val rhsWord = rhs(31, 0)
    val word = WireDefault(rhsWord)
    switch(op) {
      is(AtomicOp.Add) { word := oldWord + rhsWord }
      is(AtomicOp.Xor) { word := oldWord ^ rhsWord }
      is(AtomicOp.And) { word := oldWord & rhsWord }
      is(AtomicOp.Or) { word := oldWord | rhsWord }
      is(AtomicOp.Min) { word := Mux(oldWord.asSInt < rhsWord.asSInt, oldWord, rhsWord) }
      is(AtomicOp.Max) { word := Mux(oldWord.asSInt > rhsWord.asSInt, oldWord, rhsWord) }
      is(AtomicOp.Minu) { word := Mux(oldWord < rhsWord, oldWord, rhsWord) }
      is(AtomicOp.Maxu) { word := Mux(oldWord > rhsWord, oldWord, rhsWord) }
    }
    val dword = WireDefault(rhs)
    switch(op) {
      is(AtomicOp.Add) { dword := old + rhs }
      is(AtomicOp.Xor) { dword := old ^ rhs }
      is(AtomicOp.And) { dword := old & rhs }
      is(AtomicOp.Or) { dword := old | rhs }
      is(AtomicOp.Min) { dword := Mux(old.asSInt < rhs.asSInt, old, rhs) }
      is(AtomicOp.Max) { dword := Mux(old.asSInt > rhs.asSInt, old, rhs) }
      is(AtomicOp.Minu) { dword := Mux(old < rhs, old, rhs) }
      is(AtomicOp.Maxu) { dword := Mux(old > rhs, old, rhs) }
    }
    Mux(size === MemSize.Word, word.pad(64), dword)
  }
  private def age(index: UInt): UInt = (index - io.robHead.bits.index)(I - 1, 0)
  private def matchingSlot(token: ProductRobId): Vec[Bool] =
    VecInit(slots.map(s => s.valid && equal(s.token, token)))

  val loadCount = PopCount(slots.map(s => s.valid && s.kind === MemoryOperationKind.Load))
  // Atomic instructions reserve StoreQ capacity, including LR. Their
  // reservation and ordering effect must exist from ROB dispatch, before a
  // younger normal Load might otherwise issue out of order.
  val storeCount = PopCount(slots.map(s => s.valid &&
    (s.kind === MemoryOperationKind.Store || s.kind === MemoryOperationKind.Atomic)))
  val txnCount = PopCount(transactions.map(_.active))
  io.loadCount := loadCount
  io.storeCount := storeCount
  io.outstanding := txnCount

  val reserveKind = io.reserve.bits.kind
  val reserveLoad = reserveKind === MemoryOperationKind.Load
  val reserveStore = reserveKind === MemoryOperationKind.Store
  val reserveAtomic = reserveKind === MemoryOperationKind.Atomic
  val freeSlotMask = VecInit(slots.map(s => !s.valid)).asUInt
  val chosenSlot = PriorityEncoder(freeSlotMask)
  // Prevent a wrapped ROB generation from aliasing an external memory
  // transaction which is still leased after squash (memory latency unbounded).
  val outstandingSameLifetime = transactions.map { txn =>
    txn.active && txn.token.index === io.reserve.bits.token.index &&
      txn.token.generation === io.reserve.bits.token.generation
  }.reduce(_ || _)
  io.reserve.ready := (reserveLoad && loadCount < L.U ||
                       (reserveStore || reserveAtomic) && storeCount < S.U) &&
    freeSlotMask.orR && !matchingSlot(io.reserve.bits.token).asUInt.orR &&
    !outstandingSameLifetime &&
    !(heldRequest && equal(heldRequestToken, io.reserve.bits.token))
  when(io.reserve.fire) {
    assert(reserveLoad || reserveStore || reserveAtomic,
      "LSQ only reserves ordinary or atomic memory operations")
    when(reserveAtomic) {
      assert(io.reserve.bits.atomicOp =/= AtomicOp.None &&
        (io.reserve.bits.size === MemSize.Word || io.reserve.bits.size === MemSize.DWord),
        "decoded RV64 atomics must have a valid operation and W/D access width")
    }
    slots(chosenSlot) := 0.U.asTypeOf(slots(chosenSlot))
    slots(chosenSlot).valid := true.B
    slots(chosenSlot).token := io.reserve.bits.token
    slots(chosenSlot).kind := reserveKind
    slots(chosenSlot).size := io.reserve.bits.size
    slots(chosenSlot).unsigned := io.reserve.bits.unsigned
    slots(chosenSlot).atomicOp := io.reserve.bits.atomicOp
    slots(chosenSlot).destinationFile := io.reserve.bits.destinationFile
    slots(chosenSlot).destination := io.reserve.bits.destination
    slots(chosenSlot).hasDestination := io.reserve.bits.hasDestination
  }

  val resolveMatches = matchingSlot(io.resolve.bits.token)
  val resolveSlot = PriorityEncoder(resolveMatches.asUInt)
  private val resolving = slots(resolveSlot)
  // The MMU/translator may finish AFTER ROB squash and slot reuse. Drain
  // stale responses instead of backpressuring the resolver indefinitely.
  val resolutionLive = resolveMatches.asUInt.orR && !resolving.resolved &&
    !(io.kill.valid && equal(io.kill.bits, io.resolve.bits.token))
  io.resolve.ready := true.B // stale/duplicate results are drained, never assigned to a new lifetime
  when(io.resolve.fire && resolutionLive) {
    slots(resolveSlot).resolved := true.B
    slots(resolveSlot).virtualAddress := io.resolve.bits.virtualAddress
    slots(resolveSlot).paddr := io.resolve.bits.paddr
    slots(resolveSlot).storeData := io.resolve.bits.storeData
    slots(resolveSlot).attributes := io.resolve.bits.attributes
    slots(resolveSlot).fault := io.resolve.bits.fault
    val mask = bytesOf(resolving.size) - 1.U
    val misaligned = (io.resolve.bits.paddr & mask) =/= 0.U
    val atomic = resolving.kind === MemoryOperationKind.Atomic
    val atomicRead = atomic && resolving.atomicOp === AtomicOp.Lr
    val readLike = resolving.kind === MemoryOperationKind.Load || atomicRead
    val unsupportedAtomic = atomic && !io.resolve.bits.attributes.supportsAtomic
    // A PMA region without byte enables cannot accept a sub-word Store.
    // Fail closed before reaching AetherMem rather than widening a write or
    // silently relying on a downstream bridge to emulate read-modify-write.
    val unsupportedPartialStore = resolving.kind === MemoryOperationKind.Store &&
      resolving.size =/= MemSize.DWord && !io.resolve.bits.attributes.supportsPartial
    when(!io.resolve.bits.fault.valid) {
      when(misaligned) {
        slots(resolveSlot).fault.valid := true.B
        slots(resolveSlot).fault.cause := Mux(readLike,
          MachineExceptionCode.LoadAddressMisaligned.U,
          MachineExceptionCode.StoreAddressMisaligned.U)
        slots(resolveSlot).fault.value := io.resolve.bits.virtualAddress
      }.elsewhen(unsupportedAtomic) {
        slots(resolveSlot).fault.valid := true.B
        slots(resolveSlot).fault.cause := Mux(readLike,
          MachineExceptionCode.LoadAccessFault.U, MachineExceptionCode.StoreAccessFault.U)
        slots(resolveSlot).fault.value := io.resolve.bits.virtualAddress
      }.elsewhen(unsupportedPartialStore) {
        slots(resolveSlot).fault.valid := true.B
        slots(resolveSlot).fault.cause := MachineExceptionCode.StoreAccessFault.U
        slots(resolveSlot).fault.value := io.resolve.bits.virtualAddress
      }
    }
  }

  // ROB recovery kills the architectural owner, but an already issued read
  // keeps its leased physical transaction ID until its terminal response.
  io.droppedStaleResolution := io.resolve.fire && !resolutionLive
  when(io.kill.valid) {
    for (j <- 0 until T) {
      when(slots(j).valid && equal(slots(j).token, io.kill.bits)) {
        slots(j).valid := false.B
      }
    }
  }

  val freeTxn = VecInit(transactions.map(t => !t.active)).asUInt
  val hasTxn = freeTxn.orR
  val chosenTxn = PriorityEncoder(freeTxn)
  val eligible = Wire(Vec(T, Bool()))
  val forwardable = Wire(Vec(T, Bool()))
  val forwardOwner = Wire(Vec(T, UInt(TxBits.W)))
  val faulted = Wire(Vec(T, Bool()))

  // The compact physical entries are addressed by ROB *identity*, not slot
  // number. The dependency comparisons scale as (L+S)^2, independently of
  // ROB depth. The wide 64-bit forwarding data path is shared only after an
  // eligible instruction is selected, instead of replicated per comparison.
  for (j <- 0 until T) {
    val item = slots(j)
    val isHead = io.robHead.valid && equal(item.token, io.robHead.bits)
    val isLoad = item.kind === MemoryOperationKind.Load
    val isStore = item.kind === MemoryOperationKind.Store
    val isAtomic = item.kind === MemoryOperationKind.Atomic
    val normalRam = item.attributes.cacheable && item.attributes.idempotent &&
      !item.attributes.sideEffecting && !item.attributes.ordered
    val safeLoad = isHead || normalRam

    // Prefix reduction: feed-forward youngest overlapping older Store wins.
    // Unknown older Store blocks ALL younger loads until its address resolves.
    val olderStore = (0 until T).map { k =>
      slots(k).valid && slots(k).kind === MemoryOperationKind.Store &&
        age(slots(k).token.index) < age(item.token.index)
    }
    // An older LR/SC/AMO is a full memory-order barrier until it leaves ROB.
    // This conservatively implements aq/rl without permitting a younger RAM
    // Load to observe pre-atomic state or prematurely alter a reservation.
    val olderAtomic = (0 until T).map { k =>
      slots(k).valid && slots(k).kind === MemoryOperationKind.Atomic &&
        age(slots(k).token.index) < age(item.token.index)
    }.reduce(_ || _)
    val unknownOlder = (0 until T).map { k =>
      olderStore(k) && !slots(k).resolved
    }.reduce(_ || _)
    val intersects = (0 until T).map { k =>
      val previous = slots(k)
      olderStore(k) && previous.resolved &&
      alignedWord(previous.paddr) === alignedWord(item.paddr) &&
      (laneMask(previous.paddr, previous.size) & laneMask(item.paddr, item.size)).orR
    }
    var found: Bool = false.B
    var youngest: UInt = 0.U(I.W)
    var complete: Bool = false.B
    var source: UInt = 0.U(TxBits.W)
    for (k <- 0 until T) {
      val previous = slots(k)
      val mask = laneMask(item.paddr, item.size)
      val covered = (laneMask(previous.paddr, previous.size) & mask) === mask
      val previousAge = age(previous.token.index)
      val take = intersects(k) && (!found || previousAge >= youngest)
      complete = Mux(take, covered, complete)
      youngest = Mux(take, previousAge, youngest)
      source = Mux(take, k.U, source)
      found = found || intersects(k)
    }
    val localFault = item.fault.valid
    val forward = isLoad && found && complete && !unknownOlder &&
      !olderAtomic && !localFault && safeLoad
    val read = isLoad && !unknownOlder && !found && !olderAtomic && safeLoad
    val write = (isStore || isAtomic) && isHead && !localFault
    eligible(j) := item.valid && item.resolved && !item.launched &&
      (localFault || forward || read || write) && io.robHead.valid &&
      !io.kill.valid && (hasTxn || forward || localFault) &&
      // The offered request owns this ROB lifetime even before its physical
      // handshake. Excluding it from the tournament prevents a later changed
      // forwarding/fault candidate from completing the same Load a second
      // time while its stable external request remains backpressured.
      !(heldRequest && equal(item.token, heldRequestToken))
    forwardable(j) := forward
    forwardOwner(j) := source
    faulted(j) := localFault
  }

  // Balanced age tournament: the physical index is arbitrary, and candidate
  // selection must still choose the oldest eligible ROB lifetime across wrap.
  // A linear T-stage priority chain would turn a larger LQ/SQ into a deep
  // FPGA timing path, even though only one request may launch per cycle.
  case class AgeCandidate(valid: Bool, slot: UInt, distance: UInt)
  def earlier(a: AgeCandidate, b: AgeCandidate): AgeCandidate = {
    val takeB = b.valid && (!a.valid || b.distance < a.distance)
    AgeCandidate(a.valid || b.valid,
      Mux(takeB, b.slot, a.slot), Mux(takeB, b.distance, a.distance))
  }
  def oldest(xs: Seq[AgeCandidate]): AgeCandidate =
    if (xs.size == 1) xs.head
    else earlier(oldest(xs.take(xs.size / 2)), oldest(xs.drop(xs.size / 2)))
  val selected = oldest((0 until T).map { j =>
    AgeCandidate(eligible(j), j.U(TxBits.W), age(slots(j).token.index))
  })
  val candidateValid = selected.valid
  val candidateIndex = selected.slot
  val candidateForward = forwardable(candidateIndex)
  val candidateLocalFault = faulted(candidateIndex)

  private val candidate = slots(candidateIndex)
  private val forwardingStore = slots(forwardOwner(candidateIndex))
  val shiftedForward = (forwardingStore.storeData << (forwardingStore.paddr(2, 0) << 3))(63, 0)
  val candidateForwardData = shiftedForward >> (candidate.paddr(2, 0) << 3)
  val immediate = candidateForward || candidateLocalFault
  private val offeredRequest = Wire(new AetherMemRequest(paddrBits, 64, TxBits))
  offeredRequest := 0.U.asTypeOf(offeredRequest)
  offeredRequest.txnId := chosenTxn
  offeredRequest.op := Mux(candidate.kind === MemoryOperationKind.Atomic,
    AetherMemOp.Atomic, Mux(candidate.kind === MemoryOperationKind.Store,
      AetherMemOp.Write, AetherMemOp.Read))
  offeredRequest.paddr := candidate.paddr
  offeredRequest.size := candidate.size
  offeredRequest.wdata := candidate.storeData
  val writePayload = candidate.kind === MemoryOperationKind.Store ||
    (candidate.kind === MemoryOperationKind.Atomic && candidate.atomicOp =/= AtomicOp.Lr)
  offeredRequest.wmask := Mux(writePayload, accessMask(candidate.size), 0.U)
  offeredRequest.atomicOp := Mux(candidate.kind === MemoryOperationKind.Atomic,
    candidate.atomicOp, AtomicOp.None)
  offeredRequest.attributes := candidate.attributes

  io.memoryRequest.valid := heldRequest || (candidateValid && !immediate && hasTxn)
  io.memoryRequest.bits := Mux(heldRequest, heldRequestBits, offeredRequest)
  val launchSlot = Mux(heldRequest, heldRequestSlot, candidateIndex)
  val launchToken = Wire(new ProductRobId(I, G))
  launchToken := Mux(heldRequest, heldRequestToken, candidate.token)
  val launchTxn = io.memoryRequest.bits.txnId
  val launchLive = slots(launchSlot).valid &&
    equal(slots(launchSlot).token, launchToken) &&
    !(io.kill.valid && equal(io.kill.bits, launchToken))

  when(io.memoryRequest.valid && !io.memoryRequest.ready && !heldRequest) {
    heldRequest := true.B
    heldRequestBits := offeredRequest
    heldRequestSlot := candidateIndex
    heldRequestToken := candidate.token
  }

  when(io.memoryRequest.fire) {
    heldRequest := false.B
    assert(!transactions(launchTxn).active, "external txn ID is leased exactly once")
    transactions(launchTxn).active := true.B
    transactions(launchTxn).token := launchToken
    transactions(launchTxn).isLoad := io.memoryRequest.bits.op === AetherMemOp.Read ||
      (io.memoryRequest.bits.op === AetherMemOp.Atomic &&
        io.memoryRequest.bits.atomicOp === AtomicOp.Lr)
    when(launchLive) {
      slots(launchSlot).launched := true.B
    }.otherwise {
      // An idempotent speculative RAM Load may already be VALID when a
      // younger mispredicted branch squashes its ROB owner. Decoupled cannot
      // silently retract it: issue the unchanged read, then drain its reply
      // using the independent transaction lease, without a ROB completion.
      // Stores, atomics and device reads can only have been offered at head;
      // backend recovery is youngest-first and cannot kill that older head.
      assert(io.memoryRequest.bits.op === AetherMemOp.Read &&
        io.memoryRequest.bits.attributes.cacheable &&
        io.memoryRequest.bits.attributes.idempotent &&
        !io.memoryRequest.bits.attributes.sideEffecting &&
        !io.memoryRequest.bits.attributes.ordered,
        "only idempotent RAM reads may be issued after owner squash")
    }
    when(io.memoryRequest.bits.op === AetherMemOp.Write ||
      io.memoryRequest.bits.op === AetherMemOp.Atomic) {
      val sameHead = io.robHead.valid && equal(launchToken, io.robHead.bits)
      assert(launchLive && (heldRequest || sameHead),
        "Store/Atomic must be live and first offered at exact ROB head")
    }
  }

  // hasValue denotes the ROB's ALLOCATED destination identity, even on a
  // fault. The backend validates that identity and suppresses PRF writes when
  // exception.valid. Clearing hasValue on a fault would silently drop the
  // completion and deadlock precise trap handling.
  // Exactly one accepted completion per cycle; backpressure is held by Queue.
  private val completions = Module(new Queue(
    new ProductCompletion(64, I, P, G, paddrBits), entries = N, pipe = false, flow = false
  ))
  io.completion <> completions.io.deq
  completions.io.enq.valid := false.B
  completions.io.enq.bits := 0.U.asTypeOf(completions.io.enq.bits)
  io.forwarded := false.B
  io.drainedKilledResponse := false.B

  val responseIndex = io.memoryResponse.bits.txnId
  private val owner = transactions(responseIndex)
  private val responseMatches = matchingSlot(owner.token)
  private val responseSlot = PriorityEncoder(responseMatches.asUInt)
  private val returningSlot = slots(responseSlot)
  val matchingLive = responseMatches.asUInt.orR && returningSlot.launched &&
    !(io.kill.valid && equal(io.kill.bits, owner.token))
  io.memoryResponse.ready := !io.memoryResponse.valid || !owner.active ||
    !matchingLive || completions.io.enq.ready
  when(io.memoryResponse.fire) {
    assert(owner.active, "unknown/duplicate memory txn ID, do not deliver it to ROB")
    assert(io.memoryResponse.bits.last, "single-beat LSQ requires terminal response")
    transactions(responseIndex).active := false.B
    when(matchingLive) {
      slots(responseSlot).valid := false.B
      completions.io.enq.valid := true.B
      completions.io.enq.bits.token := owner.token
      completions.io.enq.bits.destinationFile := returningSlot.destinationFile
      completions.io.enq.bits.destination := returningSlot.destination
      completions.io.enq.bits.hasValue := returningSlot.hasDestination
      completions.io.enq.bits.value := Mux(io.memoryResponse.bits.fault,
        0.U, loadValue(io.memoryResponse.bits.rdata, returningSlot.size,
          returningSlot.unsigned, returningSlot.destinationFile))
      completions.io.enq.bits.exception.valid := io.memoryResponse.bits.fault
      completions.io.enq.bits.exception.cause := Mux(owner.isLoad,
        MachineExceptionCode.LoadAccessFault.U, MachineExceptionCode.StoreAccessFault.U)
      completions.io.enq.bits.exception.value := returningSlot.virtualAddress
      val successful = !io.memoryResponse.bits.fault
      val sc = returningSlot.kind === MemoryOperationKind.Atomic &&
        returningSlot.atomicOp === AtomicOp.Sc
      val atomicRmw = returningSlot.kind === MemoryOperationKind.Atomic &&
        returningSlot.atomicOp =/= AtomicOp.Lr && !sc
      val scSucceeded = sc && io.memoryResponse.bits.rdata === 0.U
      val physicalTrace = completions.io.enq.bits.memoryTrace
      // Failed SC never writes memory and, like the existing Tiny contract,
      // does not advertise a successful physical operation.
      physicalTrace.valid := successful && (!sc || scSucceeded)
      physicalTrace.write := successful &&
        (returningSlot.kind === MemoryOperationKind.Store || atomicRmw || scSucceeded)
      physicalTrace.paddr := returningSlot.paddr
      physicalTrace.wdata := Mux(atomicRmw,
        atomicWriteValue(io.memoryResponse.bits.rdata, returningSlot.storeData,
          returningSlot.size, returningSlot.atomicOp),
        Mux(physicalTrace.write, returningSlot.storeData, 0.U))
      physicalTrace.wmask := Mux(physicalTrace.write,
        accessMask(returningSlot.size), 0.U)
    }.otherwise {
      io.drainedKilledResponse := true.B
    }
  }

  // A forwarded Load / translation fault needs NO external memory transaction.
  // Physical responses win the single completion port; local completion waits.
  val responseWon = io.memoryResponse.fire && matchingLive
  when(candidateValid && immediate && !responseWon && completions.io.enq.ready) {
    slots(candidateIndex).valid := false.B
    completions.io.enq.valid := true.B
    completions.io.enq.bits.token := candidate.token
    completions.io.enq.bits.destinationFile := candidate.destinationFile
    completions.io.enq.bits.destination := candidate.destination
    completions.io.enq.bits.hasValue := candidate.hasDestination
    completions.io.enq.bits.value := Mux(candidate.fault.valid,
      0.U, loadValue(candidateForwardData, candidate.size,
        candidate.unsigned, candidate.destinationFile))
    completions.io.enq.bits.exception := candidate.fault
    completions.io.enq.bits.memoryTrace.valid := candidateForward && !candidate.fault.valid
    completions.io.enq.bits.memoryTrace.write := false.B
    completions.io.enq.bits.memoryTrace.paddr := candidate.paddr
    completions.io.enq.bits.memoryTrace.wdata := 0.U
    completions.io.enq.bits.memoryTrace.wmask := 0.U
    io.forwarded := candidateForward
  }

  // One source may write enq.valid per cycle: ensure a non-fault local result
  // does not mutate a completed or killed ROB lifetime after its owner leaves.
  when(io.memoryRequest.fire && !heldRequest) {
    assert(!candidateForward && !candidateLocalFault)
  }
  assert(loadCount <= L.U && storeCount <= S.U)
  assert(txnCount <= T.U)
}
