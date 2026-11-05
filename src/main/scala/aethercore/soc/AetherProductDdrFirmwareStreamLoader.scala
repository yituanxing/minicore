package aethercore.soc

import aethercore.core.product.{ProductCoreConfig, ProductCoreGeometry, ProductCoreProfiles}
import chisel3._
import chisel3.util._

/** A physical-address image segment furnished by a board-owned image source.
  *
  * Exactly two segments are accepted during one cold boot:
  *   1. OpenSBI fw_payload.bin at 0x80000000 (lastSegment = false);
  *   2. the separately generated flattened DTB at 0x87f00000
  *      (lastSegment = true).
  *
  * The byte count is the actual file length, not a padded AXI word count.
  */
class AetherProductDdrImageSegment(val addrBits: Int) extends Bundle {
  val address = UInt(addrBits.W)
  val byteCount = UInt((addrBits + 1).W)
  val lastSegment = Bool()
}

/** Little-endian, low-byte-lane-aligned source data for an image segment.
  *
  * All nonterminal words must have eight valid bytes. The final word may
  * have one through eight; its remaining high lanes are not written to DDR.
  */
class AetherProductDdrImageWord extends Bundle {
  val data = UInt(64.W)
  val validBytes = UInt(4.W)
  val lastWord = Bool()
}

/** Cold-boot FW+DTB byte stream -> DDR AXI4 single-beat loader.
  *
  * This *is* synthesizable loader hardware, not a simulation-only RAM poke.
  * An independent board transport (flash, SD, host, or JTAG) must supply the
  * two bounded segments and their bytes after DDR calibration. This module
  * deliberately does not invent a board-specific storage transport, PLL or
  * DDR PHY. Its enable input is the cold-boot controller's loader DDR grant.
  *
  * AW and W may be acknowledged independently. The loader never accepts
  * another source word until the corresponding B response is received with
  * ID zero and OKAY status. Consequently imageLoaded is asserted only after
  * the final DTB write response has really drained. Bad lengths, addresses,
  * byte masks, order, or DDR responses fail closed until a full board reset.
  */
