package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.MemSize
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}

/**
  * Small read-only reset ROM at the unified AetherMem boundary.
  *
  * The v0 image performs only the architectural handoff:
  *
  *   addi  t0, x0, 1
  *   slli  t0, t0, 31      // 0x0000000080000000
  *   jalr  x0, t0, 0       // qualified OpenSBI payload in external RAM
  *
  * Keeping the ROM as a normal AetherMem target means instruction fetches,
  * data reads and future debugger accesses all observe one physical map.
  */
class AetherSoCBootRom(
    val addrBits: Int,
    val dataBits: Int,
    val txnIdBits: Int,
    val baseAddress: BigInt,
    val apertureBytes: BigInt
) extends Module {
  require(dataBits == 64, "AetherSoC v0 BootROM uses the RV64 memory beat")
  require(txnIdBits > 0)
  require(apertureBytes >= 12)

  private val beatBytes = dataBits / 8
  private val image = Seq(
    0x93, 0x02, 0x10, 0x00, // addi x5, x0, 1
    0x93, 0x92, 0xf2, 0x01, // slli x5, x5, 31
    0x67, 0x80, 0x02, 0x00  // jalr x0, x5, 0
  )

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits)))
    val response = Decoupled(new AetherMemResponse(dataBits, txnIdBits))
  })

  private val active = RegInit(false.B)
  private val txnId = Reg(UInt(txnIdBits.W))
  private val op = Reg(AetherMemOp())
  private val address = Reg(UInt(addrBits.W))
  private val size = Reg(MemSize())

  io.request.ready := !active
  when(io.request.fire) {
    active := true.B
    txnId := io.request.bits.txnId
    op := io.request.bits.op
    address := io.request.bits.paddr
    size := io.request.bits.size
  }

  private val byteCount = WireDefault(1.U(4.W))
  when(size === MemSize.Half) { byteCount := 2.U }
  when(size === MemSize.Word) { byteCount := 4.U }
  when(size === MemSize.DWord) { byteCount := 8.U }

  private val base = baseAddress.U(addrBits.W)
  private val limit = (baseAddress + apertureBytes).U((addrBits + 1).W)
  private val requestEnd = address +& byteCount
  private val inWindow =
    address >= base && requestEnd <= limit

  private val offset = address - base

  private def imageByte(index: UInt): UInt =
    MuxLookup(
      index,
      0.U(8.W)
    )(
      image.zipWithIndex.map { case (value, i) =>
        i.U -> value.U(8.W)
      }
    )

  private val bytes = Wire(Vec(beatBytes, UInt(8.W)))
  for (i <- 0 until beatBytes) {
    bytes(i) := imageByte(offset + i.U)
  }
  private val readData = Cat(bytes.reverse)

  io.response.valid := active
  io.response.bits.txnId := txnId
  io.response.bits.rdata := readData
  io.response.bits.fault := op =/= AetherMemOp.Read || !inWindow
  io.response.bits.last := true.B

  when(io.response.fire) {
    active := false.B
  }
}
