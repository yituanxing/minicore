package aethercore.soc

import aethercore.core.product.{ProductCoreConfig, ProductCoreGeometry, ProductCoreProfiles}
import aethercore.soc.phy.AetherFractionalTickGenerator
import chisel3._
import chisel3.util._

/** Translate the Product CPU's *physical* DDR address window to the zero-based
  * byte-addressed AXI aperture provided by a board DDR controller.
  *
  * Product's fixed Linux RAM is 0x80000000..0x8fffffff. Many FPGA DDR AXI
  * ports instead expose 0..0x0fffffff. The translation must occur strictly
  * after the SoC's MMIO/RAM decoder, and after cold-boot ownership selection:
  * the firmware loader and the CPU then observe exactly the same memory.
  *
  * Both Product masters emit single-beat transactions. AW and W are
  * independently handshaken (either may be accepted first) and the adapter
  * permits one outstanding write; AR/R may overlap writes and each other.
  * Invalid addresses or protocol are HARD-REJECTED and set
  * stickyFatal. A board wrapper must feed this into the cold-boot fault/reset
  * path: it would be unsafe to alias an illegal access into valid DDR by
  * truncating its high address bits. The fault is intentionally *not* a
  * software DECERR: it indicates a violated board/hardware contract and
  * requires a fresh board reset rather than attempted CPU continuation.
  *
  * DDR PHY/controller, CDC between unrelated AXI clocks, and vendor-specific
  * ID/interconnect width conversion remain the physical board's property.
  */
