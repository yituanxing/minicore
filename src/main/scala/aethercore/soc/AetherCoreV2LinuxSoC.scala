package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.{MachinePlicMmio, MachinePlicMmioMap}
import aethercore.core.v2.TinyPagedCore
import aethercore.memory.{AetherDirectMappedReadCache, AetherMemOp, AetherMemRequest}

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
class AetherCoreV2LinuxSoC extends Module {
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

    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  private def mergeBytes(oldValue: UInt, newValue: UInt, mask: UInt, bytes: Int): UInt =
    Cat((0 until bytes).reverse.map { byte =>
      val high = byte * 8 + 7
      val low = byte * 8
      Mux(mask(byte), newValue(high, low), oldValue(high, low))
    })

  val core = Module(new TinyPagedCore(
    config,
    geometry,
    txnIdBits = txnIdBits,
    enableAsyncInterrupts = true,
    withSupervisorExternalInterrupt = true
  ))

  // Instruction and PTW transport remain direct read-only host-memory ports.
  io.imemValid := core.io.imem.valid
  io.imemAddr := core.io.imem.addr
  io.imemBytes := core.io.imem.bytes
  core.io.imem.inst := io.imemInst
  core.io.imem.fault := io.imemFault

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

  // Stage-1 core-complex D-cache. Only ordinary cacheable RAM Reads allocate;
  // writes remain write-through, atomics/MMIO bypass, and every AetherMem txnId
  // is preserved. The cache therefore sits entirely outside TinyPagedCore's
  // architectural/lifetime ownership.
  val dcache = Module(new AetherDirectMappedReadCache(
    paddrBits,
    busDataBits,
    txnIdBits,
    entries = 64
  ))
  dcache.io.upstreamRequest <> core.io.memoryRequest
  core.io.memoryResponse <> dcache.io.upstreamResponse

  // Retain up to two downstream AetherMem requests so a cache miss does not
  // collapse the core's qualified LoadQ2 transaction concurrency.
  val pendingQueue = Module(new Queue(
    new AetherMemRequest(paddrBits, busDataBits, txnIdBits),
    entries = 2,
    pipe = true
  ))
  pendingQueue.io.enq.valid := dcache.io.downstreamRequest.valid
  pendingQueue.io.enq.bits := dcache.io.downstreamRequest.bits
  dcache.io.downstreamRequest.ready := pendingQueue.io.enq.ready

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

  // ns16550 subset retained from the frozen OpenSBI/Linux board shell.
  val uartLcr = RegInit(0.U(8.W))
  val uartIer = RegInit(0.U(8.W))
  val uartDll = RegInit(0.U(8.W))
  val uartDlm = RegInit(0.U(8.W))
  val uartMcr = RegInit(0.U(8.W))
  val uartScr = RegInit(0.U(8.W))
  val uartOffset = pending.paddr - uartAddress
  val uartDlab = uartLcr(7)

  val uartRx = Module(new Queue(UInt(8.W), 16))
  uartRx.io.enq.valid := io.rxValid
  uartRx.io.enq.bits := io.rxByte
  io.rxReady := uartRx.io.enq.ready
  val uartRxAvailable = uartRx.io.deq.valid
  val uartRxByte = uartRx.io.deq.bits

  val uartRxInterrupt = uartIer(0) && uartRxAvailable
  val uartThreInterrupt = uartIer(1)
  val uartCombinedInterrupt = uartRxInterrupt || uartThreInterrupt
  io.uartRxInterrupt := uartRxInterrupt
  io.uartInterrupt := uartCombinedInterrupt

  val supervisorPlic = Module(new MachinePlicMmio(
    sourceCount = supervisorPlicSourceCount,
    addressBits = 24,
    enableBase = MachinePlicMmioMap.SupervisorEnable,
    thresholdOffset = MachinePlicMmioMap.SupervisorThreshold,
    claimCompleteOffset = MachinePlicMmioMap.SupervisorClaimComplete
  ))
  supervisorPlic.io.sources :=
    (uartCombinedInterrupt.asUInt << (supervisorUartSourceId - 1)).pad(supervisorPlicSourceCount)
  supervisorPlic.io.request := pendingPlic
  supervisorPlic.io.write := pendingWrite
  supervisorPlic.io.address := (pending.paddr - plicBase.U)(23, 0)
  supervisorPlic.io.wdata := pending.wdata(31, 0)
  supervisorPlic.io.wmask := pending.wmask(3, 0)
  core.io.supervisorExternalInterrupt.get := supervisorPlic.io.interrupt
  io.supervisorExternalInterrupt := supervisorPlic.io.interrupt

  val mtime = RegInit(0.U(64.W))
  val mtimecmp = RegInit("hffffffffffffffff".U(64.W))
  val nextMtime = WireDefault(mtime + 1.U)
  val nextMtimecmp = WireDefault(mtimecmp)

