package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.{MachinePlicMmio, MachinePlicMmioMap}
import aethercore.memory.{AetherMemOp, AetherMemRequest, MemoryAttributes}
import aethercore.soc.peripheral.{AetherAclintMtimer, AetherUart16550}

/**
  * F7 RV64 OpenSBI simulation boundary for the v2 core.
  *
  * This intentionally reuses the frozen software-visible board contract rather
  * than teaching the CPU about UART/ACLINT/PLIC addresses. TinyPagedCore keeps
  * speaking AetherMem; this shell classifies PMA attributes, terminates MMIO,
  * and preserves AetherMem Atomic operations all the way to the host RAM model.
  *
  * The external RAM interface is deliberately close to AetherCoreSimTop so the
  * qualified OpenSBI host runner can be reused. memAtomic/memAtomicOp are the
  * only additions required to retain LR/SC/AMO semantics at the memory owner.
  */
class AetherCoreV2LinuxSoC(
    val enableInstructionBackpressure: Boolean = false,
    val exposeExternalMemoryAttributes: Boolean = false
) extends Module {
  private val config = CoreProfiles.rv64imasuSv39PmpSoftware.copy(
    name = "rv64imasu-sv39-pmp-opensbi-v2",
    isa = CoreProfiles.rv64imasuSv39PmpSoftware.isa.copy(
      zExtensions = CoreProfiles.rv64imasuSv39PmpSoftware.isa.zExtensions + "Zifencei",
      machineProvidedSupervisorTimer = true,
      timeCounter = true
    )
  )
  private val geometry = PageTableGeometry.Sv39
  private val xlen = config.isa.xlen
  private val paddrBits = config.platform.paddrBits
  private val busDataBits = config.platform.busDataBits
  private val busBytes = config.platform.busBytes
  private val txnIdBits = 2

  private val ramBase = BigInt("80000000", 16)
  private val ramLimit = ramBase + BigInt("10000000", 16)
  private val plicBase = BigInt("0c000000", 16)
  private val plicLimit = plicBase + BigInt("00400000", 16)
  private val uartLimit = config.platform.uartAddress + BigInt(8)
  private val supervisorPlicSourceCount = 52
  private val supervisorUartSourceId = 10

  val io = IO(new Bundle {
    val imemValid = Output(Bool())
    val imemAddr = Output(UInt(paddrBits.W))
    val imemBytes = Output(UInt(3.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())
    val imemReady =
      if (enableInstructionBackpressure) Some(Input(Bool())) else None

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAtomic = Output(Bool())
    val memOp = Output(AetherMemOp())
    val memAtomicOp = Output(AtomicOp())
    val memAddr = Output(UInt(paddrBits.W))
    val memWdata = Output(UInt(busDataBits.W))
    val memWmask = Output(UInt(busBytes.W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(busDataBits.W))
    val memFault = Input(Bool())
    val memAttributes =
      if (exposeExternalMemoryAttributes) Some(Output(new MemoryAttributes)) else None

    val ptwValid = Output(Bool())
    val ptwAddr = Output(UInt(paddrBits.W))
    val ptwReady = Input(Bool())
    val ptwRdata = Input(UInt(geometry.pteBits.W))
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
    val exitCode = Output(UInt(xlen.W))

    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
    val timerInterrupt = Output(Bool())

    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    val instructionFence = Output(Bool())

    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  val core = Module(new AetherCoreV2Complex(
    config,
    geometry,
    txnIdBits = txnIdBits,
    dcacheEntries = 64,
    enableInstructionBackpressure = enableInstructionBackpressure
  ))

  // Instruction and PTW transport remain direct read-only host-memory ports.
  io.imemValid := core.io.imem.valid
  io.imemAddr := core.io.imem.addr
  io.imemBytes := core.io.imem.bytes
  core.io.imem.inst := io.imemInst
  core.io.imem.fault := io.imemFault
  if (enableInstructionBackpressure) {
    core.io.imemReady.get := io.imemReady.get
  }

  io.ptwValid := core.io.ptw.valid
  io.ptwAddr := core.io.ptw.addr
  core.io.ptw.ready := io.ptwReady
  core.io.ptw.rdata := io.ptwRdata
  core.io.ptw.fault := io.ptwFault

  // PMA stays at the platform boundary. RAM is the only first-stage region
  // advertising atomic support; device/unknown addresses fail atomics closed.
  val resolvedAddress = core.io.resolvedPhysicalAddress
  val resolvedRam = resolvedAddress >= ramBase.U && resolvedAddress < ramLimit.U
  core.io.resolvedAttributes.cacheable := resolvedRam
  core.io.resolvedAttributes.idempotent := resolvedRam
  core.io.resolvedAttributes.sideEffecting := !resolvedRam
  core.io.resolvedAttributes.ordered := !resolvedRam
  core.io.resolvedAttributes.executable := resolvedRam
  core.io.resolvedAttributes.supportsAtomic := resolvedRam
  core.io.resolvedAttributes.supportsPartial := true.B

  // The private D-cache now belongs to AetherCoreV2Complex. This platform
  // sees only the CPU-complex semantic memory master and remains the PMA/MMIO
  // owner during the staged migration toward the final AetherSoC fabric.

  // Retain up to two downstream AetherMem requests so a cache miss does not
  // collapse the core's qualified LoadQ2 transaction concurrency.
  val pendingQueue = Module(new Queue(
    new AetherMemRequest(paddrBits, busDataBits, txnIdBits),
    entries = 2,
    pipe = true
  ))
  pendingQueue.io.enq.valid := core.io.memoryRequest.valid
  pendingQueue.io.enq.bits := core.io.memoryRequest.bits
  core.io.memoryRequest.ready := pendingQueue.io.enq.ready

  val pendingValid = pendingQueue.io.deq.valid
  val pending = pendingQueue.io.deq.bits

  val pendingWrite = pending.op === AetherMemOp.Write
  val pendingAtomic = pending.op === AetherMemOp.Atomic
  val uartAddress = config.platform.uartAddress.U(paddrBits.W)
  val exitAddress = config.platform.exitAddress.U(paddrBits.W)
  val mtimeAddress = config.platform.mtimeAddress.U(paddrBits.W)
  val mtimecmpAddress = config.platform.mtimecmpAddress.U(paddrBits.W)
  val pendingUart = pendingValid && pending.paddr >= uartAddress && pending.paddr < uartLimit.U
  val pendingExit = pendingValid && pending.paddr === exitAddress
  val pendingTimer = pendingValid &&
    (pending.paddr === mtimeAddress || pending.paddr === mtimecmpAddress)
  val pendingPlic = pendingValid && pending.paddr >= plicBase.U && pending.paddr < plicLimit.U
  val pendingMmio = pendingUart || pendingExit || pendingTimer || pendingPlic
  val pendingExternal = pendingValid && !pendingMmio

  // UART register/FIFO/IRQ state is now an independent SoC peripheral.
  // This platform shell owns only region selection and the terminal transaction
  // pulse while the migration toward a reusable fabric continues.
  val uart = Module(new AetherUart16550(
    dataBits = busDataBits,
    rxDepth = 16
  ))
  val uartOffset = pending.paddr - uartAddress
  val uartComplete = WireDefault(false.B)
  uart.io.request := pendingUart
  uart.io.write := pendingWrite
  uart.io.offset := uartOffset(2, 0)
  uart.io.wdata := pending.wdata
  uart.io.wmask := pending.wmask
  uart.io.complete := uartComplete
  uart.io.rxValid := io.rxValid
  uart.io.rxByte := io.rxByte
  io.rxReady := uart.io.rxReady
  io.uartInterrupt := uart.io.interrupt
  io.uartRxInterrupt := uart.io.rxInterrupt

  val supervisorPlic = Module(new MachinePlicMmio(
    sourceCount = supervisorPlicSourceCount,
    addressBits = 24,
    enableBase = MachinePlicMmioMap.SupervisorEnable,
    thresholdOffset = MachinePlicMmioMap.SupervisorThreshold,
    claimCompleteOffset = MachinePlicMmioMap.SupervisorClaimComplete
  ))
  supervisorPlic.io.sources :=
    (uart.io.interrupt.asUInt << (supervisorUartSourceId - 1)).pad(supervisorPlicSourceCount)
  supervisorPlic.io.request := pendingPlic
  supervisorPlic.io.write := pendingWrite
  supervisorPlic.io.address := (pending.paddr - plicBase.U)(23, 0)
  supervisorPlic.io.wdata := pending.wdata(31, 0)
  supervisorPlic.io.wmask := pending.wmask(3, 0)
  core.io.supervisorExternalInterrupt := supervisorPlic.io.interrupt
  io.supervisorExternalInterrupt := supervisorPlic.io.interrupt

  // ACLINT/CLINT MTIMER state is an independent SoC peripheral. The
  // platform retains only address selection while the reusable MMIO fabric is
  // introduced in a later ownership cut.
  val timer = Module(new AetherAclintMtimer(
    dataBits = busDataBits
  ))
  val timerComplete = WireDefault(false.B)
  timer.io.request := pendingTimer
  timer.io.write := pendingWrite
  timer.io.selectMtimecmp := pending.paddr === mtimecmpAddress
  timer.io.wdata := pending.wdata
  timer.io.wmask := pending.wmask
  timer.io.complete := timerComplete

  val mmioReady = Mux(
    pendingPlic,
    supervisorPlic.io.ready,
    Mux(pendingTimer, timer.io.ready,
      Mux(pendingUart, uart.io.ready, true.B))
  )
  val responseReady = Mux(pendingExternal, io.memReady, mmioReady)
  val responseData = Mux(
    pendingPlic,
    supervisorPlic.io.rdata.pad(busDataBits),
    Mux(pendingTimer, timer.io.rdata,
      Mux(pendingUart, uart.io.rdata, io.memRdata))
  )
  val responseFault = Mux(
    pendingPlic,
    supervisorPlic.io.fault,
    Mux(pendingTimer, timer.io.fault,
      Mux(pendingUart, uart.io.fault,
        Mux(pendingMmio, false.B, io.memFault)))
  )

  core.io.memoryResponse.valid := pendingValid && responseReady
  core.io.memoryResponse.bits.txnId := pending.txnId
  core.io.memoryResponse.bits.rdata := responseData
  core.io.memoryResponse.bits.fault := responseFault
  core.io.memoryResponse.bits.last := true.B
  val responseFire = core.io.memoryResponse.fire
  pendingQueue.io.deq.ready := responseFire
  uartComplete := responseFire
  timerComplete := responseFire

  core.io.time := timer.io.mtime
  core.io.timerInterrupt := timer.io.interrupt

  io.memValid := pendingExternal
  io.memWrite := pendingWrite
  io.memAtomic := pendingAtomic
  io.memOp := pending.op
  io.memAtomicOp := pending.atomicOp
  io.memAddr := pending.paddr
  io.memWdata := pending.wdata
  io.memWmask := pending.wmask
  io.memSize := pending.size
  if (exposeExternalMemoryAttributes) {
    io.memAttributes.get := pending.attributes
  }

  io.uartValid := uart.io.txValid
  io.uartByte := uart.io.txByte
  io.exitValid := responseFire && pendingExit && pendingWrite
  io.exitCode := pending.wdata

  io.mtime := timer.io.mtime
  io.mtimecmp := timer.io.mtimecmp
  io.timerInterrupt := timer.io.interrupt
  io.dcacheHitCount := core.io.dcacheHitCount
  io.dcacheMissCount := core.io.dcacheMissCount
  io.dcacheBypassCount := core.io.dcacheBypassCount
  io.instructionFence := core.io.instructionFence
  io.commit := core.io.commit
  io.halted := core.io.halted

  assert(!(pendingMmio && pendingAtomic),
    "F7 PMA must reject atomic MMIO before it reaches the OpenSBI platform adapter")
}
