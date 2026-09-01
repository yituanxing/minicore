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
    val uartPlicSourceId: Int = 10,
    val uartResetDivisor: Int = 1,
    val externalSemanticMemory: Boolean = false
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

    // Preferred semantic external-RAM seam. Unified/AXI/FPGA compositions opt
    // into this interface so transaction identity survives below the platform
    // decoder. The legacy terminal-response seam remains for the historical
    // Linux oracle only.
    val externalRequest =
      if (externalSemanticMemory)
        Some(Decoupled(new AetherMemRequest(paddrBits, dataBits, txnIdBits)))
      else None
    val externalResponse =
      if (externalSemanticMemory)
        Some(Flipped(Decoupled(new AetherMemResponse(dataBits, txnIdBits))))
      else None

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
    val uartBaudDivisor = Output(UInt(16.W))

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

  private def alignedPowerOfTwoHit(address: UInt, base: BigInt, bytes: BigInt): Bool = {
    require(bytes > 0 && (bytes & (bytes - 1)) == 0,
      s"AetherSoC decode aperture must be a power of two, got $bytes")
    require((base & (bytes - 1)) == 0,
      s"AetherSoC decode aperture must be naturally aligned: base=$base bytes=$bytes")
    val offsetBits = bytes.bitLength - 1
    require(offsetBits < paddrBits,
      s"AetherSoC decode aperture $bytes exceeds paddrBits=$paddrBits")
    if (offsetBits == 0) {
      address === base.U(paddrBits.W)
    } else {
      address(paddrBits - 1, offsetBits) ===
        (base >> offsetBits).U((paddrBits - offsetBits).W)
    }
  }

  private val ramBase = addressMap.ramBase.U(paddrBits.W)
  private val uartBase = addressMap.uartBase.U(paddrBits.W)
  private val exitAddress = addressMap.exitAddress.U(paddrBits.W)
  private val mtimeAddress = addressMap.mtimeAddress.U(paddrBits.W)
  private val mtimecmpAddress = addressMap.mtimecmpAddress.U(paddrBits.W)
  private val plicBase = addressMap.plicBase.U(paddrBits.W)

  // PMA policy is now fabric-owned. RAM is the only first-stage region that is
  // cacheable/idempotent/executable and advertises atomic support.
  private val resolvedRam =
    alignedPowerOfTwoHit(
      io.resolvedPhysicalAddress,
      addressMap.ramBase,
      addressMap.ramBytes
    )
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
    pendingValid && alignedPowerOfTwoHit(
      pending.paddr,
      addressMap.uartBase,
      addressMap.uartBytes
    )
  private val pendingExit =
    pendingValid && pending.paddr === exitAddress
  private val pendingTimer =
    pendingValid && (pending.paddr === mtimeAddress || pending.paddr === mtimecmpAddress)
  private val pendingPlic =
    pendingValid && alignedPowerOfTwoHit(
      pending.paddr,
      addressMap.plicBase,
      addressMap.plicBytes
    )
  private val pendingMmio =
    pendingUart || pendingExit || pendingTimer || pendingPlic
  private val pendingExternal =
    pendingValid && !pendingMmio

  private val uart = Module(new AetherUart16550(
    dataBits = dataBits,
    rxDepth = 16,
    resetDivisor = uartResetDivisor
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
  private val mmioData = Mux(
    pendingPlic,
    plic.io.rdata.pad(dataBits),
    Mux(pendingTimer, timer.io.rdata,
      Mux(pendingUart, uart.io.rdata, 0.U))
  )
  private val mmioFault = Mux(
    pendingPlic,
    plic.io.fault,
    Mux(pendingTimer, timer.io.fault,
      Mux(pendingUart, uart.io.fault, false.B))
  )

  // Keep compatibility outputs deterministic in both compositions.
  io.memValid := false.B
  io.memWrite := pendingWrite
  io.memAtomic := pendingAtomic
  io.memOp := pending.op
  io.memAtomicOp := pending.atomicOp
  io.memAddr := pending.paddr
  io.memWdata := pending.wdata
  io.memWmask := pending.wmask
  io.memSize := pending.size
  io.memAttributes := pending.attributes

  private val responseFire = WireDefault(false.B)
  private val mmioResponseFire = WireDefault(false.B)

  if (externalSemanticMemory) {
    val TxnCount = 1 << txnIdBits
    val ReadCountBits = log2Ceil(TxnCount + 1)
    val normalReadOutstanding =
      RegInit(VecInit(Seq.fill(TxnCount)(false.B)))
    val normalReadCount = RegInit(0.U(ReadCountBits.W))
    val serializedExternalActive = RegInit(false.B)

    val pendingNormalExternalRead =
      pendingExternal &&
        pending.op === AetherMemOp.Read &&
        !pending.attributes.sideEffecting &&
        !pending.attributes.ordered
    val pendingSerializedExternal =
      pendingExternal && !pendingNormalExternalRead

    val pendingReadSlotFree =
      !normalReadOutstanding(pending.txnId)
    val canIssueNormalRead =
      pendingNormalExternalRead &&
        !serializedExternalActive &&
        pendingReadSlotFree
    val canIssueSerializedExternal =
      pendingSerializedExternal &&
        normalReadCount === 0.U &&
        !serializedExternalActive
    val canRunMmio =
      pendingMmio &&
        normalReadCount === 0.U &&
        !serializedExternalActive

    io.externalRequest.get.valid :=
      pendingValid && (canIssueNormalRead || canIssueSerializedExternal)
    io.externalRequest.get.bits := pending

    val externalRequestFire = io.externalRequest.get.fire
    val normalReadIssue =
      externalRequestFire && pendingNormalExternalRead
    val serializedExternalIssue =
      externalRequestFire && pendingSerializedExternal

    when(normalReadIssue) {
      normalReadOutstanding(pending.txnId) := true.B
    }
    when(serializedExternalIssue) {
      serializedExternalActive := true.B
    }

    val externalResponseKnown =
      serializedExternalActive ||
        normalReadOutstanding(io.externalResponse.get.bits.txnId)

    val responseArbiter = Module(new RRArbiter(
      new AetherMemResponse(dataBits, txnIdBits),
      2
    ))

    responseArbiter.io.in(0).valid :=
      io.externalResponse.get.valid && externalResponseKnown
    responseArbiter.io.in(0).bits := io.externalResponse.get.bits
    io.externalResponse.get.ready :=
      responseArbiter.io.in(0).ready && externalResponseKnown

    responseArbiter.io.in(1).valid :=
      pendingValid && canRunMmio && mmioReady
    responseArbiter.io.in(1).bits.txnId := pending.txnId
    responseArbiter.io.in(1).bits.rdata := mmioData
    responseArbiter.io.in(1).bits.fault := mmioFault
    responseArbiter.io.in(1).bits.last := true.B

    io.response <> responseArbiter.io.out
    responseFire := io.response.fire
    mmioResponseFire := responseArbiter.io.in(1).fire

    val externalResponseFire = responseArbiter.io.in(0).fire
    val normalReadRetire =
      externalResponseFire && !serializedExternalActive

    when(normalReadRetire) {
      val txn = io.externalResponse.get.bits.txnId
      assert(normalReadOutstanding(txn),
        "PlatformFabric received a RAM read response for a non-outstanding txnId")
      normalReadOutstanding(txn) := false.B
    }

    when(externalResponseFire && serializedExternalActive) {
      serializedExternalActive := false.B
    }

    switch(Cat(normalReadIssue, normalReadRetire)) {
      is("b10".U) { normalReadCount := normalReadCount + 1.U }
      is("b01".U) { normalReadCount := normalReadCount - 1.U }
    }

    when(io.externalResponse.get.valid) {
      assert(externalResponseKnown,
        "PlatformFabric received an external response with no live transaction")
    }

    // Normal RAM reads leave the request queue at acceptance and may complete
    // later/out of order. Serialized RAM operations also leave at acceptance,
    // but hold the global barrier until their terminal response. MMIO retains
    // the historical terminal-response ownership in the queue.
    pendingQueue.io.deq.ready :=
      externalRequestFire || mmioResponseFire

    uartComplete := mmioResponseFire
    timerComplete := mmioResponseFire
    plicComplete := mmioResponseFire

    io.exitValid := mmioResponseFire && pendingExit && pendingWrite
    io.exitCode := pending.wdata

    assert(!(pendingMmio && pendingAtomic),
      "AetherSoC PMA must reject atomic MMIO before it reaches the platform fabric")
    when(serializedExternalActive) {
      assert(normalReadCount === 0.U,
        "serialized external RAM lifetime must exclude concurrent normal reads")
    }
  } else {
    // Historical terminal-response external-memory contract. This branch is
    // intentionally kept behavior-identical for the qualified compatibility
    // oracle while production Unified/AXI/FPGA paths use the semantic seam.
    io.externalRequest.foreach { request =>
      request.valid := false.B
      request.bits := 0.U.asTypeOf(request.bits)
    }
    io.externalResponse.foreach(_.ready := false.B)

    val responseReady = Mux(pendingExternal, io.memReady, mmioReady)
    val responseData = Mux(
      pendingExternal,
      io.memRdata,
      mmioData
    )
    val responseFault = Mux(
      pendingExternal,
      io.memFault,
      mmioFault
    )

    io.response.valid := pendingValid && responseReady
    io.response.bits.txnId := pending.txnId
    io.response.bits.rdata := responseData
    io.response.bits.fault := responseFault
    io.response.bits.last := true.B

    responseFire := io.response.fire
    pendingQueue.io.deq.ready := responseFire
    uartComplete := responseFire
    timerComplete := responseFire
    plicComplete := responseFire

    io.memValid := pendingExternal

    io.exitValid := responseFire && pendingExit && pendingWrite
    io.exitCode := pending.wdata

    assert(!(pendingMmio && pendingAtomic),
      "AetherSoC PMA must reject atomic MMIO before it reaches the platform fabric")
  }

  io.rxReady := uart.io.rxReady
  io.uartValid := uart.io.txValid
  io.uartByte := uart.io.txByte
  io.uartBaudDivisor := uart.io.baudDivisor
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
