package aethercore.soc

import aethercore.common.CommitTrace
import aethercore.config.SoftwareTarget
import aethercore.core.product.{ProductCoreConfig, ProductCoreGeometry, ProductCoreProfiles, ProductRetirement, ProductSmallPagedCore}
import aethercore.memory.{AetherDirectMappedReadCache, AetherMemRequest, AetherMemResponse}
import chisel3._
import chisel3.util._

/** The OpenSBI board binding for a selected SmallOoO software target.
  *
  * This chooses reset ROM, physical seam, architectural TIME and
  * machine-provided supervisor timer without conflating an ISA request with
  * implemented hardware. The default engineering target remains RV64IMAC;
  * the separately requested RV64GC/LP64D target still needs RTL qualification.
  */
object AetherCoreProductSoCProfiles {
  // The unchanged OpenSBI/QEMU-virt-compatible DTB declares riscv,ndev=52.
  // Product must implement that whole architectural PLIC priority/enable
  // aperture; source 17 is used during the firmware's cold initialization.
  // Keep the already-qualified V2 board's 16-source preset independent.
  val OpenSbiPlicSourceCount: Int = 52
  val MemoryClientCount: Int = 3

  // The physical LSQ owns its transaction namespace. The hub appends a
  // separate source tag for the data, PTW, and instruction clients; neither
  // width is a board constant. Keep this identical through AXI and FPGA IO.
  def clientTransactionBits(geometry: ProductCoreGeometry): Int =
    log2Ceil(geometry.loadQueueEntries + geometry.storeQueueEntries)

  def externalTransactionBits(geometry: ProductCoreGeometry): Int =
    clientTransactionBits(geometry) + log2Ceil(MemoryClientCount)

  /** Once a board-level CPU target is chosen, every enclosing shell must
    * forward this exact target into the paged core.  A geometry preset or a
    * software ISA is not itself a hardware qualification.
    */
  def requireBoardCompatible(config: ProductCoreConfig, implementedPaddrBits: Int): Unit = {
    require(implementedPaddrBits >= 32 && implementedPaddrBits <= 56,
      "Product SoC supports a 32..56-bit physical memory seam")
    require(config.platform.paddrBits == implementedPaddrBits,
      "Product SoC target physical address width must match its board shell")
    require(config.platform.resetVector == AetherSoCAddressMap.QualifiedBootRomBase,
      "Product SoC target must reset at the connected OpenSBI BootROM")
    val g = config.geometry
    require(g.decodeWidth == 1 && g.dispatchWidth == 1 &&
      g.issueWidth == 1 && g.retireWidth == 1,
      "Product SmallOoO SoC implements one-wide execution and retirement")
  }

  def smallOpenSbiEngineering(
      implementedPaddrBits: Int,
      productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry
  ): ProductCoreConfig = bindOpenSbiBoard(
    ProductCoreProfiles.smallCompressedEngineering,
    "small-imac-opensbi-product", implementedPaddrBits, productGeometry)

  /** Construct the explicit RV64GC/LP64D board target once at the top.
    * It is not the default and is not a claim that numerical, privileged or
    * board-level RV64GC correctness has already been qualified.
    */
  def smallOpenSbiDistroTarget(
      implementedPaddrBits: Int,
      productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry
  ): ProductCoreConfig = bindOpenSbiBoard(
    ProductCoreProfiles.smallDebianTarget,
    "small-rv64gc-opensbi-product", implementedPaddrBits, productGeometry)

  private def bindOpenSbiBoard(
      base: ProductCoreConfig,
      name: String,
      implementedPaddrBits: Int,
      productGeometry: ProductCoreGeometry
  ): ProductCoreConfig = {
    require(implementedPaddrBits >= 32 && implementedPaddrBits <= 56,
      "Product SoC supports a 32..56-bit physical memory seam")
    require(productGeometry.decodeWidth == 1 && productGeometry.dispatchWidth == 1 &&
      productGeometry.issueWidth == 1 && productGeometry.retireWidth == 1,
      "Product SmallOoO SoC implements one-wide execution and retirement")
    base.copy(
      name = name,
      geometry = productGeometry,
      software = SoftwareTarget(
        base.software.isa.copy(
          zExtensions = base.software.isa.zExtensions ++ Set("Zicsr", "Zifencei"),
          machineProvidedSupervisorTimer = true,
          timeCounter = true
        ),
        base.software.abi
      ),
      platform = base.platform.copy(
        resetVector = AetherSoCAddressMap.QualifiedBootRomBase,
        paddrBits = implementedPaddrBits
      )
    )
  }
}

