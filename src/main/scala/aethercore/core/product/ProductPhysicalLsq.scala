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
  val tokenGeneration = UInt(generationBits.W)
  val kind = MemoryOperationKind()
  val size = MemSize()
  val unsigned = Bool()
  val atomicOp = AtomicOp()
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
    val completion = Decoupled(new ProductCompletion(64, I, P, G))
    val loadCount = Output(UInt(log2Ceil(L + 1).W))
    val storeCount = Output(UInt(log2Ceil(S + 1).W))
    val outstanding = Output(UInt(log2Ceil(T + 1).W))
    val forwarded = Output(Bool())
    val drainedKilledResponse = Output(Bool())
  })

  private val slots = RegInit(VecInit(Seq.fill(N)(
    0.U.asTypeOf(new ProductMemSlot(paddrBits, I, P, G)))))
  private val transactions = RegInit(VecInit(Seq.fill(T)(
    0.U.asTypeOf(new ProductTxnOwner(I, G)))))
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
  private def age(index: UInt): UInt = (index - io.robHead.bits.index)(I - 1, 0)

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
  val reserveIndex = io.reserve.bits.token.index
  // Prevent a wrapped ROB generation from aliasing an external memory
  // transaction which is still leased after squash (memory latency unbounded).
  val outstandingSameLifetime = transactions.map { txn =>
    txn.active && txn.token.index === io.reserve.bits.token.index &&
      txn.token.generation === io.reserve.bits.token.generation
  }.reduce(_ || _)
  io.reserve.ready := (reserveLoad && loadCount < L.U ||
                       (reserveStore || reserveAtomic) && storeCount < S.U) &&
    !slots(reserveIndex).valid && !outstandingSameLifetime
  when(io.reserve.fire) {
    assert(reserveLoad || reserveStore || reserveAtomic,
      "LSQ only reserves ordinary or atomic memory operations")
    when(reserveAtomic) {
      assert(io.reserve.bits.atomicOp =/= AtomicOp.None &&
        (io.reserve.bits.size === MemSize.Word || io.reserve.bits.size === MemSize.DWord),
        "decoded RV64 atomics must have a valid operation and W/D access width")
    }
    slots(reserveIndex) := 0.U.asTypeOf(slots(reserveIndex))
    slots(reserveIndex).valid := true.B
    slots(reserveIndex).tokenGeneration := io.reserve.bits.token.generation
    slots(reserveIndex).kind := reserveKind
    slots(reserveIndex).size := io.reserve.bits.size
    slots(reserveIndex).unsigned := io.reserve.bits.unsigned
    slots(reserveIndex).atomicOp := io.reserve.bits.atomicOp
    slots(reserveIndex).destination := io.reserve.bits.destination
    slots(reserveIndex).hasDestination := io.reserve.bits.hasDestination
  }

  val resolveIndex = io.resolve.bits.token.index
  private val resolving = slots(resolveIndex)
  // The MMU/translator may finish AFTER ROB squash and slot reuse. Drain
  // stale responses instead of backpressuring the resolver indefinitely.
  val resolutionLive = resolving.valid && !resolving.resolved &&
    resolving.tokenGeneration === io.resolve.bits.token.generation &&
    !(io.kill.valid && equal(io.kill.bits, io.resolve.bits.token))
  io.resolve.ready := true.B // stale/duplicate results are drained, never assigned to a new lifetime
  when(io.resolve.fire && resolutionLive) {
    slots(resolveIndex).resolved := true.B
    slots(resolveIndex).virtualAddress := io.resolve.bits.virtualAddress
    slots(resolveIndex).paddr := io.resolve.bits.paddr
    slots(resolveIndex).storeData := io.resolve.bits.storeData
    slots(resolveIndex).attributes := io.resolve.bits.attributes
    slots(resolveIndex).fault := io.resolve.bits.fault
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
        slots(resolveIndex).fault.valid := true.B
        slots(resolveIndex).fault.cause := Mux(readLike,
          MachineExceptionCode.LoadAddressMisaligned.U,
          MachineExceptionCode.StoreAddressMisaligned.U)
        slots(resolveIndex).fault.value := io.resolve.bits.virtualAddress
      }.elsewhen(unsupportedAtomic) {
        slots(resolveIndex).fault.valid := true.B
        slots(resolveIndex).fault.cause := Mux(readLike,
          MachineExceptionCode.LoadAccessFault.U, MachineExceptionCode.StoreAccessFault.U)
        slots(resolveIndex).fault.value := io.resolve.bits.virtualAddress
      }.elsewhen(unsupportedPartialStore) {
        slots(resolveIndex).fault.valid := true.B
        slots(resolveIndex).fault.cause := MachineExceptionCode.StoreAccessFault.U
        slots(resolveIndex).fault.value := io.resolve.bits.virtualAddress
      }
    }
  }

  // ROB recovery kills the architectural owner, but an already issued read
  // keeps its leased physical transaction ID until its terminal response.
  io.droppedStaleResolution := io.resolve.fire && !resolutionLive
  when(io.kill.valid) {
    val killed = slots(io.kill.bits.index)
    when(killed.valid && killed.tokenGeneration === io.kill.bits.generation) {
      slots(io.kill.bits.index).valid := false.B
    }
  }

  val freeTxn = VecInit(transactions.map(t => !t.active)).asUInt
  val hasTxn = freeTxn.orR
  val chosenTxn = PriorityEncoder(freeTxn)
  val eligible = Wire(Vec(N, Bool()))
  val forwardable = Wire(Vec(N, Bool()))
  val forwardedData = Wire(Vec(N, UInt(64.W)))
  val faulted = Wire(Vec(N, Bool()))

  // Each record is indexed by architectural ROB slot and has its own
  // generation. Age selection is a pure feed-forward network: we never feed
  // a mutable Wire back into its own enable / priority condition.
  for (j <- 0 until N) {
    val item = slots(j)
    val token = Wire(new ProductRobId(I, G))
    token.index := j.U
    token.generation := item.tokenGeneration
    val isHead = io.robHead.valid && equal(token, io.robHead.bits)
    val isLoad = item.kind === MemoryOperationKind.Load
    val isStore = item.kind === MemoryOperationKind.Store
    val isAtomic = item.kind === MemoryOperationKind.Atomic
    val normalRam = item.attributes.cacheable && item.attributes.idempotent &&
      !item.attributes.sideEffecting && !item.attributes.ordered
    val safeLoad = isHead || normalRam

    // Prefix reduction: feed-forward youngest overlapping older Store wins.
    // Unknown older Store blocks ALL younger loads until its address resolves.
    val olderStore = (0 until N).map { k =>
      slots(k).valid && slots(k).kind === MemoryOperationKind.Store &&
        age(k.U) < age(j.U)
    }
    // An older LR/SC/AMO is a full memory-order barrier until it leaves ROB.
    // This conservatively implements aq/rl without permitting a younger RAM
    // Load to observe pre-atomic state or prematurely alter a reservation.
    val olderAtomic = (0 until N).map { k =>
      slots(k).valid && slots(k).kind === MemoryOperationKind.Atomic &&
        age(k.U) < age(j.U)
    }.reduce(_ || _)
    val unknownOlder = (0 until N).map { k =>
      olderStore(k) && !slots(k).resolved
    }.reduce(_ || _)
    val intersects = (0 until N).map { k =>
      val previous = slots(k)
      olderStore(k) && previous.resolved &&
      alignedWord(previous.paddr) === alignedWord(item.paddr) &&
      (laneMask(previous.paddr, previous.size) & laneMask(item.paddr, item.size)).orR
    }
    var found: Bool = false.B
    var youngest: UInt = 0.U(I.W)
    var complete: Bool = false.B
    var data: UInt = 0.U(64.W)
    for (k <- 0 until N) {
      val previous = slots(k)
      val mask = laneMask(item.paddr, item.size)
      val covered = (laneMask(previous.paddr, previous.size) & mask) === mask
      val shiftedStore = (previous.storeData << (previous.paddr(2, 0) << 3))(63, 0)
      val result = shiftedStore >> (item.paddr(2, 0) << 3)
      val take = intersects(k) && (!found || age(k.U) >= youngest)
      data = Mux(take, result, data)
      complete = Mux(take, covered, complete)
      youngest = Mux(take, age(k.U), youngest)
      found = found || intersects(k)
    }
    val localFault = item.fault.valid
    val forward = isLoad && found && complete && !unknownOlder &&
      !olderAtomic && !localFault && safeLoad
    val read = isLoad && !unknownOlder && !found && !olderAtomic && safeLoad
    val write = (isStore || isAtomic) && isHead && !localFault
    eligible(j) := item.valid && item.resolved && !item.launched &&
      (localFault || forward || read || write) && io.robHead.valid &&
      !io.kill.valid && (hasTxn || forward || localFault)
    forwardable(j) := forward
    forwardedData(j) := data
    faulted(j) := localFault
  }

  // Rotate by actual ROB head; selecting a physical slot number would give
  // incorrect age priority across head/tail wrap.
  val rotated = Wire(Vec(N, Bool()))
  for (offset <- 0 until N) {
    val index = (io.robHead.bits.index + offset.U)(I - 1, 0)
    rotated(offset) := eligible(index)
  }
  val candidateValid = rotated.asUInt.orR
  val candidateOffset = PriorityEncoder(rotated.asUInt)
  val candidateIndex = (io.robHead.bits.index + candidateOffset)(I - 1, 0)
  val candidateForward = forwardable(candidateIndex)
  val candidateForwardData = forwardedData(candidateIndex)
  val candidateLocalFault = faulted(candidateIndex)

  private val candidate = slots(candidateIndex)
  val immediate = candidateForward || candidateLocalFault
  io.memoryRequest.valid := candidateValid && !immediate && hasTxn
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.txnId := chosenTxn
  io.memoryRequest.bits.op := Mux(candidate.kind === MemoryOperationKind.Atomic,
    AetherMemOp.Atomic, Mux(candidate.kind === MemoryOperationKind.Store,
      AetherMemOp.Write, AetherMemOp.Read))
  io.memoryRequest.bits.paddr := candidate.paddr
  io.memoryRequest.bits.size := candidate.size
  io.memoryRequest.bits.wdata := candidate.storeData
  val writePayload = candidate.kind === MemoryOperationKind.Store ||
    (candidate.kind === MemoryOperationKind.Atomic && candidate.atomicOp =/= AtomicOp.Lr)
  io.memoryRequest.bits.wmask := Mux(writePayload, accessMask(candidate.size), 0.U)
  io.memoryRequest.bits.atomicOp := Mux(candidate.kind === MemoryOperationKind.Atomic,
    candidate.atomicOp, AtomicOp.None)
  io.memoryRequest.bits.attributes := candidate.attributes

  when(io.memoryRequest.fire) {
    assert(!transactions(chosenTxn).active, "external txn ID is leased exactly once")
    transactions(chosenTxn).active := true.B
    transactions(chosenTxn).token.index := candidateIndex
    transactions(chosenTxn).token.generation := candidate.tokenGeneration
    transactions(chosenTxn).isLoad := candidate.kind === MemoryOperationKind.Load ||
      (candidate.kind === MemoryOperationKind.Atomic && candidate.atomicOp === AtomicOp.Lr)
    slots(candidateIndex).launched := true.B
    when(candidate.kind === MemoryOperationKind.Store ||
      candidate.kind === MemoryOperationKind.Atomic) {
      val sameHead = io.robHead.valid && candidateIndex === io.robHead.bits.index &&
        candidate.tokenGeneration === io.robHead.bits.generation
      assert(sameHead, "Store/Atomic must NEVER externalize before exact ROB head")
    }
  }

  // hasValue denotes the ROB's ALLOCATED destination identity, even on a
  // fault. The backend validates that identity and suppresses PRF writes when
  // exception.valid. Clearing hasValue on a fault would silently drop the
  // completion and deadlock precise trap handling.
  // Exactly one accepted completion per cycle; backpressure is held by Queue.
  private val completions = Module(new Queue(
    new ProductCompletion(64, I, P, G), entries = N, pipe = false, flow = false
  ))
  io.completion <> completions.io.deq
  completions.io.enq.valid := false.B
  completions.io.enq.bits := 0.U.asTypeOf(completions.io.enq.bits)
  io.forwarded := false.B
  io.drainedKilledResponse := false.B

  val responseIndex = io.memoryResponse.bits.txnId
  private val owner = transactions(responseIndex)
  private val returningSlot = slots(owner.token.index)
  val matchingLive = returningSlot.valid && returningSlot.launched &&
    returningSlot.tokenGeneration === owner.token.generation &&
    !(io.kill.valid && equal(io.kill.bits, owner.token))
  io.memoryResponse.ready := !io.memoryResponse.valid || !owner.active ||
    !matchingLive || completions.io.enq.ready
  when(io.memoryResponse.fire) {
    assert(owner.active, "unknown/duplicate memory txn ID, do not deliver it to ROB")
    assert(io.memoryResponse.bits.last, "single-beat LSQ requires terminal response")
    transactions(responseIndex).active := false.B
    when(matchingLive) {
      slots(owner.token.index).valid := false.B
      completions.io.enq.valid := true.B
      completions.io.enq.bits.token := owner.token
      completions.io.enq.bits.destination := returningSlot.destination
      completions.io.enq.bits.hasValue := returningSlot.hasDestination
      completions.io.enq.bits.value := extended(io.memoryResponse.bits.rdata,
        returningSlot.size, returningSlot.unsigned)
      completions.io.enq.bits.exception.valid := io.memoryResponse.bits.fault
      completions.io.enq.bits.exception.cause := Mux(owner.isLoad,
        MachineExceptionCode.LoadAccessFault.U, MachineExceptionCode.StoreAccessFault.U)
      completions.io.enq.bits.exception.value := returningSlot.virtualAddress
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
    completions.io.enq.bits.token.index := candidateIndex
    completions.io.enq.bits.token.generation := candidate.tokenGeneration
    completions.io.enq.bits.destination := candidate.destination
    completions.io.enq.bits.hasValue := candidate.hasDestination
    completions.io.enq.bits.value := extended(candidateForwardData, candidate.size, candidate.unsigned)
    completions.io.enq.bits.exception := candidate.fault
    io.forwarded := candidateForward
  }

  // One source may write enq.valid per cycle: ensure a non-fault local result
  // does not mutate a completed or killed ROB lifetime after its owner leaves.
  when(io.memoryRequest.fire) {
    assert(!candidateForward && !candidateLocalFault)
  }
  assert(loadCount <= L.U && storeCount <= S.U)
  assert(txnCount <= T.U)
}
