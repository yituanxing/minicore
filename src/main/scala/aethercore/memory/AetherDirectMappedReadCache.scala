package aethercore.memory

import chisel3._
import chisel3.util._
import aethercore.common.MemSize

/**
  * Stage-1 direct-mapped data cache on the AetherMem boundary.
  *
  * This cache is intentionally conservative:
  *   - only ordinary cacheable/idempotent/non-side-effecting Reads allocate;
  *   - Writes are write-through and invalidate a matching cached line;
  *   - Atomics always bypass and invalidate a matching cached line;
  *   - MMIO/non-cacheable traffic always bypasses;
  *   - the original AetherMem txnId is preserved end-to-end.
  *
  * Each line is exactly one downstream data beat. Byte-valid state allows a
  * narrow Byte/Half/Word miss to populate only the bytes that were actually
  * authorized and fetched; the cache therefore never widens the physical
  * access behind PMP/PMA merely to fill a line.
  */
class AetherDirectMappedReadCache(
    val addrBits: Int,
    val dataBits: Int,
    val txnIdBits: Int,
    val entries: Int = 64
) extends Module {
  require(addrBits > 0, s"cache address width must be positive, got $addrBits")
  require(dataBits == 32 || dataBits == 64,
    s"stage-1 cache data width must be 32 or 64, got $dataBits")
  require(txnIdBits > 0, s"cache txnId width must be positive, got $txnIdBits")
  require(entries >= 2 && (entries & (entries - 1)) == 0,
    s"cache entries must be a power of two >=2, got $entries")

  private val BeatBytes = dataBits / 8
  private val OffsetBits = log2Ceil(BeatBytes)
  private val IndexBits = log2Ceil(entries)
  private val TagBits = addrBits - OffsetBits - IndexBits
  private val TxnCount = 1 << txnIdBits
  // Four bits retain the architectural DWord=8B value even for a 32-bit beat;
  // such an access will fail reqFitsLine rather than being width-truncated.
  private val AccessBytesBits = 4

  require(TagBits > 0,
    s"cache tag width must stay positive: addr=$addrBits entries=$entries beatBytes=$BeatBytes")

  val io = IO(new Bundle {
    val upstreamRequest =
      Flipped(Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits)))
    val upstreamResponse =
      Decoupled(new AetherMemResponse(dataBits, txnIdBits))

    val downstreamRequest =
      Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits))
    val downstreamResponse =
      Flipped(Decoupled(new AetherMemResponse(dataBits, txnIdBits)))

    val hitCount = Output(UInt(64.W))
    val missCount = Output(UInt(64.W))
    val bypassCount = Output(UInt(64.W))
  })

  private def mergeBytes(oldValue: UInt, newValue: UInt, mask: UInt): UInt =
    Cat((0 until BeatBytes).reverse.map { byte =>
      val high = byte * 8 + 7
      val low = byte * 8
      Mux(mask(byte), newValue(high, low), oldValue(high, low))
    })

  private val lineValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  // Keep the large cache payload/tag/byte-valid arrays in asynchronous Mem
  // form so FPGA synthesis can infer LUTRAM/BRAM-friendly storage instead of
  // expanding every entry into flip-flops plus wide dynamic-index muxes.
  // lineValid remains resettable register state; when it is false, the
  // uninitialized contents of these memories are architecturally irrelevant.
  private val lineTag = Mem(entries, UInt(TagBits.W))
  private val lineData = Mem(entries, UInt(dataBits.W))
  private val lineByteValid = Mem(entries, UInt(BeatBytes.W))
  // Incremented by write/atomic traffic to conservatively suppress an older
  // read fill from being installed after a later writer touched this index.
  private val lineEpoch =
    RegInit(VecInit(Seq.fill(entries)(0.U(4.W))))

  private val outstanding =
    RegInit(VecInit(Seq.fill(TxnCount)(false.B)))
  private val fillValid =
    RegInit(VecInit(Seq.fill(TxnCount)(false.B)))
  private val fillIndex = Reg(Vec(TxnCount, UInt(IndexBits.W)))
  private val fillTag = Reg(Vec(TxnCount, UInt(TagBits.W)))
  private val fillOffset = Reg(Vec(TxnCount, UInt(OffsetBits.W)))
  private val fillMask = Reg(Vec(TxnCount, UInt(BeatBytes.W)))
  private val fillEpoch = Reg(Vec(TxnCount, UInt(4.W)))

  private val req = io.upstreamRequest.bits
  private val reqIndex =
    req.paddr(OffsetBits + IndexBits - 1, OffsetBits)
  private val reqTag =
    req.paddr(addrBits - 1, OffsetBits + IndexBits)
  private val reqOffset = req.paddr(OffsetBits - 1, 0)

  private val reqAccessBytes = Wire(UInt(AccessBytesBits.W))
  reqAccessBytes := 1.U
  switch(req.size) {
    is(MemSize.Byte)  { reqAccessBytes := 1.U }
    is(MemSize.Half)  { reqAccessBytes := 2.U }
    is(MemSize.Word)  { reqAccessBytes := 4.U }
    is(MemSize.DWord) { reqAccessBytes := 8.U }
  }

  private val reqBaseMask = Wire(UInt(BeatBytes.W))
  reqBaseMask := 1.U
  switch(req.size) {
    is(MemSize.Byte)  { reqBaseMask := 1.U }
    is(MemSize.Half)  { reqBaseMask := 3.U }
    is(MemSize.Word)  {
      reqBaseMask := ((BigInt(1) << math.min(4, BeatBytes)) - 1).U
    }
    is(MemSize.DWord) {
      reqBaseMask := ((BigInt(1) << BeatBytes) - 1).U
    }
  }

  private val reqMask =
    (reqBaseMask << reqOffset)(BeatBytes - 1, 0)
  private val reqFitsLine =
    (reqOffset +& reqAccessBytes) <= BeatBytes.U

  private val ordinaryCacheableRead =
    req.op === AetherMemOp.Read &&
      req.attributes.cacheable &&
      req.attributes.idempotent &&
      !req.attributes.sideEffecting &&
      !req.attributes.ordered &&
      reqFitsLine

  private val tagHit =
    lineValid(reqIndex) && lineTag(reqIndex) === reqTag
  private val byteHit =
    (lineByteValid(reqIndex) & reqMask) === reqMask
  private val readHit = ordinaryCacheableRead && tagHit && byteHit

  private val hitResponseValid = RegInit(false.B)
  private val hitResponseBits =
    Reg(new AetherMemResponse(dataBits, txnIdBits))

  private val responses = Module(new RRArbiter(
    new AetherMemResponse(dataBits, txnIdBits),
    2
  ))
  responses.io.in(0).valid := hitResponseValid
  responses.io.in(0).bits := hitResponseBits
  responses.io.in(1).valid := io.downstreamResponse.valid
  responses.io.in(1).bits := io.downstreamResponse.bits
  io.downstreamResponse.ready := responses.io.in(1).ready
  io.upstreamResponse <> responses.io.out

  private val hitResponseFire = responses.io.in(0).fire
  private val hitCanAccept = !hitResponseValid || hitResponseFire

  io.downstreamRequest.valid := io.upstreamRequest.valid && !readHit
  io.downstreamRequest.bits := io.upstreamRequest.bits
  io.upstreamRequest.ready :=
    Mux(readHit, hitCanAccept, io.downstreamRequest.ready)

  private val hitRequestFire = io.upstreamRequest.fire && readHit
  private val forwardedRequestFire = io.downstreamRequest.fire

  private val shiftedHitData =
    (lineData(reqIndex) >> (reqOffset << 3))(dataBits - 1, 0)

  when(hitRequestFire) {
    hitResponseValid := true.B
    hitResponseBits.txnId := req.txnId
    hitResponseBits.rdata := shiftedHitData
    hitResponseBits.fault := false.B
    hitResponseBits.last := true.B
  }.elsewhen(hitResponseFire) {
    hitResponseValid := false.B
  }

  private val hitCounter = RegInit(0.U(64.W))
  private val missCounter = RegInit(0.U(64.W))
  private val bypassCounter = RegInit(0.U(64.W))

  when(hitRequestFire) {
    hitCounter := hitCounter + 1.U
  }
  when(forwardedRequestFire && ordinaryCacheableRead) {
    missCounter := missCounter + 1.U
  }
  when(forwardedRequestFire && !ordinaryCacheableRead) {
    bypassCounter := bypassCounter + 1.U
  }

  io.hitCount := hitCounter
  io.missCount := missCounter
  io.bypassCount := bypassCounter

  when(forwardedRequestFire) {
    val txn = req.txnId
    assert(!outstanding(txn),
      "AetherDCache observed txnId reuse before terminal response")
    outstanding(txn) := true.B

    val fillCandidate = ordinaryCacheableRead
    fillValid(txn) := fillCandidate
    when(fillCandidate) {
      fillIndex(txn) := reqIndex
      fillTag(txn) := reqTag
      fillOffset(txn) := reqOffset
      fillMask(txn) := reqMask
      fillEpoch(txn) := lineEpoch(reqIndex)
    }

    when(req.op =/= AetherMemOp.Read) {
      // Writers/atomics remain fully owned by downstream memory. Invalidate
      // matching state immediately so no later read can consume stale bytes
      // while the write is in flight.
      lineEpoch(reqIndex) := lineEpoch(reqIndex) + 1.U
      when(lineValid(reqIndex) && lineTag(reqIndex) === reqTag) {
        lineValid(reqIndex) := false.B
        lineByteValid.write(reqIndex, 0.U)
      }
    }
  }

  private val downstreamResponseFire = io.downstreamResponse.fire
  private val responseTxn = io.downstreamResponse.bits.txnId
  when(downstreamResponseFire) {
    assert(outstanding(responseTxn),
      "AetherDCache received terminal response for a non-outstanding txnId")
    outstanding(responseTxn) := false.B

    val canFill =
      fillValid(responseTxn) &&
        io.downstreamResponse.bits.last &&
        !io.downstreamResponse.bits.fault &&
        lineEpoch(fillIndex(responseTxn)) === fillEpoch(responseTxn)

    when(canFill) {
      val index = fillIndex(responseTxn)
      val tag = fillTag(responseTxn)
      val sameLine = lineValid(index) && lineTag(index) === tag
      val oldData = Mux(sameLine, lineData(index), 0.U)
      val oldMask = Mux(sameLine, lineByteValid(index), 0.U)
      val shifted =
        (io.downstreamResponse.bits.rdata << (fillOffset(responseTxn) << 3))(
          dataBits - 1, 0
        )

      lineValid(index) := true.B
      lineTag.write(index, tag)
      lineData.write(index, mergeBytes(oldData, shifted, fillMask(responseTxn)))
      lineByteValid.write(index, oldMask | fillMask(responseTxn))
    }
    fillValid(responseTxn) := false.B
  }

  // Atomic operations are never satisfied from cached state in stage 1.
  when(io.upstreamRequest.valid && req.op === AetherMemOp.Atomic) {
    assert(!readHit, "atomic AetherMem operation must never hit stage-1 D-cache")
  }
}