class AetherProductDdrFirmwareStreamLoader(
    val addrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    val txnIdBits: Int = 4,
    val firmwareAddress: BigInt = BigInt("80000000", 16),
    val deviceTreeAddress: BigInt = BigInt("87f00000", 16),
    val maxDeviceTreeBytes: BigInt = BigInt("00100000", 16),
    val ramLimit: BigInt = BigInt("90000000", 16)
) extends Module {
  require(addrBits >= 32 && txnIdBits > 0)
  require(firmwareAddress >= 0 && firmwareAddress % 8 == 0)
  require(deviceTreeAddress > firmwareAddress && deviceTreeAddress % 8 == 0)
  require(maxDeviceTreeBytes > 0 && maxDeviceTreeBytes % 8 == 0)
  require(deviceTreeAddress + maxDeviceTreeBytes <= ramLimit)
  require(ramLimit <= (BigInt(1) << addrBits))

  val io = IO(new Bundle {
    val loaderEnabled = Input(Bool())
    val segment = Flipped(Decoupled(new AetherProductDdrImageSegment(addrBits)))
    val wordIn = Flipped(Decoupled(new AetherProductDdrImageWord))
    val axi = new Axi4MasterIO(addrBits, 64, txnIdBits)
    val imageLoaded = Output(Bool())
    val loaderIdle = Output(Bool())
    val imageLoadFailed = Output(Bool())
    val completedSegments = Output(UInt(2.W))
  })

  private val states = Enum(7)
  private val sFirmwareDescriptor = states(0)
  private val sDeviceTreeDescriptor = states(1)
  private val sReceiveWord = states(2)
  private val sIssueWrite = states(3)
  private val sWaitResponse = states(4)
  private val sDone = states(5)
  private val sFault = states(6)
  private val state = RegInit(sFirmwareDescriptor)

  private val segmentBytes = RegInit(0.U((addrBits + 1).W))
  private val writtenBytes = RegInit(0.U((addrBits + 1).W))
  private val writeAddress = RegInit(firmwareAddress.U(addrBits.W))
  private val writeData = Reg(UInt(64.W))
  private val writeMask = Reg(UInt(8.W))
  private val currentWordBytes = Reg(UInt(4.W))
  private val currentWordIsLast = Reg(Bool())
  private val currentSegmentIsLast = RegInit(false.B)
  private val awAccepted = RegInit(false.B)
  private val wAccepted = RegInit(false.B)
  private val completed = RegInit(0.U(2.W))

  io.completedSegments := completed
  io.imageLoaded := state === sDone
  // "idle" is intentionally NOT true while the stream is temporarily
  // empty: only two completed segments plus their final B permit handoff.
  io.loaderIdle := state === sDone
  io.imageLoadFailed := state === sFault

  private val needFirmware = state === sFirmwareDescriptor
  private val needDeviceTree = state === sDeviceTreeDescriptor
  io.segment.ready := io.loaderEnabled && (needFirmware || needDeviceTree)
  io.wordIn.ready := io.loaderEnabled && state === sReceiveWord

  private val expectedAddress =
    Mux(needFirmware, firmwareAddress.U(addrBits.W), deviceTreeAddress.U(addrBits.W))
  private val maxSegmentBytes =
    Mux(needFirmware,
      (deviceTreeAddress - firmwareAddress).U((addrBits + 1).W),
      maxDeviceTreeBytes.U((addrBits + 1).W))
  private val validSegment =
    io.segment.bits.address === expectedAddress &&
      io.segment.bits.byteCount > 0.U &&
      io.segment.bits.byteCount <= maxSegmentBytes &&
      io.segment.bits.lastSegment === needDeviceTree

  when(io.segment.fire) {
    when(!validSegment) {
      state := sFault
    }.otherwise {
      segmentBytes := io.segment.bits.byteCount
      writtenBytes := 0.U
      writeAddress := io.segment.bits.address
      currentSegmentIsLast := io.segment.bits.lastSegment
      state := sReceiveWord
    }
  }

  // All byte counts are checked BEFORE a request can reach DDR. The
  // descriptor bounds ensure the last rounded-up AXI word remains wholly
  // inside its reserved firmware or DTB physical-address aperture.
  private val validByteCount =
    io.wordIn.bits.validBytes >= 1.U && io.wordIn.bits.validBytes <= 8.U
  private val nextWrittenBytes = writtenBytes + io.wordIn.bits.validBytes
  private val coherentWordEnd =
    Mux(io.wordIn.bits.lastWord,
      nextWrittenBytes === segmentBytes,
      io.wordIn.bits.validBytes === 8.U && nextWrittenBytes < segmentBytes)
  private val validWord = validByteCount && nextWrittenBytes <= segmentBytes &&
    coherentWordEnd

  when(io.wordIn.fire) {
    when(!validWord) {
      state := sFault
    }.otherwise {
      writeData := io.wordIn.bits.data
      writeMask := VecInit((0 until 8).map(i =>
        io.wordIn.bits.validBytes > i.U)).asUInt
      currentWordBytes := io.wordIn.bits.validBytes
      currentWordIsLast := io.wordIn.bits.lastWord
      awAccepted := false.B
      wAccepted := false.B
      state := sIssueWrite
    }
  }

  io.axi.aw.valid := state === sIssueWrite && !awAccepted && io.loaderEnabled
  io.axi.aw.bits.id := 0.U
  io.axi.aw.bits.addr := writeAddress
  io.axi.aw.bits.len := 0.U
  io.axi.aw.bits.size := 3.U // eight-byte bus beat, partial final WSTRB
  io.axi.aw.bits.burst := Axi4Burst.Incr
  io.axi.aw.bits.lock := false.B
  io.axi.aw.bits.cache := 0.U // board loader writes uncached before CPU reset release
  io.axi.aw.bits.prot := 0.U
  io.axi.aw.bits.qos := 0.U

  io.axi.w.valid := state === sIssueWrite && !wAccepted && io.loaderEnabled
  io.axi.w.bits.data := writeData
  io.axi.w.bits.strb := writeMask
  io.axi.w.bits.last := true.B

  // Firmware loading does not read DDR, keeping response ownership trivially
  // attributable to the one single-beat write in progress.
  io.axi.ar.valid := false.B
  io.axi.ar.bits := 0.U.asTypeOf(io.axi.ar.bits)
  io.axi.r.ready := false.B
  io.axi.b.ready := state === sWaitResponse && io.loaderEnabled

  when(state === sIssueWrite) {
    when(!io.loaderEnabled) {
      state := sFault
    }.otherwise {
      when(io.axi.aw.fire) { awAccepted := true.B }
      when(io.axi.w.fire) { wAccepted := true.B }
      when((awAccepted || io.axi.aw.fire) && (wAccepted || io.axi.w.fire)) {
        state := sWaitResponse
      }
    }
  }

  when(state === sWaitResponse) {
    when(!io.loaderEnabled) {
      state := sFault
    }.elsewhen(io.axi.b.fire) {
      when(io.axi.b.bits.id =/= 0.U || io.axi.b.bits.resp =/= Axi4Resp.Okay) {
        state := sFault
      }.otherwise {
        writtenBytes := writtenBytes + currentWordBytes
        writeAddress := writeAddress + 8.U
        when(currentWordIsLast) {
          completed := completed + 1.U
          when(currentSegmentIsLast) {
            state := sDone
          }.otherwise {
            state := sDeviceTreeDescriptor
          }
        }.otherwise {
          state := sReceiveWord
        }
      }
    }
  }

  when((state === sReceiveWord || state === sIssueWrite ||
        state === sWaitResponse || state === sDeviceTreeDescriptor) &&
      !io.loaderEnabled) {
    // Losing the DDR grant mid-load cannot be recovered by a fresh segment
    // handshake. A full board reset must discard all possibly partial data.
    state := sFault
  }

  assert(completed <= 2.U, "firmware loader completed an impossible segment count")
  when(state === sDone) {
    assert(completed === 2.U, "imageLoaded requires fw_payload AND the standalone DTB")
  }
}