class AetherProductDdrLocalAxiWindow(
    val physicalAddrBits: Int,
    val localAddrBits: Int,
    val txnIdBits: Int,
    val physicalBase: BigInt = BigInt("80000000", 16),
    val windowBytes: BigInt = BigInt("10000000", 16)
) extends Module {
  require(physicalAddrBits >= 32 && localAddrBits >= 3 && txnIdBits > 0)
  require(localAddrBits <= physicalAddrBits,
    "the DDR local aperture cannot exceed its physical AXI address width")
  require(physicalBase >= 0 && windowBytes > 0)
  require(physicalBase % 8 == 0 && windowBytes % 8 == 0)
  require(physicalBase + windowBytes <= (BigInt(1) << physicalAddrBits))
  require(windowBytes <= (BigInt(1) << localAddrBits),
    "the local DDR AXI byte aperture must represent the full software RAM")

  val io = IO(new Bundle {
    val physical = Flipped(new Axi4MasterIO(physicalAddrBits, 64, txnIdBits))
    val local = new Axi4MasterIO(localAddrBits, 64, txnIdBits)
    val stickyFatal = Output(Bool())
  })

  private val stickyFatal = RegInit(false.B)
  io.stickyFatal := stickyFatal
  private val base = physicalBase.U((physicalAddrBits + 1).W)
  private val limit = (physicalBase + windowBytes).U((physicalAddrBits + 1).W)

  private def validAddress(a: Axi4Address): Bool = {
    val addr = Cat(0.U(1.W), a.addr)
    val bytes = 1.U((physicalAddrBits + 1).W) << a.size
    val offsetMask = ((1.U(8.W) << a.size) - 1.U)(2, 0)
    val aligned = (a.addr(2, 0) & offsetMask) === 0.U
    a.len === 0.U && a.size <= 3.U && a.burst === Axi4Burst.Incr &&
      !a.lock && aligned &&
      addr >= base && addr + bytes <= limit
  }

  private def translate(in: Axi4Address, out: Axi4Address): Unit = {
    out.id := in.id
    out.addr := (in.addr - physicalBase.U(physicalAddrBits.W))(localAddrBits - 1, 0)
    out.len := in.len
    out.size := in.size
    out.burst := in.burst
    out.lock := in.lock
    out.cache := in.cache
    out.prot := in.prot
    out.qos := in.qos
  }

  private val awAllowed = validAddress(io.physical.aw.bits)
  private val arAddressAllowed = validAddress(io.physical.ar.bits)

  // Concurrent Product RAM reads may complete out of order. Exactly one AR
  // per AXI ID is live; no DDR R may be forwarded unless that ID was actually
  // accepted, and single-beat ARs must terminate with RLAST. This is the
  // read-channel counterpart of the AW/W/B ledger below. DDR protocol faults
  // are board-fatal, not arbitrary AetherMem data returned to Linux.
  private val readOutstanding =
    RegInit(VecInit(Seq.fill(1 << txnIdBits)(false.B)))
  private val arSlotFree = !readOutstanding(io.physical.ar.bits.id)
  private val arAllowed = arAddressAllowed && arSlotFree
  // A duplicate-ID AR held VALID while waiting for its predecessor is legal
  // backpressure, not an accepted protocol fault. Only malformed addresses
  // are board-fatal; the slot ledger prevents accepting the duplicate until
  // its earlier R has retired.
  // AXI AW and W have *independent* READY/VALID handshakes. A vendor DDR IP
  // may legally withhold AWREADY until WVALID is asserted. Requiring AW.fire
  // before asserting WVALID would therefore deadlock otherwise valid traffic.
  //
  // Both current single-beat Product masters keep AWVALID and WVALID stable
  // until separately accepted, and issue no second write until B. Forward W
  // only while a VALID, address-checked AW is presented (not necessarily
  // accepted yet), or after that AW has been accepted. This allows either
  // channel to handshake first while never exposing W for an illegal AW.
  // The two per-channel acceptance latches block a second beat/address until
  // the unique response has retired. They are reset only on B or board reset.
  private val awAccepted = RegInit(false.B)
  private val wAccepted = RegInit(false.B)
  private val writeId = RegInit(0.U(txnIdBits.W))
  // For a narrow single-beat AXI write, WSTRB may select only the byte lanes
  // described by AWADDR/AWSIZE. Keep the mask after AW fires so that W may
  // legally arrive later, and use the presented AW for W-before-AW traffic.
  // An all-zero WSTRB remains a legal AXI null write.
  private def writeLaneMask(a: Axi4Address): UInt = {
    val sizedMask = MuxLookup(a.size, 0.U(8.W))(Seq(
      0.U -> "h01".U(8.W), 1.U -> "h03".U(8.W),
      2.U -> "h0f".U(8.W), 3.U -> "hff".U(8.W)))
    (sizedMask << a.addr(2, 0))(7, 0)
  }
  private val acceptedWriteLaneMask = RegInit(0.U(8.W))
  private val validAwPresented = io.physical.aw.valid && awAllowed && !stickyFatal
  private val writeDataHasLegalAddress = awAccepted || validAwPresented
  private val legalWriteLanes = Mux(awAccepted,
    acceptedWriteLaneMask, writeLaneMask(io.physical.aw.bits))
  private val wLaneLegal = (io.physical.w.bits.strb & ~legalWriteLanes) === 0.U
  private val wAllowed = io.physical.w.bits.last && wLaneLegal
  when((io.physical.aw.valid && !awAllowed) ||
       (io.physical.ar.valid && !arAddressAllowed) ||
       (io.physical.w.valid && (!io.physical.w.bits.last ||
         (writeDataHasLegalAddress && !wLaneLegal)))) {
    stickyFatal := true.B
  }
  io.local.aw.valid := validAwPresented && !awAccepted
  translate(io.physical.aw.bits, io.local.aw.bits)
  io.physical.aw.ready := io.local.aw.ready && awAllowed && !stickyFatal && !awAccepted
  when(io.physical.aw.fire) {
    awAccepted := true.B
    writeId := io.physical.aw.bits.id
    acceptedWriteLaneMask := writeLaneMask(io.physical.aw.bits)
  }

  io.local.w.valid := io.physical.w.valid && wAllowed && !stickyFatal &&
    writeDataHasLegalAddress && !wAccepted
  io.local.w.bits := io.physical.w.bits
  io.physical.w.ready := io.local.w.ready && wAllowed && !stickyFatal &&
    writeDataHasLegalAddress && !wAccepted
  when(io.physical.w.fire) {
    wAccepted := true.B
  }

  // A hardware-contract fault discards eventual DDR responses after the
  // board has withdrawn CPU ownership; do not let response backpressure
  // deadlock a controller while the physical reset sequence begins.
  private val coherentWriteResponse =
    awAccepted && wAccepted && io.local.b.bits.id === writeId
  when(io.local.b.valid && !coherentWriteResponse) {
    stickyFatal := true.B
  }
  io.physical.b.valid := io.local.b.valid && !stickyFatal && coherentWriteResponse
  io.physical.b.bits := io.local.b.bits
  io.local.b.ready := Mux(coherentWriteResponse,
    io.physical.b.ready || stickyFatal, true.B)
  when(io.local.b.fire) {
    awAccepted := false.B
    wAccepted := false.B
  }

  io.local.ar.valid := io.physical.ar.valid && arAllowed && !stickyFatal
  translate(io.physical.ar.bits, io.local.ar.bits)
  io.physical.ar.ready := io.local.ar.ready && arAllowed && !stickyFatal

  private val arFire = io.physical.ar.fire
  private val returningReadId = io.local.r.bits.id
  // A legal zero-latency slave may assert R in the cycle AR handshakes.
  private val returningReadKnown = readOutstanding(returningReadId) ||
    (arFire && io.physical.ar.bits.id === returningReadId)
  private val coherentReadResponse = returningReadKnown && io.local.r.bits.last
  when(io.local.r.valid && !coherentReadResponse) {
    stickyFatal := true.B
  }
  io.physical.r.valid := io.local.r.valid && !stickyFatal && coherentReadResponse
  io.physical.r.bits := io.local.r.bits
  io.local.r.ready := Mux(coherentReadResponse,
    io.physical.r.ready || stickyFatal, true.B)
  private val validReadRetire = io.local.r.fire && coherentReadResponse
  when(arFire) {
    readOutstanding(io.physical.ar.bits.id) := true.B
  }
  when(validReadRetire) {
    readOutstanding(returningReadId) := false.B
  }
}