/** Product SmallOoO CPU connected to the actual board memory/peripheral fabric.
  *
  * This is a separate integration from the qualified V2 Linux SoC. The Product
  * frontend fetches real BootROM instructions, walks Sv39 via a PTW memory
  * client, accesses RAM/MMIO through PlatformFabric, and observes the board
  * MTIMER/PLIC interrupts. Its three independent semantic memory clients
  * converge at the existing source-tagging MemoryHub and one external port.
  *
  * The Product retirement and physical-memory CommitTrace are exported from
  * their actual retirement owner; neither is reconstituted inside the board.
  * This is a bring-up platform, not an OpenSBI/Linux pass.
  */
class AetherCoreProductUnifiedMemorySoC(
    val externalPhysicalSeams: Boolean = false,
    val implementedPaddrBits: Int = 56,
    // Legacy geometry selection is used only when no exact CPU target is given.
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry,
    val productConfigOverride: Option[ProductCoreConfig] = None,
    val boardDevices: ProductSoCBoardConfig = ProductSoCBoardProfiles.existingRamOnly
) extends Module {
  private val enableSpiSd = boardDevices.spiSdEnabled
  val config: ProductCoreConfig = productConfigOverride.getOrElse(
    AetherCoreProductSoCProfiles.smallOpenSbiEngineering(implementedPaddrBits, productGeometry))
  AetherCoreProductSoCProfiles.requireBoardCompatible(config, implementedPaddrBits)
  private val geometry = config.geometry
  private val Xlen = 64
  private val DataBits = 64
  private val ClientTxnBits = AetherCoreProductSoCProfiles.clientTransactionBits(geometry)
  private val ClientCount = AetherCoreProductSoCProfiles.MemoryClientCount
  private val SourceBits = log2Ceil(ClientCount)
  val externalTxnIdBits: Int = ClientTxnBits + SourceBits

  private val board = AetherSoCBoardSpec.qualifiedLinux(config.platform).copy(
    plicSourceCount = AetherCoreProductSoCProfiles.OpenSbiPlicSourceCount,
    // One board-profile clock contract feeds both physical SPI divider
    // expectations and the DTS `clock-frequency` emitted for the Linux driver.
    // The FPGA integrator must actually supply this clock to the Product top.
    spiBusClockFrequencyHz = boardDevices.spiBusClockFrequencyHz,
    spiMaxFrequencyHz = boardDevices.spiMaxFrequencyHz
  )
  private val map = board.addressMap
  require(log2Ceil(geometry.loadQueueEntries + geometry.storeQueueEntries) == ClientTxnBits,
    "Product data transaction tags must match the shared platform fabric")

  val io = IO(new Bundle {
    val memoryRequest = Decoupled(
      new AetherMemRequest(implementedPaddrBits, DataBits, externalTxnIdBits))
    val memoryResponse = Flipped(Decoupled(
      new AetherMemResponse(DataBits, externalTxnIdBits)))

    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val uartBaudDivisor = Output(UInt(16.W))
    val uartTxReady = if (externalPhysicalSeams) Some(Input(Bool())) else None
    val timebaseTick = if (externalPhysicalSeams) Some(Input(Bool())) else None
    val spiSclk = if (enableSpiSd) Some(Output(Bool())) else None
    val spiMosi = if (enableSpiSd) Some(Output(Bool())) else None
    val spiMiso = if (enableSpiSd) Some(Input(Bool())) else None
    val spiCsN = if (enableSpiSd) Some(Output(Bool())) else None

    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val timerInterrupt = Output(Bool())

    val exitValid = Output(Bool())
    val exitCode = Output(UInt(Xlen.W))
    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    val icacheHitCount = Output(UInt(64.W))
    val icacheMissCount = Output(UInt(64.W))
    val commit = Output(new CommitTrace(Xlen, implementedPaddrBits, DataBits,
      withFloating = config.software.isa.extensions.contains('F')))

    val retire = Valid(new ProductRetirement(
      Xlen,
      log2Ceil(geometry.robEntries),
      log2Ceil(geometry.integerPhysicalRegisters),
      16,
      implementedPaddrBits
    ))
    val currentPrivilege = Output(UInt(2.W))
    val frontendPc = Output(UInt(Xlen.W))
    val outstandingTransactions = Output(UInt(log2Ceil(
      geometry.loadQueueEntries + geometry.storeQueueEntries + 1).W))
    val halted = Output(Bool())
  })

  val core = Module(new ProductSmallPagedCore(
    config,
    enableInstructionBackpressure = true,
    withSupervisorExternalInterrupt = true
  ))
  val fabric = Module(new AetherSoCPlatformFabric(
    paddrBits = implementedPaddrBits,
    dataBits = DataBits,
    txnIdBits = ClientTxnBits,
    addressMap = map,
    plicSourceCount = board.plicSourceCount,
    uartPlicSourceId = board.uartPlicSourceId,
    uartResetDivisor = board.uartDefaultDivisor,
    externalSemanticMemory = true,
    enableSpiSd = enableSpiSd,
    spiPlicSourceId = board.spiPlicSourceId,
    spiBusClockFrequencyHz = board.spiBusClockFrequencyHz
  ))
  val dcache = Module(new AetherDirectMappedReadCache(
    addrBits = implementedPaddrBits,
    dataBits = DataBits,
    txnIdBits = ClientTxnBits,
    entries = 64
  ))
  val ptwAdapter = Module(new AetherSoCPtwReadAdapter(
    addrBits = implementedPaddrBits,
    dataBits = DataBits,
    pteBits = 64,
    txnIdBits = ClientTxnBits
  ))
  val instructionCache = Module(new AetherSoCInstructionCache(
    addrBits = implementedPaddrBits,
    dataBits = DataBits,
    txnIdBits = ClientTxnBits,
    entries = 64
  ))
  val hub = Module(new AetherSoCMemoryHub(
    addrBits = implementedPaddrBits,
    dataBits = DataBits,
    clientTxnIdBits = ClientTxnBits,
    clientCount = ClientCount
  ))
  val bootRom = Module(new AetherSoCBootRom(
    addrBits = implementedPaddrBits,
    dataBits = DataBits,
    txnIdBits = externalTxnIdBits,
    baseAddress = map.bootRomBase,
    apertureBytes = map.bootRomBytes
  ))
  val responseArbiter = Module(new RRArbiter(
    new AetherMemResponse(DataBits, externalTxnIdBits),
    2
  ))

  // Cache policy follows the CPU-resolved physical PMA. As in the V2 complex,
  // only ordinary RAM reads allocate; writes are write-through, atomics and
  // MMIO bypass, and writes/atomics invalidate matching cached bytes.
  // The board fabric remains the single owner of PMA, UART, PLIC and ACLINT.
  fabric.io.resolvedPhysicalAddress := core.io.resolvedPhysicalAddress
  core.io.resolvedAttributes := fabric.io.resolvedAttributes
  dcache.io.upstreamRequest <> core.io.memoryRequest
  core.io.memoryResponse <> dcache.io.upstreamResponse
  fabric.io.request <> dcache.io.downstreamRequest
  dcache.io.downstreamResponse <> fabric.io.response

  core.io.time.get := fabric.io.time
  core.io.timerInterrupt := fabric.io.timerInterrupt
  core.io.supervisorExternalInterrupt.get := fabric.io.supervisorExternalInterrupt

  // Leave historical terminal-response pins inert: all external RAM traffic
  // uses the semantic AetherMem port and preserves its original transaction ID.
  fabric.io.memReady := false.B
  fabric.io.memRdata := 0.U
  fabric.io.memFault := false.B

  // PTW PTE reads are data, but must never probe side-effecting MMIO or
  // silently fall through to external RAM for an unmapped physical address.
  // The paged core handles the returned fault as an architectural page walk
  // access failure. Both checks use PA+1 arithmetic to forbid wraparound.
  val pteEnd = core.io.ptw.addr +& 8.U
  val pteInRam = core.io.ptw.addr >= map.ramBase.U &&
    pteEnd <= map.ramLimit.U((implementedPaddrBits + 1).W)
  val deniedPte = core.io.ptw.valid && !pteInRam
  ptwAdapter.io.legacyValid := core.io.ptw.valid && pteInRam
  ptwAdapter.io.legacyAddr := core.io.ptw.addr
  core.io.ptw.ready := deniedPte || ptwAdapter.io.legacyReady
  core.io.ptw.rdata := ptwAdapter.io.legacyRdata
  core.io.ptw.fault := deniedPte || ptwAdapter.io.legacyFault

  // Instruction fetch bypasses the data-side MMIO decoder. Therefore a real
  // board-level execute PMA check must explicitly reject UART/PLIC/ACLINT,
  // exit and unmapped physical addresses before they reach the I-cache/hub.
  // A two-byte C parcel at the end of a region cannot straddle its boundary.
  val fetchEnd = core.io.imem.addr +& core.io.imem.bytes
  val fetchInRom = core.io.imem.addr >= map.bootRomBase.U &&
    fetchEnd <= map.bootRomLimit.U((implementedPaddrBits + 1).W)
  val fetchInRam = core.io.imem.addr >= map.ramBase.U &&
    fetchEnd <= map.ramLimit.U((implementedPaddrBits + 1).W)
  val fetchExecutable = fetchInRom || fetchInRam
  val deniedFetch = core.io.imem.valid && !fetchExecutable
  instructionCache.io.frontendValid := core.io.imem.valid && fetchExecutable
  instructionCache.io.frontendAddr := core.io.imem.addr
  instructionCache.io.frontendBytes := core.io.imem.bytes
  instructionCache.io.invalidateAll := core.io.instructionFence
  core.io.imemReady.get := deniedFetch || instructionCache.io.frontendReady
  core.io.imem.inst := instructionCache.io.frontendInst
  core.io.imem.fault := deniedFetch || instructionCache.io.frontendFault

  hub.io.clients(0).request <> fabric.io.externalRequest.get
  fabric.io.externalResponse.get <> hub.io.clients(0).response
  hub.io.clients(1).request <> ptwAdapter.io.request
  ptwAdapter.io.response <> hub.io.clients(1).response
  hub.io.clients(2).request <> instructionCache.io.request
  instructionCache.io.response <> hub.io.clients(2).response

  // BootROM shares the SAME transaction/source-tagged downstream fabric as
  // external RAM. It must be selected after, not before, the MemoryHub.
  val bootRomHit =
    hub.io.downstreamRequest.bits.paddr >= map.bootRomBase.U &&
      hub.io.downstreamRequest.bits.paddr < map.bootRomLimit.U
  bootRom.io.request.valid := hub.io.downstreamRequest.valid && bootRomHit
  bootRom.io.request.bits := hub.io.downstreamRequest.bits
  io.memoryRequest.valid := hub.io.downstreamRequest.valid && !bootRomHit
  io.memoryRequest.bits := hub.io.downstreamRequest.bits
  hub.io.downstreamRequest.ready :=
    Mux(bootRomHit, bootRom.io.request.ready, io.memoryRequest.ready)

  responseArbiter.io.in(0) <> bootRom.io.response
  responseArbiter.io.in(1).valid := io.memoryResponse.valid
  responseArbiter.io.in(1).bits := io.memoryResponse.bits
  io.memoryResponse.ready := responseArbiter.io.in(1).ready
  hub.io.downstreamResponse <> responseArbiter.io.out

  fabric.io.rxValid := io.rxValid
  fabric.io.rxByte := io.rxByte
  fabric.io.uartTxReady :=
    (if (externalPhysicalSeams) io.uartTxReady.get else true.B)
  fabric.io.timebaseTick :=
    (if (externalPhysicalSeams) io.timebaseTick.get else true.B)
  if (enableSpiSd) {
    fabric.io.spiMiso.get := io.spiMiso.get
    io.spiSclk.get := fabric.io.spiSclk.get
    io.spiMosi.get := fabric.io.spiMosi.get
    io.spiCsN.get := fabric.io.spiCsN.get
  }
  io.rxReady := fabric.io.rxReady
  io.uartValid := fabric.io.uartValid
  io.uartByte := fabric.io.uartByte
  io.uartBaudDivisor := fabric.io.uartBaudDivisor

  io.supervisorExternalInterrupt := fabric.io.supervisorExternalInterrupt
  io.uartInterrupt := fabric.io.uartInterrupt
  io.uartRxInterrupt := fabric.io.uartRxInterrupt
  io.timerInterrupt := fabric.io.timerInterrupt
  io.exitValid := fabric.io.exitValid
  io.exitCode := fabric.io.exitCode
  io.mtime := fabric.io.mtime
  io.mtimecmp := fabric.io.mtimecmp
  io.dcacheHitCount := dcache.io.hitCount
  io.dcacheMissCount := dcache.io.missCount
  io.dcacheBypassCount := dcache.io.bypassCount
  io.icacheHitCount := instructionCache.io.hitCount
  io.icacheMissCount := instructionCache.io.missCount
  io.commit := core.io.commit

  io.retire := core.io.retire
  io.currentPrivilege := core.io.currentPrivilege
  io.frontendPc := core.io.frontendPc
  io.outstandingTransactions := core.io.outstandingTransactions
  io.halted := core.io.halted
}
