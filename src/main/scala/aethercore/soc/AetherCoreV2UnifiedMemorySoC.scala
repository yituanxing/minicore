package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.CommitTrace
import aethercore.config.CoreProfiles
import aethercore.memory.{AetherMemRequest, AetherMemResponse}

/**
  * First synthesizable unified-memory AetherSoC boundary.
  *
  * The qualified Linux platform remains the software-visible owner of PMA,
  * D-cache, UART, PLIC, timer and MMIO. Its data-side external RAM traffic now
  * remains semantic AetherMem end-to-end, while PTW and instruction seams are
  * adapted into the other two MemoryHub clients.
  *
  * The only external memory interface is one AetherMem request/response pair.
  * A later adapter may translate this boundary to AXI4 without teaching the CPU
  * or the internal SoC fabric about AXI channel semantics.
  */
object AetherCoreV2UnifiedMemorySoC {
  val QualifiedCompactTxnIdBits: Int = 3
}

class AetherCoreV2UnifiedMemorySoC(
    val externalPhysicalSeams: Boolean = false,
    val implementedPaddrBits: Int = 56,
    val compactQualifiedTxnIds: Boolean = false
) extends Module {
  private val xlen = 64
  private val paddrBits = implementedPaddrBits
  private val dataBits = 64
  private val clientTxnIdBits = 2
  private val clientCount = 3
  private val sourceBits = log2Ceil(clientCount)
  val externalTxnIdBits: Int =
    if (compactQualifiedTxnIds)
      AetherCoreV2UnifiedMemorySoC.QualifiedCompactTxnIdBits
    else
      clientTxnIdBits + sourceBits
  private val board =
    AetherSoCBoardSpec.qualifiedLinux(CoreProfiles.rv64imasuSv39PmpSoftware.platform)
  private val addressMap = board.addressMap

  val io = IO(new Bundle {
    val memoryRequest =
      Decoupled(new AetherMemRequest(paddrBits, dataBits, externalTxnIdBits))
    val memoryResponse =
      Flipped(Decoupled(new AetherMemResponse(dataBits, externalTxnIdBits)))

    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val uartBaudDivisor = Output(UInt(16.W))
    val uartTxReady =
      if (externalPhysicalSeams) Some(Input(Bool())) else None
    val timebaseTick =
      if (externalPhysicalSeams) Some(Input(Bool())) else None

    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val timerInterrupt = Output(Bool())

    val exitValid = Output(Bool())
    val exitCode = Output(UInt(xlen.W))
    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))

    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    val icacheHitCount = Output(UInt(64.W))
    val icacheMissCount = Output(UInt(64.W))

    val commit = Output(new CommitTrace(xlen, paddrBits, dataBits))
    val halted = Output(Bool())
  })

  val platform = Module(new AetherCoreV2LinuxSoC(
    enableInstructionBackpressure = true,
    exposeExternalMemoryAttributes = false,
    externalPhysicalSeams = externalPhysicalSeams,
    externalSemanticMemory = true,
    implementedPaddrBits = implementedPaddrBits
  ))
  val ptwAdapter = Module(new AetherSoCPtwReadAdapter(
    addrBits = paddrBits,
    dataBits = dataBits,
    pteBits = 64,
    txnIdBits = clientTxnIdBits
  ))
  val instructionCache = Module(new AetherSoCInstructionCache(
    addrBits = paddrBits,
    dataBits = dataBits,
    txnIdBits = clientTxnIdBits,
    entries = 64
  ))
  val hub = Module(new AetherSoCMemoryHub(
    addrBits = paddrBits,
    dataBits = dataBits,
    clientTxnIdBits = clientTxnIdBits,
    clientCount = clientCount,
    compactQualifiedTxnIds = compactQualifiedTxnIds
  ))

  val bootRom = Module(new AetherSoCBootRom(
    addrBits = paddrBits,
    dataBits = dataBits,
    txnIdBits = externalTxnIdBits,
    baseAddress = addressMap.bootRomBase,
    apertureBytes = addressMap.bootRomBytes
  ))
  val responseArbiter = Module(new RRArbiter(
    new AetherMemResponse(dataBits, externalTxnIdBits),
    2
  ))

  // Unified/AXI/FPGA composition uses the platform's semantic external-RAM
  // seam directly. The historical terminal-response data port is tied off and
  // remains available only in the standalone Linux compatibility composition.
  platform.io.memReady := false.B
  platform.io.memRdata := 0.U
  platform.io.memFault := false.B

  // PTW read seam -> semantic AetherMem.
  ptwAdapter.io.legacyValid := platform.io.ptwValid
  ptwAdapter.io.legacyAddr := platform.io.ptwAddr
  platform.io.ptwReady := ptwAdapter.io.legacyReady
  platform.io.ptwRdata := ptwAdapter.io.legacyRdata
  platform.io.ptwFault := ptwAdapter.io.legacyFault

  // I-cache hits satisfy the optional frontend backpressure seam in the same
  // cycle. Misses remain registered AetherMem lifetimes through MemoryHub.
  instructionCache.io.frontendValid := platform.io.imemValid
  instructionCache.io.frontendAddr := platform.io.imemAddr
  instructionCache.io.frontendBytes := platform.io.imemBytes
  instructionCache.io.invalidateAll := platform.io.instructionFence
  platform.io.imemReady.get := instructionCache.io.frontendReady
  platform.io.imemInst := instructionCache.io.frontendInst
  platform.io.imemFault := instructionCache.io.frontendFault

  hub.io.clients(0).request <> platform.io.externalRequest.get
  platform.io.externalResponse.get <> hub.io.clients(0).response

  hub.io.clients(1).request <> ptwAdapter.io.request
  ptwAdapter.io.response <> hub.io.clients(1).response

  hub.io.clients(2).request <> instructionCache.io.request
  instructionCache.io.response <> hub.io.clients(2).response

  // BootROM is a first-class target below the client-tagging MemoryHub.
  // Requests outside its aperture remain the single exported external-memory
  // master. Responses from ROM and external memory share one backpressured
  // return path, so transaction/source identity remains unchanged.
  val bootRomHit =
    hub.io.downstreamRequest.bits.paddr >= addressMap.bootRomBase.U &&
      hub.io.downstreamRequest.bits.paddr < addressMap.bootRomLimit.U

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

  // Byte-stream UART remains at the reusable SoC boundary. A physical serial
  // PHY belongs in the later FPGA wrapper.
  platform.io.rxValid := io.rxValid
  platform.io.rxByte := io.rxByte
  if (externalPhysicalSeams) {
    platform.io.uartTxReady.get := io.uartTxReady.get
    platform.io.timebaseTick.get := io.timebaseTick.get
  }
  io.rxReady := platform.io.rxReady
  io.uartValid := platform.io.uartValid
  io.uartByte := platform.io.uartByte
  io.uartBaudDivisor := platform.io.uartBaudDivisor

  io.supervisorExternalInterrupt := platform.io.supervisorExternalInterrupt
  io.uartInterrupt := platform.io.uartInterrupt
  io.uartRxInterrupt := platform.io.uartRxInterrupt
  io.timerInterrupt := platform.io.timerInterrupt

  io.exitValid := platform.io.exitValid
  io.exitCode := platform.io.exitCode
  io.mtime := platform.io.mtime
  io.mtimecmp := platform.io.mtimecmp

  io.dcacheHitCount := platform.io.dcacheHitCount
  io.dcacheMissCount := platform.io.dcacheMissCount
  io.dcacheBypassCount := platform.io.dcacheBypassCount
  io.icacheHitCount := instructionCache.io.hitCount
  io.icacheMissCount := instructionCache.io.missCount
  io.commit := platform.io.commit
  io.halted := platform.io.halted
}