/** Asynchronous-assert, synchronous-release board readiness qualification.
  *
  * An FPGA can lose PLL lock or DDR calibration without receiving another
  * usable system-clock edge. Asynchronously clear the qualification history
  * immediately on either loss. Only release the Product cold-boot controller
  * after three consecutive healthy UI-clock edges; retaining the raw signal
  * in the final AND keeps reset assertion immediate on later loss.
  * The board must run the Product and DDR AXI on the same DDR UI clock, or
  * supply a separate, properly constrained AXI clock converter.
  */
class AetherProductBoardReadyQualifier extends Module {
  val io = IO(new Bundle {
    val rawClockLocked = Input(Bool())
    val rawDdrCalibrated = Input(Bool())
    val clockLocked = Output(Bool())
    val ddrCalibrated = Output(Bool())
  })

  private val clockClear = (reset.asBool || !io.rawClockLocked).asAsyncReset
  private val ddrClear = (reset.asBool || !io.rawClockLocked || !io.rawDdrCalibrated).asAsyncReset
  private val clockHistory = withReset(clockClear) { RegInit(0.U(3.W)) }
  private val ddrHistory = withReset(ddrClear) { RegInit(0.U(3.W)) }
  clockHistory := Cat(clockHistory(1, 0), true.B)
  ddrHistory := Cat(ddrHistory(1, 0), true.B)
  io.clockLocked := io.rawClockLocked && clockHistory(2)
  io.ddrCalibrated := io.rawClockLocked && io.rawDdrCalibrated && ddrHistory(2)
}

/** Standalone G5-SD1 Product FPGA board-level adapter.
  *
  * This closes the *logical* FPGA seam for a byte-stream image source: native
  * 50 MHz Product/UI clock, real firmware+DTB stream, local DDR AXI address,
  * Linux SPI SD-card pads, and UART/timer clock enables. It intentionally
  * does not pretend to implement a vendor DDR PHY or FPGA pin constraints.
  * A sibling UART-upload top can drive segment/wordIn directly without
  * duplicating the CPU, MMIO platform or AXI conversion logic.
  */
