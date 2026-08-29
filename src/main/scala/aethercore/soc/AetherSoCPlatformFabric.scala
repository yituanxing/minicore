package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse, MemoryAttributes}
import aethercore.soc.peripheral.{AetherAclintMtimer, AetherPlic, AetherPlicMap, AetherUart16550}

/**
  * Data-side AetherSoC platform fabric for the qualified RV64 board contract.
  *
  * Responsibilities owned here:
  *   - PMA classification for the CPU-complex resolved physical address
  *   - bounded request buffering
  *   - software-visible address decode
  *   - UART / PLIC / MTIMER instantiation and routing
  *   - simulation-exit MMIO termination
  *   - external RAM selection
  *
  * The CPU complex therefore sees only semantic AetherMem plus PMA attributes;
  * board devices and address-map policy no longer live in the Linux wrapper.
  */
class AetherSoCPlatformFabric(
    val paddrBits: Int,
    val dataBits: Int,
    val txnIdBits: Int,
    val addressMap: AetherSoCAddressMap,
    val plicSourceCount: Int = 52,
    val uartPlicSourceId: Int = 10
) extends Module {
  require(dataBits == 64, "AetherSoC v0 platform fabric currently targets RV64")
  require(txnIdBits > 0)
  require(uartPlicSourceId > 0 && uartPlicSourceId <= plicSourceCount)

  private val busBytes = dataBits / 8

  val io = IO(new Bundle {
    // PMA classification seam consumed by AetherCoreV2Complex before request issue.
    val resolvedPhysicalAddress = Input(UInt(paddrBits.W))
    val resolvedAttributes = Output(new MemoryAttributes)

    // CPU-complex semantic memory master.
    val request = Flipped(Decoupled(new AetherMemRequest(paddrBits, dataBits, txnIdBits)))
    val response = Decoupled(new AetherMemResponse(dataBits, txnIdBits))

    // External RAM / lower-memory compatibility seam.
    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAtomic = Output(Bool())
    val memOp = Output(AetherMemOp())
    val memAtomicOp = Output(AtomicOp())
    val memAddr = Output(UInt(paddrBits.W))
    val memWdata = Output(UInt(dataBits.W))
    val memWmask = Output(UInt(busBytes.W))
    val memSize = Output(MemSize())
    val memAttributes = Output(new MemoryAttributes)
    val memReady = Input(Bool())
    val memRdata = Input(UInt(dataBits.W))
    val memFault = Input(Bool())

    // Board-facing byte-stream UART.
    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val uartTxReady = Input(Bool())

    // Board-owned architectural timebase pulse. The simulator may assert this
    // every cycle; an FPGA clock/reset shell must generate the declared rate.
    val timebaseTick = Input(Bool())

    // Interrupt/time topology returned to the CPU complex.
    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val time = Output(UInt(64.W))
    val timerInterrupt = Output(Bool())

    // Software-visible observability retained by the qualified host wrapper.
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(dataBits.W))
    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
  })

  private val ramBase = addressMap.ramBase.U(paddrBits.W)
  private val ramLimit = addressMap.ramLimit.U(paddrBits.W)
  private val uartBase = addressMap.uartBase.U(paddrBits.W)
  private val uartLimit = addressMap.uartLimit.U(paddrBits.W)
  private val exitAddress = addressMap.exitAddress.U(paddrBits.W)
  private val mtimeAddress = addressMap.mtimeAddress.U(paddrBits.W)
  private val mtimecmpAddress = addressMap.mtimecmpAddress.U(paddrBits.W)
  private val plicBase = addressMap.plicBase.U(paddrBits.W)
  private val plicLimit = addressMap.plicLimit.U(paddrBits.W)

  // PMA policy is now fabric-owned. RAM is the only first-stage region that is
  // cacheable/idempotent/executable and advertises atomic support.
  private val resolvedRam =
    io.resolvedPhysicalAddress >= ramBase && io.resolvedPhysicalAddress < ramLimit
  io.resolvedAttributes.cacheable := resolvedRam
  io.resolvedAttributes.idempotent := resolvedRam
  io.resolvedAttributes.sideEffecting := !resolvedRam
  io.resolvedAttributes.ordered := !resolvedRam
  io.resolvedAttributes.executable := resolvedRam
  io.resolvedAttributes.supportsAtomic := resolvedRam
  io.resolvedAttributes.supportsPartial := true.B

  // Preserve the qualified LoadQ2 concurrency at the fabric boundary.
  private val pendingQueue = Module(new Queue(
    new AetherMemRequest(paddrBits, dataBits, txnIdBits),
    entries = 2,
    pipe = true
  ))
  pendingQueue.io.enq <> io.request

  private val pendingValid = pendingQueue.io.deq.valid
  private val pending = pendingQueue.io.deq.bits
  private val pendingWrite = pending.op === AetherMemOp.Write
  private val pendingAtomic = pending.op === AetherMemOp.Atomic

  private val pendingUart =
    pendingValid && pending.paddr >= uartBase && pending.paddr < uartLimit
  private val pendingExit =
    pendingValid && pending.paddr === exitAddress
  private val pendingTimer =
    pendingValid && (pending.paddr === mtimeAddress || pending.paddr === mtimecmpAddress)
  private val pendingPlic =
    pendingValid && pending.paddr >= plicBase && pending.paddr < plicLimit
  private val pendingMmio =
    pendingUart || pendingExit || pendingTimer || pendingPlic
  private val pendingExternal =
    pendingValid && !pendingMmio

  private val uart = Module(new AetherUart16550(
    dataBits = dataBits,
    rxDepth = 16
  ))
  private val uartComplete = WireDefault(false.B)
  uart.io.request := pendingUart
  uart.io.write := pendingWrite
  uart.io.offset := (pending.paddr - uartBase)(2, 0)
  uart.io.wdata := pending.wdata
  uart.io.wmask := pending.wmask
  uart.io.complete := uartComplete
  uart.io.rxValid := io.rxValid
  uart.io.rxByte := io.rxByte
  uart.io.txReady := io.uartTxReady

  private val plic = Module(new AetherPlic(
    sourceCount = plicSourceCount,
    addressBits = 24,
    enableBase = AetherPlicMap.SupervisorEnable,
    thresholdOffset = AetherPlicMap.SupervisorThreshold,
    claimCompleteOffset = AetherPlicMap.SupervisorClaimComplete
  ))
  private val plicComplete = WireDefault(false.B)
  plic.io.sources :=
    (uart.io.interrupt.asUInt << (uartPlicSourceId - 1)).pad(plicSourceCount)
  plic.io.request := pendingPlic
  plic.io.write := pendingWrite
  plic.io.address := (pending.paddr - plicBase)(23, 0)
  plic.io.wdata := pending.wdata(31, 0)
  plic.io.wmask := pending.wmask(3, 0)
  plic.io.complete := plicComplete

  private val timer = Module(new AetherAclintMtimer(
    dataBits = dataBits
  ))
  private val timerComplete = WireDefault(false.B)
  timer.io.request := pendingTimer
  timer.io.write := pendingWrite
  timer.io.selectMtimecmp := pending.paddr === mtimecmpAddress
  timer.io.wdata := pending.wdata
  timer.io.wmask := pending.wmask
  timer.io.complete := timerComplete
  timer.io.timebaseTick := io.timebaseTick

  private val mmioReady = Mux(
    pendingPlic,
    plic.io.ready,
    Mux(pendingTimer, timer.io.ready,
      Mux(pendingUart, uart.io.ready, true.B))
  )
  private val responseReady = Mux(pendingExternal, io.memReady, mmioReady)
  private val responseData = Mux(
    pendingPlic,
    plic.io.rdata.pad(dataBits),
    Mux(pendingTimer, timer.io.rdata,
      Mux(pendingUart, uart.io.rdata, io.memRdata))
  )
  private val responseFault = Mux(
    pendingPlic,
    plic.io.fault,
    Mux(pendingTimer, timer.io.fault,
      Mux(pendingUart, uart.io.fault,
        Mux(pendingMmio, false.B, io.memFault)))
  )

  io.response.valid := pendingValid && responseReady
  io.response.bits.txnId := pending.txnId
  io.response.bits.rdata := responseData
  io.response.bits.fault := responseFault
  io.response.bits.last := true.B

  private val responseFire = io.response.fire
  pendingQueue.io.deq.ready := responseFire
  uartComplete := responseFire
  timerComplete := responseFire
  plicComplete := responseFire

  io.memValid := pendingExternal
  io.memWrite := pendingWrite
  io.memAtomic := pendingAtomic
  io.memOp := pending.op
  io.memAtomicOp := pending.atomicOp
  io.memAddr := pending.paddr
  io.memWdata := pending.wdata
  io.memWmask := pending.wmask
  io.memSize := pending.size
  io.memAttributes := pending.attributes

  io.rxReady := uart.io.rxReady
  io.uartValid := uart.io.txValid
  io.uartByte := uart.io.txByte
  io.uartInterrupt := uart.io.interrupt
  io.uartRxInterrupt := uart.io.rxInterrupt

  io.supervisorExternalInterrupt := plic.io.interrupt
  io.time := timer.io.mtime
  io.timerInterrupt := timer.io.interrupt
  io.mtime := timer.io.mtime
  io.mtimecmp := timer.io.mtimecmp

  io.exitValid := responseFire && pendingExit && pendingWrite
  io.exitCode := pending.wdata

  assert(!(pendingMmio && pendingAtomic),
    "AetherSoC PMA must reject atomic MMIO before it reaches the platform fabric")
}