/** Synthesizable Product FPGA top joining the actual stream loader, the
  * loader/CPU DDR ownership arbiter, and the SPI-SD Linux runtime pins.
  *
  * The board still owns its byte-source controller, DDR PHY, PLL, and clock
  * domain synchronization. The stream protocol is fully decoupled/back-
  * pressured, so a slow SD/JTAG/flash reader cannot overrun the AXI fabric.
  */
class AetherCoreProductFpgaColdBootDdrStreamFabric(
    val implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry,
    val productConfigOverride: Option[ProductCoreConfig] = None,
    val boardDevices: ProductSoCBoardConfig = ProductSoCBoardProfiles.persistentSpiSd
) extends Module {
  require(boardDevices.spiSdEnabled,
    "The persistent-root FPGA stream wrapper needs the Product SPI-SD board profile")

  private val config = AetherCoreProductSoCProfiles.selectEngineeringTarget(
    implementedPaddrBits, productGeometry, productConfigOverride)
  private val txnBits =
    AetherCoreProductSoCProfiles.externalTransactionBits(config.geometry)
  private val addressMap = AetherSoCAddressMap.qualifiedLinux(config.platform)

  val io = IO(new Bundle {
    val segment = Flipped(Decoupled(new AetherProductDdrImageSegment(implementedPaddrBits)))
    val wordIn = Flipped(Decoupled(new AetherProductDdrImageWord))
    val ddrAxi = new Axi4MasterIO(implementedPaddrBits, 64, txnBits)
    val uartClockTick = Input(Bool())
    val timebaseTick = Input(Bool())
    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
    val spiSclk = Output(Bool())
    val spiMosi = Output(Bool())
    val spiMiso = Input(Bool())
    val spiCsN = Output(Bool())
    val clockLocked = Input(Bool())
    val ddrCalibrated = Input(Bool())
    val boardImageFailed = Input(Bool())
    val loaderOwnsDdr = Output(Bool())
    val loaderIdle = Output(Bool())
    val completedSegments = Output(UInt(2.W))
    val cpuReleased = Output(Bool())
    val bootFault = Output(Bool())
  })

  private val loader = Module(new AetherProductDdrFirmwareStreamLoader(
    addrBits = implementedPaddrBits,
    txnIdBits = txnBits,
    firmwareAddress = addressMap.ramBase,
    deviceTreeAddress = BigInt("87f00000", 16),
    ramLimit = addressMap.ramLimit))
  private val fabric = Module(new AetherCoreProductFpgaColdBootDdrFabric(
    implementedPaddrBits = implementedPaddrBits,
    productConfigOverride = Some(config),
    boardDevices = boardDevices))

  loader.io.loaderEnabled := fabric.io.loaderOwnsDdr
  loader.io.segment.valid := io.segment.valid
  loader.io.segment.bits := io.segment.bits
  io.segment.ready := loader.io.segment.ready
  loader.io.wordIn.valid := io.wordIn.valid
  loader.io.wordIn.bits := io.wordIn.bits
  io.wordIn.ready := loader.io.wordIn.ready

  fabric.io.imageLoaded := loader.io.imageLoaded
  fabric.io.loaderIdle := loader.io.loaderIdle
  fabric.io.imageLoadFailed := loader.io.imageLoadFailed || io.boardImageFailed
  fabric.io.clockLocked := io.clockLocked
  fabric.io.ddrCalibrated := io.ddrCalibrated
  io.loaderOwnsDdr := fabric.io.loaderOwnsDdr
  io.loaderIdle := loader.io.loaderIdle
  io.completedSegments := loader.io.completedSegments
  io.cpuReleased := fabric.io.cpuReleased
  io.bootFault := fabric.io.bootFault

  fabric.io.loaderAxi.aw.valid := loader.io.axi.aw.valid
  fabric.io.loaderAxi.aw.bits := loader.io.axi.aw.bits
  loader.io.axi.aw.ready := fabric.io.loaderAxi.aw.ready
  fabric.io.loaderAxi.w.valid := loader.io.axi.w.valid
  fabric.io.loaderAxi.w.bits := loader.io.axi.w.bits
  loader.io.axi.w.ready := fabric.io.loaderAxi.w.ready
  loader.io.axi.b.valid := fabric.io.loaderAxi.b.valid
  loader.io.axi.b.bits := fabric.io.loaderAxi.b.bits
  fabric.io.loaderAxi.b.ready := loader.io.axi.b.ready
  fabric.io.loaderAxi.ar.valid := loader.io.axi.ar.valid
  fabric.io.loaderAxi.ar.bits := loader.io.axi.ar.bits
  loader.io.axi.ar.ready := fabric.io.loaderAxi.ar.ready
  loader.io.axi.r.valid := fabric.io.loaderAxi.r.valid
  loader.io.axi.r.bits := fabric.io.loaderAxi.r.bits
  fabric.io.loaderAxi.r.ready := loader.io.axi.r.ready

  io.ddrAxi.aw.valid := fabric.io.ddrAxi.aw.valid
  io.ddrAxi.aw.bits := fabric.io.ddrAxi.aw.bits
  fabric.io.ddrAxi.aw.ready := io.ddrAxi.aw.ready
  io.ddrAxi.w.valid := fabric.io.ddrAxi.w.valid
  io.ddrAxi.w.bits := fabric.io.ddrAxi.w.bits
  fabric.io.ddrAxi.w.ready := io.ddrAxi.w.ready
  fabric.io.ddrAxi.b.valid := io.ddrAxi.b.valid
  fabric.io.ddrAxi.b.bits := io.ddrAxi.b.bits
  io.ddrAxi.b.ready := fabric.io.ddrAxi.b.ready
  io.ddrAxi.ar.valid := fabric.io.ddrAxi.ar.valid
  io.ddrAxi.ar.bits := fabric.io.ddrAxi.ar.bits
  fabric.io.ddrAxi.ar.ready := io.ddrAxi.ar.ready
  fabric.io.ddrAxi.r.valid := io.ddrAxi.r.valid
  fabric.io.ddrAxi.r.bits := io.ddrAxi.r.bits
  io.ddrAxi.r.ready := fabric.io.ddrAxi.r.ready

  fabric.io.uartClockTick := io.uartClockTick
  fabric.io.timebaseTick := io.timebaseTick
  fabric.io.serialRx := io.serialRx
  io.serialTx := fabric.io.serialTx
  fabric.io.spiMiso.get := io.spiMiso
  io.spiSclk := fabric.io.spiSclk.get
  io.spiMosi := fabric.io.spiMosi.get
  io.spiCsN := fabric.io.spiCsN.get
}