  val uartReadData = WireDefault(0.U(busDataBits.W))
  switch(uartOffset(2, 0)) {
    is(0.U) { uartReadData := Mux(uartDlab, uartDll, uartRxByte).pad(busDataBits) }
    is(1.U) { uartReadData := Mux(uartDlab, uartDlm, uartIer).pad(busDataBits) }
    is(2.U) {
      uartReadData := Mux(uartRxInterrupt, 4.U,
        Mux(uartThreInterrupt, 2.U, 1.U)).pad(busDataBits)
    }
    is(3.U) { uartReadData := uartLcr.pad(busDataBits) }
    is(4.U) { uartReadData := uartMcr.pad(busDataBits) }
    is(5.U) { uartReadData := ("h60".U(8.W) | uartRxAvailable.asUInt).pad(busDataBits) }
    is(7.U) { uartReadData := uartScr.pad(busDataBits) }
  }

  val timerReadData = Mux(pending.paddr === mtimeAddress, mtime, mtimecmp)
  val mmioReady = Mux(pendingPlic, supervisorPlic.io.ready, true.B)
  val responseReady = Mux(pendingExternal, io.memReady, mmioReady)
  val responseData = Mux(
    pendingPlic,
    supervisorPlic.io.rdata.pad(busDataBits),
    Mux(pendingTimer, timerReadData,
      Mux(pendingUart, uartReadData, io.memRdata))
  )
  val responseFault = Mux(
    pendingPlic,
    supervisorPlic.io.fault,
    Mux(pendingMmio, false.B, io.memFault)
  )

  dcache.io.downstreamResponse.valid := pendingValid && responseReady
  dcache.io.downstreamResponse.bits.txnId := pending.txnId
  dcache.io.downstreamResponse.bits.rdata := responseData
  dcache.io.downstreamResponse.bits.fault := responseFault
  dcache.io.downstreamResponse.bits.last := true.B
  val responseFire = dcache.io.downstreamResponse.fire
  pendingQueue.io.deq.ready := responseFire

  // Apply MMIO state only when the exact AetherMem response is accepted.
  val uartRxPop = responseFire && pendingUart && !pendingWrite &&
    uartOffset(2, 0) === 0.U && !uartDlab
  uartRx.io.deq.ready := uartRxPop

  when(responseFire && pendingUart && pendingWrite) {
    switch(uartOffset(2, 0)) {
      is(0.U) {
        when(uartDlab) { uartDll := pending.wdata(7, 0) }
      }
      is(1.U) {
        when(uartDlab) {
          uartDlm := pending.wdata(7, 0)
        }.otherwise {
          uartIer := pending.wdata(7, 0)
        }
      }
      is(3.U) { uartLcr := pending.wdata(7, 0) }
      is(4.U) { uartMcr := pending.wdata(7, 0) }
      is(7.U) { uartScr := pending.wdata(7, 0) }
    }
  }

  when(responseFire && pendingTimer && pendingWrite) {
    when(pending.paddr === mtimeAddress) {
      nextMtime := mergeBytes(mtime, pending.wdata, pending.wmask, busBytes)
    }.otherwise {
      nextMtimecmp := mergeBytes(mtimecmp, pending.wdata, pending.wmask, busBytes)
    }
  }
  mtime := nextMtime
  mtimecmp := nextMtimecmp

  val timerInterrupt = mtime >= mtimecmp
  core.io.time.get := mtime
  core.io.timerInterrupt.get := timerInterrupt

  io.memValid := pendingExternal
  io.memWrite := pendingWrite
  io.memAtomic := pendingAtomic
  io.memOp := pending.op
  io.memAtomicOp := pending.atomicOp
  io.memAddr := pending.paddr
  io.memWdata := pending.wdata
  io.memWmask := pending.wmask
  io.memSize := pending.size

  val isUartTx = responseFire && pendingUart && pendingWrite &&
    uartOffset(2, 0) === 0.U && !uartDlab
  io.uartValid := isUartTx
  io.uartByte := pending.wdata(7, 0)
  io.exitValid := responseFire && pendingExit && pendingWrite
  io.exitCode := pending.wdata

  io.mtime := mtime
  io.mtimecmp := mtimecmp
  io.timerInterrupt := timerInterrupt
  io.dcacheHitCount := dcache.io.hitCount
  io.dcacheMissCount := dcache.io.missCount
  io.dcacheBypassCount := dcache.io.bypassCount
  io.commit := core.io.commit
  io.halted := core.io.halted

  assert(!(pendingMmio && pendingAtomic),
    "F7 PMA must reject atomic MMIO before it reaches the OpenSBI platform adapter")
}