class AetherCoreProductFpgaDdrLocalWindowBoard(
    val implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    val ddrLocalAddrBits: Int = 28,
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry,
    val productConfigOverride: Option[ProductCoreConfig] = None,
    val boardDevices: ProductSoCBoardConfig = ProductSoCBoardProfiles.persistentSpiSd
) extends Module {
  require(implementedPaddrBits == 32,
    "G5-SD1 physical DDR adapter matches the frozen 32-bit board seam")
  require(boardDevices.spiSdEnabled,
    "G5-SD1 persistent Debian board requires physical SPI SD support")
  private val config = AetherCoreProductSoCProfiles.selectDistroTarget(
    implementedPaddrBits, productGeometry, productConfigOverride)
  private val txnBits = AetherCoreProductSoCProfiles.externalTransactionBits(config.geometry)
  private val board = AetherSoCBoardSpec.qualifiedLinux(config.platform)
  private val map = board.addressMap
  require(boardDevices.spiBusClockFrequencyHz == board.spiBusClockFrequencyHz,
    "source clock and SPI device-tree contract must use exactly the same board frequency")

  val io = IO(new Bundle {
    val segment = Flipped(Decoupled(new AetherProductDdrImageSegment(implementedPaddrBits)))
    val wordIn = Flipped(Decoupled(new AetherProductDdrImageWord))
    val localDdrAxi = new Axi4MasterIO(ddrLocalAddrBits, 64, txnBits)

    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
    val spiSclk = Output(Bool())
    val spiMosi = Output(Bool())
    val spiMiso = Input(Bool())
    val spiCsN = Output(Bool())
    val rawClockLocked = Input(Bool())
    val rawDdrCalibrated = Input(Bool())
    val boardImageFailed = Input(Bool())

    val loaderOwnsDdr = Output(Bool())
    val loaderIdle = Output(Bool())
    val completedSegments = Output(UInt(2.W))
    val cpuReleased = Output(Bool())
    val bootFault = Output(Bool())
    val ddrAddressFatal = Output(Bool())
  })

  val sourceClockHz = boardDevices.spiBusClockFrequencyHz
  private val uartTick = Module(new AetherFractionalTickGenerator(
    sourceClockHz, board.uartClockFrequencyHz))
  private val timeTick = Module(new AetherFractionalTickGenerator(
    sourceClockHz, board.timebaseFrequencyHz))
  private val ready = Module(new AetherProductBoardReadyQualifier)
  ready.io.rawClockLocked := io.rawClockLocked
  ready.io.rawDdrCalibrated := io.rawDdrCalibrated

  private val product = Module(new AetherCoreProductFpgaColdBootDdrStreamFabric(
    implementedPaddrBits = implementedPaddrBits,
    productConfigOverride = Some(config),
    boardDevices = boardDevices))
  private val local = Module(new AetherProductDdrLocalAxiWindow(
    physicalAddrBits = implementedPaddrBits,
    localAddrBits = ddrLocalAddrBits,
    txnIdBits = txnBits,
    physicalBase = map.ramBase,
    windowBytes = map.ramBytes))

  product.io.segment.valid := io.segment.valid
  product.io.segment.bits := io.segment.bits
  io.segment.ready := product.io.segment.ready
  product.io.wordIn.valid := io.wordIn.valid
  product.io.wordIn.bits := io.wordIn.bits
  io.wordIn.ready := product.io.wordIn.ready

  product.io.clockLocked := ready.io.clockLocked
  product.io.ddrCalibrated := ready.io.ddrCalibrated
  product.io.boardImageFailed := io.boardImageFailed || local.io.stickyFatal
  product.io.uartClockTick := uartTick.io.tick
  product.io.timebaseTick := timeTick.io.tick
  product.io.serialRx := io.serialRx
  io.serialTx := product.io.serialTx
  product.io.spiMiso := io.spiMiso
  io.spiSclk := product.io.spiSclk
  io.spiMosi := product.io.spiMosi
  io.spiCsN := product.io.spiCsN
  io.loaderOwnsDdr := product.io.loaderOwnsDdr
  io.loaderIdle := product.io.loaderIdle
  io.completedSegments := product.io.completedSegments
  io.cpuReleased := product.io.cpuReleased
  io.bootFault := product.io.bootFault
  io.ddrAddressFatal := local.io.stickyFatal

  local.io.physical.aw.valid := product.io.ddrAxi.aw.valid
  local.io.physical.aw.bits := product.io.ddrAxi.aw.bits
  product.io.ddrAxi.aw.ready := local.io.physical.aw.ready
  local.io.physical.w.valid := product.io.ddrAxi.w.valid
  local.io.physical.w.bits := product.io.ddrAxi.w.bits
  product.io.ddrAxi.w.ready := local.io.physical.w.ready
  product.io.ddrAxi.b.valid := local.io.physical.b.valid
  product.io.ddrAxi.b.bits := local.io.physical.b.bits
  local.io.physical.b.ready := product.io.ddrAxi.b.ready
  local.io.physical.ar.valid := product.io.ddrAxi.ar.valid
  local.io.physical.ar.bits := product.io.ddrAxi.ar.bits
  product.io.ddrAxi.ar.ready := local.io.physical.ar.ready
  product.io.ddrAxi.r.valid := local.io.physical.r.valid
  product.io.ddrAxi.r.bits := local.io.physical.r.bits
  local.io.physical.r.ready := product.io.ddrAxi.r.ready

  io.localDdrAxi.aw.valid := local.io.local.aw.valid
  io.localDdrAxi.aw.bits := local.io.local.aw.bits
  local.io.local.aw.ready := io.localDdrAxi.aw.ready
  io.localDdrAxi.w.valid := local.io.local.w.valid
  io.localDdrAxi.w.bits := local.io.local.w.bits
  local.io.local.w.ready := io.localDdrAxi.w.ready
  local.io.local.b.valid := io.localDdrAxi.b.valid
  local.io.local.b.bits := io.localDdrAxi.b.bits
  io.localDdrAxi.b.ready := local.io.local.b.ready
  io.localDdrAxi.ar.valid := local.io.local.ar.valid
  io.localDdrAxi.ar.bits := local.io.local.ar.bits
  local.io.local.ar.ready := io.localDdrAxi.ar.ready
  local.io.local.r.valid := io.localDdrAxi.r.valid
  local.io.local.r.bits := io.localDdrAxi.r.bits
  io.localDdrAxi.r.ready := local.io.local.r.ready
}

