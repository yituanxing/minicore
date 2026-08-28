package aethercore.sim

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.soc.{Axi4Burst, Axi4MasterIO, Axi4Resp}

/**
  * Simulation-only AXI4 target adapter that preserves the historical host-memory
  * seams used by the qualified OpenSBI/Linux runner.
  *
  * It is deliberately outside the production SoC. Its purpose is to force
  * dynamic Linux traffic through the real AetherMem->AXI4 bridge while still
  * reusing the already-qualified host RAM implementation.
  *
  * AXI ID high bits preserve the MemoryHub source tag:
  *   0 = data/D-cache, 1 = PTW, 2 = instruction.
  * The production bridge has already resolved LR/SC/AMO into ordinary AXI
  * reads/writes, so the host adapter itself never implements atomics.
  */
class AetherSoCAxi4HostMemoryAdapter(
    val addrBits: Int = 56,
    val dataBits: Int = 64,
    val idBits: Int = 4,
    val localTxnIdBits: Int = 2
) extends Module {
  require(dataBits == 64)
  require(idBits > localTxnIdBits)

  private val BusBytes = dataBits / 8
  private val SourceBits = idBits - localTxnIdBits
  private val DataSource = 0
  private val PtwSource = 1
  private val InstructionSource = 2

  val io = IO(new Bundle {
    val axi = Flipped(new Axi4MasterIO(addrBits, dataBits, idBits))

    val imemValid = Output(Bool())
    val imemAddr = Output(UInt(addrBits.W))
    val imemBytes = Output(UInt(3.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

    val ptwValid = Output(Bool())
    val ptwAddr = Output(UInt(addrBits.W))
    val ptwReady = Input(Bool())
    val ptwRdata = Input(UInt(64.W))
    val ptwFault = Input(Bool())

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAtomic = Output(Bool())
    val memOp = Output(AetherMemOp())
    val memAtomicOp = Output(AtomicOp())
    val memAddr = Output(UInt(addrBits.W))
    val memWdata = Output(UInt(dataBits.W))
    val memWmask = Output(UInt(BusBytes.W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(dataBits.W))
    val memFault = Input(Bool())
  })

  private def memSizeFromAxi(size: UInt): MemSize.Type = {
    val result = WireDefault(MemSize.DWord)
    switch(size) {
      is(0.U) { result := MemSize.Byte }
      is(1.U) { result := MemSize.Half }
      is(2.U) { result := MemSize.Word }
      is(3.U) { result := MemSize.DWord }
    }
    result
  }

  // --------------------------------------------------------------------------
  // AXI read channel -> one historical host read source
  // --------------------------------------------------------------------------
  private val readActive = RegInit(false.B)
  private val readId = Reg(UInt(idBits.W))
  private val readAddr = Reg(UInt(addrBits.W))
  private val readSize = Reg(UInt(3.W))

  io.axi.ar.ready := !readActive
  when(io.axi.ar.fire) {
    assert(io.axi.ar.bits.len === 0.U, "AXI host adapter accepts one-beat reads only")
    assert(io.axi.ar.bits.burst === Axi4Burst.Incr,
      "AXI host adapter expects incrementing single-beat reads")
    readActive := true.B
    readId := io.axi.ar.bits.id
    readAddr := io.axi.ar.bits.addr
    readSize := io.axi.ar.bits.size
  }

  private val readSource = readId(idBits - 1, localTxnIdBits)
  private val readByteOffset = readAddr(log2Ceil(BusBytes) - 1, 0)
  private val readBitShift = readByteOffset << 3

  io.imemValid := readActive && readSource === InstructionSource.U
  io.imemAddr := readAddr
  io.imemBytes := Mux(readSize === 1.U, 2.U, Mux(readSize === 2.U, 4.U, 0.U))

  io.ptwValid := readActive && readSource === PtwSource.U
  io.ptwAddr := readAddr

  private val dataReadActive = readActive && readSource === DataSource.U

  private val readTerminal = MuxLookup(
    readSource,
    false.B
  )(
    Seq(
      DataSource.U -> io.memReady,
      PtwSource.U -> io.ptwReady,
      InstructionSource.U -> true.B
    )
  )

  private val readFault = MuxLookup(
    readSource,
    true.B
  )(
    Seq(
      DataSource.U -> io.memFault,
      PtwSource.U -> io.ptwFault,
      InstructionSource.U -> io.imemFault
    )
  )

  private val semanticReadData = MuxLookup(
    readSource,
    0.U(dataBits.W)
  )(
    Seq(
      DataSource.U -> io.memRdata,
      PtwSource.U -> io.ptwRdata,
      InstructionSource.U -> io.imemInst.pad(dataBits)
    )
  )
  private val laneReadDataWide = semanticReadData << readBitShift

  io.axi.r.valid := readActive && readTerminal
  io.axi.r.bits.id := readId
  io.axi.r.bits.data := laneReadDataWide(dataBits - 1, 0)
  io.axi.r.bits.resp := Mux(readFault, Axi4Resp.SlvErr, Axi4Resp.Okay)
  io.axi.r.bits.last := true.B

  when(io.axi.r.fire) {
    assert(readSource <= InstructionSource.U,
      "AXI host adapter received an unknown read source tag")
    readActive := false.B
  }

  // --------------------------------------------------------------------------
  // AXI AW/W channels -> historical data-memory write
  // --------------------------------------------------------------------------
  private val awActive = RegInit(false.B)
  private val awId = Reg(UInt(idBits.W))
  private val awAddr = Reg(UInt(addrBits.W))
  private val awSize = Reg(UInt(3.W))

  private val wActive = RegInit(false.B)
  private val wData = Reg(UInt(dataBits.W))
  private val wStrb = Reg(UInt(BusBytes.W))

  private val bValid = RegInit(false.B)
  private val bId = Reg(UInt(idBits.W))
  private val bResp = Reg(UInt(2.W))

  io.axi.aw.ready := !awActive && !bValid
  io.axi.w.ready := !wActive && !bValid

  when(io.axi.aw.fire) {
    assert(io.axi.aw.bits.len === 0.U, "AXI host adapter accepts one-beat writes only")
    assert(io.axi.aw.bits.burst === Axi4Burst.Incr,
      "AXI host adapter expects incrementing single-beat writes")
    assert(
      io.axi.aw.bits.id(idBits - 1, localTxnIdBits) === DataSource.U,
      "only data/D-cache traffic may issue AXI writes"
    )
    awActive := true.B
    awId := io.axi.aw.bits.id
    awAddr := io.axi.aw.bits.addr
    awSize := io.axi.aw.bits.size
  }

  when(io.axi.w.fire) {
    assert(io.axi.w.bits.last, "AXI host adapter accepts one-beat write data only")
    wActive := true.B
    wData := io.axi.w.bits.data
    wStrb := io.axi.w.bits.strb
  }

  private val writeActive = awActive && wActive
  private val writeByteOffset = awAddr(log2Ceil(BusBytes) - 1, 0)
  private val writeBitShift = writeByteOffset << 3
  private val lowWriteData = wData >> writeBitShift
  private val lowWriteMask = wStrb >> writeByteOffset

  // One legacy data port is shared by AXI data reads and writes. The production
  // bridge itself is one-transaction-at-a-time, so these cannot contend.
  io.memValid := dataReadActive || writeActive
  io.memWrite := writeActive
  io.memAtomic := false.B
  io.memOp := Mux(writeActive, AetherMemOp.Write, AetherMemOp.Read)
  io.memAtomicOp := AtomicOp.None
  io.memAddr := Mux(writeActive, awAddr, readAddr)
  io.memWdata := Mux(writeActive, lowWriteData(dataBits - 1, 0), 0.U)
  io.memWmask := Mux(writeActive, lowWriteMask(BusBytes - 1, 0), 0.U)
  io.memSize := Mux(writeActive, memSizeFromAxi(awSize), memSizeFromAxi(readSize))

  when(writeActive && io.memReady && !bValid) {
    bValid := true.B
    bId := awId
    bResp := Mux(io.memFault, Axi4Resp.SlvErr, Axi4Resp.Okay)
    awActive := false.B
    wActive := false.B
  }

  io.axi.b.valid := bValid
  io.axi.b.bits.id := bId
  io.axi.b.bits.resp := bResp

  when(io.axi.b.fire) {
    bValid := false.B
  }

  when(readActive) {
    assert(readSource <= InstructionSource.U,
      "AXI host adapter read ID must contain a known MemoryHub source tag")
  }
}
