package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}

/**
  * Direct-mapped instruction cache for the FPGA-oriented unified-memory path.
  *
  * Key contract:
  *   - a cache hit is presented to the frontend in the same cycle;
  *   - a miss becomes one registered AetherMem read lifetime;
  *   - redirect/cancel may replace the frontend PA while a miss is in flight;
  *     the old response is consumed/fills the cache but is not presented as the
  *     new instruction;
  *   - FENCE.I invalidates all cached instruction bytes at the architectural
  *     retirement boundary exported by TinyPagedCore;
  *   - narrow 2B/4B fetches fill only the bytes actually fetched, so the cache
  *     never widens a physical instruction access behind PMP/PMA.
  *
  * Each cache line is one AetherMem beat (8B on RV64 v0). The large data/tag/
  * byte-valid arrays use asynchronous Mem storage so FPGA synthesis can infer
  * LUTRAM/BRAM-friendly structures while preserving same-cycle hit lookup.
  */
class AetherSoCInstructionCache(
    val addrBits: Int = 56,
    val dataBits: Int = 64,
    val txnIdBits: Int = 2,
    val entries: Int = 64
) extends Module {
  require(dataBits == 64, "AetherSoC v0 I-cache currently targets a 64-bit memory beat")
  require(txnIdBits > 0)
  require(entries >= 2 && (entries & (entries - 1)) == 0)

  private val BeatBytes = dataBits / 8
  private val OffsetBits = log2Ceil(BeatBytes)
  private val IndexBits = log2Ceil(entries)
  private val TagBits = addrBits - OffsetBits - IndexBits

  val io = IO(new Bundle {
    val frontendValid = Input(Bool())
    val frontendAddr = Input(UInt(addrBits.W))
    val frontendBytes = Input(UInt(3.W))
    val frontendReady = Output(Bool())
    val frontendInst = Output(UInt(32.W))
    val frontendFault = Output(Bool())

    val invalidateAll = Input(Bool())

    val request = Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits))
    val response = Flipped(Decoupled(new AetherMemResponse(dataBits, txnIdBits)))

    val hitCount = Output(UInt(64.W))
    val missCount = Output(UInt(64.W))
  })

  private def mergeBytes(oldValue: UInt, newValue: UInt, mask: UInt): UInt =
    Cat((0 until BeatBytes).reverse.map { byte =>
      val high = byte * 8 + 7
      val low = byte * 8
      Mux(mask(byte), newValue(high, low), oldValue(high, low))
    })

  private val lineValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val lineTag = Mem(entries, UInt(TagBits.W))
  private val lineData = Mem(entries, UInt(dataBits.W))
  private val lineByteValid = Mem(entries, UInt(BeatBytes.W))

  private val active = RegInit(false.B)
  private val missAddr = Reg(UInt(addrBits.W))
  private val missIndex = Reg(UInt(IndexBits.W))
  private val missTag = Reg(UInt(TagBits.W))
  private val missOffset = Reg(UInt(OffsetBits.W))
  private val missMask = Reg(UInt(BeatBytes.W))
  private val missFillValid = RegInit(false.B)

  private val reqOffset = io.frontendAddr(OffsetBits - 1, 0)
  private val reqIndex =
    io.frontendAddr(OffsetBits + IndexBits - 1, OffsetBits)
  private val reqTag =
    io.frontendAddr(addrBits - 1, OffsetBits + IndexBits)

  private val requestSize = WireDefault(MemSize.Word)
  private val requestBytes = WireDefault(4.U(4.W))
  private val baseMask = WireDefault("b00001111".U(BeatBytes.W))
  when(io.frontendBytes === 2.U) {
    requestSize := MemSize.Half
    requestBytes := 2.U
    baseMask := "b00000011".U
  }

  private val reqMask = (baseMask << reqOffset)(BeatBytes - 1, 0)
  private val reqFitsLine = (reqOffset +& requestBytes) <= BeatBytes.U

  private val tagHit =
    lineValid(reqIndex) && lineTag(reqIndex) === reqTag
  private val byteHit =
    (lineByteValid(reqIndex) & reqMask) === reqMask
  private val hit =
    io.frontendValid && reqFitsLine && tagHit && byteHit && !io.invalidateAll

  private val hitData =
    (lineData(reqIndex) >> (reqOffset << 3))(31, 0)

  private val activeMatches =
    io.frontendValid && io.frontendAddr === missAddr

  // Hit response is intentionally combinational. A miss response is safe to
  // expose only for the exact PA that still owns the frontend request.
  io.frontendReady :=
    hit || (active && io.response.valid && activeMatches)
  io.frontendInst :=
    Mux(hit, hitData, io.response.bits.rdata(31, 0))
  io.frontendFault :=
    Mux(hit, false.B, active && io.response.valid && activeMatches && io.response.bits.fault)

  io.request.valid := io.frontendValid && !hit && !active && !io.invalidateAll
  io.request.bits.txnId := 0.U
  io.request.bits.op := AetherMemOp.Read
  io.request.bits.paddr := io.frontendAddr
  io.request.bits.size := requestSize
  io.request.bits.wdata := 0.U
  io.request.bits.wmask := 0.U
  io.request.bits.atomicOp := AtomicOp.None
  io.request.bits.attributes.cacheable := true.B
  io.request.bits.attributes.idempotent := true.B
  io.request.bits.attributes.sideEffecting := false.B
  io.request.bits.attributes.ordered := false.B
  io.request.bits.attributes.executable := true.B
  io.request.bits.attributes.supportsAtomic := false.B
  io.request.bits.attributes.supportsPartial := true.B

  io.response.ready := active

  when(io.request.fire) {
    active := true.B
    missAddr := io.frontendAddr
    missIndex := reqIndex
    missTag := reqTag
    missOffset := reqOffset
    missMask := reqMask
    missFillValid := reqFitsLine
  }

  when(io.response.fire) {
    assert(io.response.bits.txnId === 0.U,
      "I-cache received a response for the wrong local txnId")
    assert(io.response.bits.last,
      "I-cache requires one terminal response per instruction miss")

    when(missFillValid && !io.response.bits.fault && !io.invalidateAll) {
      val sameLine = lineValid(missIndex) && lineTag(missIndex) === missTag
      val oldData = Mux(sameLine, lineData(missIndex), 0.U)
      val oldMask = Mux(sameLine, lineByteValid(missIndex), 0.U)
      val shifted =
        (io.response.bits.rdata << (missOffset << 3))(dataBits - 1, 0)

      lineValid(missIndex) := true.B
      lineTag.write(missIndex, missTag)
      lineData.write(missIndex, mergeBytes(oldData, shifted, missMask))
      lineByteValid.write(missIndex, oldMask | missMask)
    }

    active := false.B
    missFillValid := false.B
  }

  when(io.invalidateAll) {
    for (index <- 0 until entries) {
      lineValid(index) := false.B
    }
    // An already-issued miss may still terminate, but it must not refill after
    // the retiring FENCE.I boundary.
    missFillValid := false.B
  }

  private val hitCounter = RegInit(0.U(64.W))
  private val missCounter = RegInit(0.U(64.W))
  private val observedHit = hit && io.frontendReady
  when(observedHit) {
    hitCounter := hitCounter + 1.U
  }
  when(io.request.fire) {
    missCounter := missCounter + 1.U
  }
  io.hitCount := hitCounter
  io.missCount := missCounter

  when(io.frontendValid && !active) {
    assert(io.frontendBytes === 2.U || io.frontendBytes === 4.U,
      "I-cache frontend supports only 2-byte parcels or 4-byte instructions")
  }
}
