package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.{CommitTrace, InstructionBusIO, PageTableReadBusIO}
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore
import aethercore.memory.{AetherDirectMappedReadCache, AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * Reusable AetherCore V2 CPU-complex boundary.
  *
  * The CPU complex owns CPU-internal microarchitecture and caches only:
  *   - TinyPagedCore
  *   - stage-1 D-cache
  *   - MMU/TLB/PTW machinery already owned by TinyPagedCore
  *   - PMP/privilege state already owned by TinyPagedCore
  *
  * It deliberately does NOT own SoC peripherals, address-map policy, UART,
  * interrupt controllers, timers, BootROM, external-memory technology, or AXI.
  * Those remain platform/SoC responsibilities.
  *
  * This is the first ownership seam for turning the historical Linux platform
  * shell into a replaceable CPU complex inside AetherSoC.
  */
class AetherCoreV2Complex(
    val config: CoreConfig,
    val geometry: PageTableGeometry,
    val txnIdBits: Int = 2,
    val dcacheEntries: Int = 64,
    val enableInstructionBackpressure: Boolean = false
) extends Module {
  private val xlen = config.isa.xlen
  private val paddrBits = config.platform.paddrBits
  private val dataBits = config.platform.busDataBits

  require(config.isa.hasTimeCounter,
    "AetherCoreV2Complex v0 currently targets the Linux time-counter profile")

  val io = IO(new Bundle {
    val imem = new InstructionBusIO(paddrBits)
    val imemReady =
      if (enableInstructionBackpressure) Some(Input(Bool())) else None
    val ptw = new PageTableReadBusIO(paddrBits, geometry.pteBits)

    // PMA/address-map classification is a SoC responsibility. The CPU complex
    // exports the resolved physical address and consumes only semantic
    // attributes for the exact request being formed.
    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(paddrBits.W))
    val resolvedAttributes = Input(new MemoryAttributes)

    // CPU-complex memory master after the private D-cache.
    val memoryRequest =
      Decoupled(new AetherMemRequest(paddrBits, dataBits, txnIdBits))
    val memoryResponse =
      Flipped(Decoupled(new AetherMemResponse(dataBits, txnIdBits)))

    val time = Input(UInt(64.W))
    val timerInterrupt = Input(Bool())
    val supervisorExternalInterrupt = Input(Bool())

    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    val instructionFence = Output(Bool())

    // Transitional read-only observation seam for the existing Linux
    // performance/oracle tooling. These are not SoC control inputs.
    val occupancy = Output(UInt(3.W))
    val frontendPc = Output(UInt(xlen.W))
    val interruptHold = Output(Bool())
    val lsuBusy = Output(Bool())

    val commit = Output(new CommitTrace(xlen, paddrBits, dataBits))
    val halted = Output(Bool())
  })

  private val core = Module(new TinyPagedCore(
    config,
    geometry,
    tlbEntries = 4,
    txnIdBits = txnIdBits,
    enableInstructionBackpressure = enableInstructionBackpressure,
    enableAsyncInterrupts = true,
    withSupervisorExternalInterrupt = true
  ))

  // Transitional read-only elaboration seam for legacy simulation attribution.
  // Production SoC wiring must use only this module's IO; the attribution path
  // will be migrated to explicit observation taps before the old Linux shell is
  // removed.
  val backend = core.backend
  val fetch = core.fetch
  val parcel = core.parcel

  // Keep the existing physical instruction-fetch seam during the staged SoC
  // migration. The I-cache/fabric ownership remains outside this CPU-complex
  // cut for now and will be normalized in a later migration step.
  io.imem.valid := core.io.imem.valid
  io.imem.addr := core.io.imem.addr
  io.imem.bytes := core.io.imem.bytes
  core.io.imem.inst := io.imem.inst
  core.io.imem.fault := io.imem.fault
  if (enableInstructionBackpressure) {
    core.io.imemReady.get := io.imemReady.get
  }

  io.ptw.valid := core.io.ptw.valid
  io.ptw.addr := core.io.ptw.addr
  core.io.ptw.ready := io.ptw.ready
  core.io.ptw.rdata := io.ptw.rdata
  core.io.ptw.fault := io.ptw.fault

  core.io.time.get := io.time
  core.io.timerInterrupt.get := io.timerInterrupt
  core.io.supervisorExternalInterrupt.get := io.supervisorExternalInterrupt

  io.resolvedPhysicalValid := core.io.resolvedPhysicalValid
  io.resolvedPhysicalAddress := core.io.resolvedPhysicalAddress
  core.io.resolvedAttributes := io.resolvedAttributes

  // D-cache is CPU-complex state, not a board/platform peripheral. Reads may
  // allocate, writes remain write-through, and MMIO/atomic policy still follows
  // the semantic MemoryAttributes supplied by the SoC.
  private val dcache = Module(new AetherDirectMappedReadCache(
    paddrBits,
    dataBits,
    txnIdBits,
    entries = dcacheEntries
  ))
  dcache.io.upstreamRequest <> core.io.memoryRequest
  core.io.memoryResponse <> dcache.io.upstreamResponse

  io.memoryRequest.valid := dcache.io.downstreamRequest.valid
  io.memoryRequest.bits := dcache.io.downstreamRequest.bits
  dcache.io.downstreamRequest.ready := io.memoryRequest.ready

  dcache.io.downstreamResponse.valid := io.memoryResponse.valid
  dcache.io.downstreamResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := dcache.io.downstreamResponse.ready

  io.dcacheHitCount := dcache.io.hitCount
  io.dcacheMissCount := dcache.io.missCount
  io.dcacheBypassCount := dcache.io.bypassCount
  io.instructionFence := core.io.instructionFence
  io.occupancy := core.io.occupancy
  io.frontendPc := core.io.frontendPc
  io.interruptHold := core.io.interruptHold
  io.lsuBusy := core.io.lsuBusy
  io.commit := core.io.commit
  io.halted := core.io.halted
}
