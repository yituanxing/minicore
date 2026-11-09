package aethercore.sim

import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.soc.{AetherCoreProductSoCProfiles, AetherCoreProductUnifiedMemorySoC, AetherSoCBoardSpec, ProductSoCBoardProfiles, ProductSoCCacheProfile, ProductSoCCacheProfiles}
import aethercore.soc.phy.AetherFractionalTickGenerator
import _root_.circt.stage.ChiselStage
import chisel3._

/** Simulation-only, historical OpenSBI host-memory ABI for the Product CPU.
  *
  * No CPU instruction/PTW/data shortcuts are attached here: all three memory
  * classes traverse their synthesizable Product SoC clients, source-tagged
  * MemoryHub and the existing unified host adapter. This lets the existing
  * Verilator C++ test harness attempt a Product boot without replacing or
  * weakening the independently qualified V2 Linux path.
  *
  * The default Product top is the narrow IMAC bring-up baseline. Selecting
  * debianTarget elaborates the existing RV64GC hardware; neither configuration
  * alone is evidence that a complete Debian userspace has booted.
  */
class AetherCoreProductUnifiedMemoryCompatSimTop(
    val debianTarget: Boolean = false,
    val spiSdTarget: Boolean = false,
    val cacheProfile: ProductSoCCacheProfile = ProductSoCCacheProfiles.productionP0,
    val exportCacheCounters: Boolean = false
) extends Module {
  require(!spiSdTarget || debianTarget,
    "Product SPI-SD simulation requires the real RV64GC Debian ISA/ABI target")
  require(!exportCacheCounters || debianTarget,
    "Cache capacity A/B counters require the RV64GC Debian target")
  private val Xlen = 64
  private val PA = 56
  private val DataBits = 64

  val io = IO(new Bundle {
    val imemValid = Output(Bool())
    val imemAddr = Output(UInt(PA.W))
    val imemBytes = Output(UInt(3.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAtomic = Output(Bool())
    val memOp = Output(AetherMemOp())
    val memAtomicOp = Output(AtomicOp())
    val memAddr = Output(UInt(PA.W))
    val memWdata = Output(UInt(DataBits.W))
    val memWmask = Output(UInt((DataBits / 8).W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(DataBits.W))
    val memFault = Input(Bool())

    val ptwValid = Output(Bool())
    val ptwAddr = Output(UInt(PA.W))
    val ptwReady = Input(Bool())
    val ptwRdata = Input(UInt(64.W))
    val ptwFault = Input(Bool())

    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(Xlen.W))

    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
    val timerInterrupt = Output(Bool())
    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    // Present only in the opt-in cache A/B top. Existing G4/G5/SD1
    // Verilator top-level pin sets must remain exactly unchanged.
    val icacheHitCount = if (exportCacheCounters) Some(Output(UInt(64.W))) else None
    val icacheMissCount = if (exportCacheCounters) Some(Output(UInt(64.W))) else None
    // The same Product target that enables F/D in the core must export its
    // floating retirement trace through the host top. The engineering IMAC
    // ABI remains unchanged; a Debian RV64GC top cannot silently discard
    // floating register writes and FCSR/FS retirement visibility.
    val commit = Output(new CommitTrace(Xlen, PA, DataBits,
      withFloating = debianTarget))
    val halted = Output(Bool())

    // Product SD1 independently enables the same physical card pins as the
    // FPGA cold-boot SoC. Existing G4/G5 tops retain their frozen host ABI.
    val spiSclk = if (spiSdTarget) Some(Output(Bool())) else None
    val spiMosi = if (spiSdTarget) Some(Output(Bool())) else None
    val spiMiso = if (spiSdTarget) Some(Input(Bool())) else None
    val spiCsN = if (spiSdTarget) Some(Output(Bool())) else None
  })

  // Keep the independently qualified RV64IMAC G4 top unchanged. The Debian
  // runner explicitly opts into the very same Product CPU/SoC implementation
  // with the hardware RV64GC/LP64D target selected at its sole board owner.
  private val requestedTarget = if (debianTarget)
    Some(AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(PA)) else None
  private val selectedBoardDevices = if (spiSdTarget)
    ProductSoCBoardProfiles.persistentSpiSd else ProductSoCBoardProfiles.existingRamOnly
  val soc = Module(new AetherCoreProductUnifiedMemorySoC(
    // The legacy IMAC/G4 host oracle intentionally retains its old one-tick-
    // per-cycle ABI. Debian targets must see the actual board's 10MHz MTIME
    // cadence relative to the 50MHz SPI/CPU clock, or kernel and SD timeouts
    // become five times shorter than on the Product FPGA shell.
    externalPhysicalSeams = debianTarget,
    productConfigOverride = requestedTarget,
    boardDevices = selectedBoardDevices,
    cacheProfile = cacheProfile))
  if (debianTarget) {
    val board = AetherSoCBoardSpec.qualifiedLinux(soc.config.platform)
    val timeTick = Module(new AetherFractionalTickGenerator(
      sourceFrequencyHz = selectedBoardDevices.spiBusClockFrequencyHz,
      targetFrequencyHz = board.timebaseFrequencyHz
    ))
    soc.io.timebaseTick.get := timeTick.io.tick
    soc.io.uartTxReady.get := true.B
  }
  val core = soc.core
  val hostMemory = Module(new AetherSoCUnifiedHostMemoryAdapter(
    addrBits = PA,
    dataBits = DataBits,
    localTxnIdBits = 2,
    sourceBits = 2
  ))

  hostMemory.io.request <> soc.io.memoryRequest
  soc.io.memoryResponse <> hostMemory.io.response

  io.imemValid := hostMemory.io.imemValid
  io.imemAddr := hostMemory.io.imemAddr
  io.imemBytes := hostMemory.io.imemBytes
  hostMemory.io.imemInst := io.imemInst
  hostMemory.io.imemFault := io.imemFault

  io.ptwValid := hostMemory.io.ptwValid
  io.ptwAddr := hostMemory.io.ptwAddr
  hostMemory.io.ptwReady := io.ptwReady
  hostMemory.io.ptwRdata := io.ptwRdata
  hostMemory.io.ptwFault := io.ptwFault

  io.memValid := hostMemory.io.memValid
  io.memWrite := hostMemory.io.memWrite
  io.memAtomic := hostMemory.io.memAtomic
  io.memOp := hostMemory.io.memOp
  io.memAtomicOp := hostMemory.io.memAtomicOp
  io.memAddr := hostMemory.io.memAddr
  io.memWdata := hostMemory.io.memWdata
  io.memWmask := hostMemory.io.memWmask
  io.memSize := hostMemory.io.memSize
  hostMemory.io.memReady := io.memReady
  hostMemory.io.memRdata := io.memRdata
  hostMemory.io.memFault := io.memFault

  soc.io.rxValid := io.rxValid
  soc.io.rxByte := io.rxByte
  io.rxReady := soc.io.rxReady
  io.uartValid := soc.io.uartValid
  io.uartByte := soc.io.uartByte

  io.supervisorExternalInterrupt := soc.io.supervisorExternalInterrupt
  io.uartInterrupt := soc.io.uartInterrupt
  io.uartRxInterrupt := soc.io.uartRxInterrupt
  io.timerInterrupt := soc.io.timerInterrupt
  io.exitValid := soc.io.exitValid
  io.exitCode := soc.io.exitCode
  io.mtime := soc.io.mtime
  io.mtimecmp := soc.io.mtimecmp
  io.dcacheHitCount := soc.io.dcacheHitCount
  io.dcacheMissCount := soc.io.dcacheMissCount
  io.dcacheBypassCount := soc.io.dcacheBypassCount
  if (exportCacheCounters) {
    io.icacheHitCount.get := soc.io.icacheHitCount
    io.icacheMissCount.get := soc.io.icacheMissCount
  }
  io.commit := soc.io.commit
  io.halted := soc.io.halted
  if (spiSdTarget) {
    soc.io.spiMiso.get := io.spiMiso.get
    io.spiSclk.get := soc.io.spiSclk.get
    io.spiMosi.get := soc.io.spiMosi.get
    io.spiCsN.get := soc.io.spiCsN.get
  }
}

/** Distinct module identity for the existing OpenSBI host program. */
class AetherCoreProductOpenSbiRV64SimTop
    extends AetherCoreProductUnifiedMemoryCompatSimTop(debianTarget = false)

/** True Debian RV64GC/LP64D hardware target, not the IMAC G4 bring-up top. */
class AetherCoreProductDebianRV64SimTop
    extends AetherCoreProductUnifiedMemoryCompatSimTop(debianTarget = true)

/** Same Debian RAM/host-memory path, explicit private cache-capacity experiment.
  * Never substitute it for the frozen G5 or physical SPI-SD model.
  */
class AetherCoreProductDebianCacheAbRV64SimTop(
    instructionEntries: Int = 64,
    dataEntries: Int = 64
) extends AetherCoreProductUnifiedMemoryCompatSimTop(
      debianTarget = true,
      cacheProfile = ProductSoCCacheProfile(instructionEntries, dataEntries),
      exportCacheCounters = true)

/** SD1 Product Debian SPI-SD: no software storage shortcut or synthetic block
  * MMIO. All SD sectors travel through the Linux mmc_spi driver, OpenCores
  * SPI, GPIO chip select, S-mode PLIC and the host card's four physical pins.
  */
class AetherCoreProductDebianSdRV64SimTop
    extends AetherCoreProductUnifiedMemoryCompatSimTop(
      debianTarget = true, spiSdTarget = true)

object ElaborateProductOpenSbiRV64SimTop extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductOpenSbiRV64SimTop,
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateProductDebianRV64SimTop extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductDebianRV64SimTop,
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

/** `I_ENTRIES D_ENTRIES --target-dir DIR`: matching short-Debian cache A/B. */
object ElaborateProductDebianCacheAbRV64SimTop extends App {
  require(args.length >= 2,
    "Debian cache A/B requires exact I-cache and D-cache entry counts")
  val instructionEntries = args(0).toInt
  val dataEntries = args(1).toInt
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductDebianCacheAbRV64SimTop(
      instructionEntries = instructionEntries,
      dataEntries = dataEntries),
    args.drop(2),
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateProductDebianSdRV64SimTop extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductDebianSdRV64SimTop,
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
