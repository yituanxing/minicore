package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.{
  AetherMemOp,
  AetherMemRequest,
  AetherMemResponse,
  MemoryAttributes
}

/**
  * Adapter from the historical terminal-response data-memory port to a true
  * Decoupled AetherMem client.
  *
  * The legacy ready signal means "the transaction has completed", not request
  * acceptance. Therefore request acceptance and terminal response are tracked
  * separately here.
  */
class AetherSoCLegacyDataAdapter(
    val addrBits: Int,
    val dataBits: Int,
    val txnIdBits: Int = 2
) extends Module {
  val io = IO(new Bundle {
    val legacyValid = Input(Bool())
    val legacyOp = Input(AetherMemOp())
    val legacyAtomicOp = Input(AtomicOp())
    val legacyAddr = Input(UInt(addrBits.W))
    val legacyWdata = Input(UInt(dataBits.W))
    val legacyWmask = Input(UInt((dataBits / 8).W))
    val legacySize = Input(MemSize())
    val legacyAttributes = Input(new MemoryAttributes)

    val legacyReady = Output(Bool())
    val legacyRdata = Output(UInt(dataBits.W))
    val legacyFault = Output(Bool())

    val request = Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits))
    val response = Flipped(Decoupled(new AetherMemResponse(dataBits, txnIdBits)))
  })

  private val active = RegInit(false.B)

  io.request.valid := io.legacyValid && !active
  io.request.bits.txnId := 0.U
  io.request.bits.op := io.legacyOp
  io.request.bits.paddr := io.legacyAddr
  io.request.bits.size := io.legacySize
  io.request.bits.wdata := io.legacyWdata
  io.request.bits.wmask := io.legacyWmask
  io.request.bits.atomicOp := io.legacyAtomicOp
  io.request.bits.attributes := io.legacyAttributes

  when(io.request.fire) {
    active := true.B
  }

  io.response.ready := active
  io.legacyReady := active && io.response.valid
  io.legacyRdata := io.response.bits.rdata
  io.legacyFault := io.response.bits.fault

  when(io.response.fire) {
    assert(io.response.bits.txnId === 0.U,
      "legacy data adapter received a response for the wrong local txnId")
    assert(io.response.bits.last,
      "legacy data adapter requires one terminal response per request")
    active := false.B
  }
}

/**
  * Converts the frontend instruction-read seam into a Decoupled AetherMem
  * client. At most one physical fetch transaction is outstanding in this first
  * adapter.
  *
  * Redirects may replace the frontend address while a response is in flight.
  * Such a response is consumed and discarded unless the current physical
  * request still matches the latched request address.
  */
class AetherSoCInstructionReadAdapter(
    val addrBits: Int,
    val dataBits: Int = 64,
    val txnIdBits: Int = 2
) extends Module {
  require(dataBits >= 32)

  val io = IO(new Bundle {
    val legacyValid = Input(Bool())
    val legacyAddr = Input(UInt(addrBits.W))
    val legacyBytes = Input(UInt(3.W))

    val legacyReady = Output(Bool())
    val legacyInst = Output(UInt(32.W))
    val legacyFault = Output(Bool())

    val request = Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits))
    val response = Flipped(Decoupled(new AetherMemResponse(dataBits, txnIdBits)))
  })

  private val active = RegInit(false.B)
  private val requestAddr = Reg(UInt(addrBits.W))

  private val requestSize = WireDefault(MemSize.Word)
  when(io.legacyBytes === 2.U) {
    requestSize := MemSize.Half
  }

  io.request.valid := io.legacyValid && !active
  io.request.bits.txnId := 0.U
  io.request.bits.op := AetherMemOp.Read
  io.request.bits.paddr := io.legacyAddr
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

  when(io.request.fire) {
    active := true.B
    requestAddr := io.legacyAddr
  }

  private val currentRequestMatches =
    io.legacyValid && io.legacyAddr === requestAddr

  io.response.ready := active
  io.legacyReady := active && io.response.valid && currentRequestMatches
  io.legacyInst := io.response.bits.rdata(31, 0)
  io.legacyFault := io.response.bits.fault

  when(io.legacyValid && !active) {
    assert(io.legacyBytes === 2.U || io.legacyBytes === 4.U,
      "instruction adapter supports only 2-byte parcels or 4-byte instructions")
  }

  when(io.response.fire) {
    assert(io.response.bits.txnId === 0.U,
      "instruction adapter received a response for the wrong local txnId")
    assert(io.response.bits.last,
      "instruction adapter requires one terminal response per request")
    active := false.B
  }
}

/**
  * Converts the implicit page-table read seam into a Decoupled AetherMem
  * client. A canceled/replaced page walk cannot consume a stale PTE response:
  * the adapter drops any response whose address no longer matches the active
  * PTW request.
  */
class AetherSoCPtwReadAdapter(
    val addrBits: Int,
    val dataBits: Int = 64,
    val pteBits: Int = 64,
    val txnIdBits: Int = 2
) extends Module {
  require(dataBits >= pteBits)
  require(pteBits == 32 || pteBits == 64)

  val io = IO(new Bundle {
    val legacyValid = Input(Bool())
    val legacyAddr = Input(UInt(addrBits.W))

    val legacyReady = Output(Bool())
    val legacyRdata = Output(UInt(pteBits.W))
    val legacyFault = Output(Bool())

    val request = Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits))
    val response = Flipped(Decoupled(new AetherMemResponse(dataBits, txnIdBits)))
  })

  private val active = RegInit(false.B)
  private val requestAddr = Reg(UInt(addrBits.W))

  io.request.valid := io.legacyValid && !active
  io.request.bits.txnId := 0.U
  io.request.bits.op := AetherMemOp.Read
  io.request.bits.paddr := io.legacyAddr
  io.request.bits.size := (if (pteBits == 64) MemSize.DWord else MemSize.Word)
  io.request.bits.wdata := 0.U
  io.request.bits.wmask := 0.U
  io.request.bits.atomicOp := AtomicOp.None
  io.request.bits.attributes.cacheable := true.B
  io.request.bits.attributes.idempotent := true.B
  io.request.bits.attributes.sideEffecting := false.B
  io.request.bits.attributes.ordered := false.B
  io.request.bits.attributes.executable := false.B
  io.request.bits.attributes.supportsAtomic := false.B
  io.request.bits.attributes.supportsPartial := true.B

  when(io.request.fire) {
    active := true.B
    requestAddr := io.legacyAddr
  }

  private val currentRequestMatches =
    io.legacyValid && io.legacyAddr === requestAddr

  io.response.ready := active
  io.legacyReady := active && io.response.valid && currentRequestMatches
  io.legacyRdata := io.response.bits.rdata(pteBits - 1, 0)
  io.legacyFault := io.response.bits.fault

  when(io.response.fire) {
    assert(io.response.bits.txnId === 0.U,
      "PTW adapter received a response for the wrong local txnId")
    assert(io.response.bits.last,
      "PTW adapter requires one terminal response per request")
    active := false.B
  }
}