/** Complete *logical* Product FPGA UART-download/DDR/SD board boundary.
  *
  * The board exports one asynchronous RX pin, one UART TX pin, SPI SD pins,
  * and one native single-clock AXI DDR controller aperture. The source hands
  * a CRC-verified firmware/DTB byte stream to the already established AXI
  * loader; release of DDR/CPU ownership is managed by that same controller.
  *
  * The USB-UART bridge is not a clock source. All Product RTL and the DDR AXI
  * controller must share the clock driving this Module: exactly 50 MHz for
  * the selected Product DTS and SPI profile. A DDR IP with a different UI
  * clock must use a real CDC and corresponding Product clock configuration
  * outside this boundary. The board still supplies real PLL/DDR PHY, external
  * pad constraints, I/O standards, DDR power-up sequence and a physical SD
  * slot. This module does not claim those vendor/PCB-specific blocks exist.
  *
  * The very last DTB window intentionally sends no UART ACK: its final DDR B
  * may release the CPU immediately, when the actual TX pad is switched from
  * the upload UART to the Linux 16550 console. All earlier host windows do
  * receive their full credit ACK before the source accepts more data.
  */
class AetherCoreProductFpgaUartDdrBoard(
    val implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    val ddrLocalAddrBits: Int = 28,
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry,
    val productConfigOverride: Option[ProductCoreConfig] = None,
    val boardDevices: ProductSoCBoardConfig = ProductSoCBoardProfiles.persistentSpiSd,
    val uploadBaud: Long = 1_000_000L
) extends Module {
  require(boardDevices.spiBusClockFrequencyHz == 50_000_000L,
    "the Product UART-upload PHY and FPGA/UI clock contract is 50 MHz")

  private val config = AetherCoreProductSoCProfiles.selectDistroTarget(
    implementedPaddrBits, productGeometry, productConfigOverride)
  private val txnBits =
    AetherCoreProductSoCProfiles.externalTransactionBits(config.geometry)

  val io = IO(new Bundle {
    val localDdrAxi = new Axi4MasterIO(ddrLocalAddrBits, 64, txnBits)
    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
    val spiSclk = Output(Bool())
    val spiMosi = Output(Bool())
    val spiMiso = Input(Bool())
    val spiCsN = Output(Bool())
    val rawClockLocked = Input(Bool())
    val rawDdrCalibrated = Input(Bool())
    val boardImageFailed = Input(Bool())

    val loaderOwnsDdr = Output(Bool())
    val loaderIdle = Output(Bool())
    val completedSegments = Output(UInt(2.W))
    val cpuReleased = Output(Bool())
    val bootFault = Output(Bool())
    val ddrAddressFatal = Output(Bool())
    val uartUploadFailed = Output(Bool())
    val uartReceivedBytes = Output(UInt(32.W))
    val uartDrainedBytes = Output(UInt(32.W))
  })

  private val board = Module(new AetherCoreProductFpgaDdrLocalWindowBoard(
    implementedPaddrBits = implementedPaddrBits,
    ddrLocalAddrBits = ddrLocalAddrBits,
    productConfigOverride = Some(config),
    boardDevices = boardDevices))
  private val source = Module(new AetherProductUartFirmwareUpload(
    addrBits = implementedPaddrBits,
    boardClockHz = boardDevices.spiBusClockFrequencyHz,
    uploadBaud = uploadBaud))

  source.io.loaderEnabled := board.io.loaderOwnsDdr
  source.io.serialRx := io.serialRx
  board.io.serialRx := io.serialRx
  board.io.segment.valid := source.io.segment.valid
  board.io.segment.bits := source.io.segment.bits
  source.io.segment.ready := board.io.segment.ready
  board.io.wordIn.valid := source.io.wordOut.valid
  board.io.wordIn.bits := source.io.wordOut.bits
  source.io.wordOut.ready := board.io.wordIn.ready

  // The Linux UART PHY belongs to the CPU after release; neither UART may
  // drive the physical pin while the other side owns it.
  io.serialTx := Mux(board.io.cpuReleased, board.io.serialTx, source.io.serialTx)
  board.io.boardImageFailed := io.boardImageFailed || source.io.failed
  board.io.rawClockLocked := io.rawClockLocked
  board.io.rawDdrCalibrated := io.rawDdrCalibrated
  board.io.spiMiso := io.spiMiso
  io.spiSclk := board.io.spiSclk
  io.spiMosi := board.io.spiMosi
  io.spiCsN := board.io.spiCsN

  io.loaderOwnsDdr := board.io.loaderOwnsDdr
  io.loaderIdle := board.io.loaderIdle
  io.completedSegments := board.io.completedSegments
  io.cpuReleased := board.io.cpuReleased
  io.bootFault := board.io.bootFault
  io.ddrAddressFatal := board.io.ddrAddressFatal
  io.uartUploadFailed := source.io.failed
  io.uartReceivedBytes := source.io.receivedBytes
  io.uartDrainedBytes := source.io.drainedBytes

  io.localDdrAxi.aw.valid := board.io.localDdrAxi.aw.valid
  io.localDdrAxi.aw.bits := board.io.localDdrAxi.aw.bits
  board.io.localDdrAxi.aw.ready := io.localDdrAxi.aw.ready
  io.localDdrAxi.w.valid := board.io.localDdrAxi.w.valid
  io.localDdrAxi.w.bits := board.io.localDdrAxi.w.bits
  board.io.localDdrAxi.w.ready := io.localDdrAxi.w.ready
  board.io.localDdrAxi.b.valid := io.localDdrAxi.b.valid
  board.io.localDdrAxi.b.bits := io.localDdrAxi.b.bits
  io.localDdrAxi.b.ready := board.io.localDdrAxi.b.ready
  io.localDdrAxi.ar.valid := board.io.localDdrAxi.ar.valid
  io.localDdrAxi.ar.bits := board.io.localDdrAxi.ar.bits
  board.io.localDdrAxi.ar.ready := io.localDdrAxi.ar.ready
  board.io.localDdrAxi.r.valid := io.localDdrAxi.r.valid
  board.io.localDdrAxi.r.bits := io.localDdrAxi.r.bits
  io.localDdrAxi.r.ready := board.io.localDdrAxi.r.ready
}
