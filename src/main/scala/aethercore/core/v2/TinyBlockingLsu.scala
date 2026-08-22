package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize}
import aethercore.config.PageTableGeometry
import aethercore.core.{DataPathAdapter, PmpChecker, PmpConstants, PmpGeometry}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * Narrow F6 request contract for one architectural memory uOp.
  *
  * ROB lifetime, dependency wakeup and value-storage identities remain distinct
  * across the LSU seam. The physical-memory transaction ID is intentionally not
  * part of this request: AetherMem transaction identity is allocated by the LSU
  * only when a physical request is actually issued.
  */
class TinyMemoryRequest(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  require(xlen == 32 || xlen == 64, s"tiny-memory request XLEN must be 32 or 64, got $xlen")

  val robToken = new RobToken(identityBits, generationBits)
  val producerTag = new ProducerTag(identityBits, generationBits)
  val valueRef = new ValueRef(identityBits, generationBits)

  val kind = MemoryOperationKind()
  val size = MemSize()
  val unsigned = Bool()
  val atomicOp = AtomicOp()

  val base = UInt(xlen.W)
  val offset = UInt(xlen.W)
  val storeData = UInt(xlen.W)
  val rawInst = UInt(32.W)
}

/**
  * Physical memory observation kept outside ExecutionResponse.
  *
  * In particular, paddrBits is independent of XLEN: Sv32 needs a 34-bit PA
  * even though its architectural integer datapath is only 32 bits wide.
  */
class TinyMemoryTrace(
    val xlen: Int,
    val paddrBits: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  val robToken = new RobToken(identityBits, generationBits)
  val paddr = UInt(paddrBits.W)
  val write = Bool()
  val wdata = UInt(xlen.W)
  val wmask = UInt((xlen / 8).W)
}

/**
  * F6 correctness-first blocking LSU.
  *
  * Exactly one architectural memory uOp may be live. Address translation and
  * page/access-fault generation reuse DataPathAdapter; physical permission
  * checking reuses PmpChecker. Loads may issue once translation/PMP succeed.
  * Stores additionally require a live commit/head permit carrying the same full
  * RobToken, so an address-resolved store cannot become externally visible just
  * because execution reached the LSU.
  *
  * This slice deliberately leaves atomics for a later F6 extension and fails
  * them closed as Illegal Instruction instead of allowing an unsupported uOp to
  * wedge the ROB head.
  */
class TinyBlockingLsu(
    val geometry: PageTableGeometry,
    val paddrBits: Int = -1,
    val tlbEntries: Int = 8,
    val txnIdBits: Int = 2
) extends Module {
  private val Xlen = geometry.xlen
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val PhysicalBits =
    if (paddrBits > 0) paddrBits else geometry.architecturalPhysicalAddressBits
  private val BusBytes = Xlen / 8
  private val PmpAddressBits = PmpGeometry(Xlen, PhysicalBits).encodedAddressBits

  require(Xlen == 32 || Xlen == 64)
  require(PhysicalBits >= geometry.architecturalPhysicalAddressBits)
  require(txnIdBits > 0)

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits)))
    val completion = Valid(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
    val memoryTrace = Valid(new TinyMemoryTrace(Xlen, PhysicalBits, IdentityBits, GenerationBits))

    // This is a live permission indication, not a one-cycle speculative pulse.
    // Integration will derive it only for the current ROB head.
    val storePermit = Flipped(Valid(new RobToken(IdentityBits, GenerationBits)))

    val effectivePrivilege = Input(UInt(2.W))
    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(geometry.ppnBits.W))
    val supervisorSum = Input(Bool())
    val supervisorMxr = Input(Bool())
    val translationFlush = Input(Bool())

    val pmpEnabled = Input(Bool())
    val pmpConfig = Input(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Input(Vec(PmpConstants.MaxEntries, UInt(PmpAddressBits.W)))

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PhysicalBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    // PMA/attribute policy remains outside the LSU. The LSU exposes the
    // resolved physical address and consumes the resolved attributes.
    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(PhysicalBits.W))
    val resolvedAttributes = Input(new MemoryAttributes)

    val memoryRequest = Decoupled(new AetherMemRequest(PhysicalBits, Xlen, txnIdBits))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(Xlen, txnIdBits)))

    val busy = Output(Bool())
  })

  def sameRobToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  val active = Reg(new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits))
  val busy = RegInit(false.B)
  val physicalIssued = RegInit(false.B)
  val activeTxn = RegInit(0.U(txnIdBits.W))
  val nextTxn = RegInit(0.U(txnIdBits.W))

  io.request.ready := !busy
  io.busy := busy

  when(io.request.fire) {
    active := io.request.bits
    busy := true.B
    physicalIssued := false.B
  }

  val effectiveAddress = active.base + active.offset
  val isLoad = active.kind === MemoryOperationKind.Load
  val isStore = active.kind === MemoryOperationKind.Store
  val supportedKind = isLoad || isStore
  val sizeSupported = if (Xlen == 32) active.size =/= MemSize.DWord else true.B
  val unsupported = busy && (!supportedKind || !sizeSupported || active.atomicOp =/= AtomicOp.None)

  val accessBytes = WireDefault(BusBytes.U(4.W))
  val alignmentMask = WireDefault((BusBytes - 1).U(Xlen.W))
  val storeMask = WireDefault(((BigInt(1) << BusBytes) - 1).U(BusBytes.W))
  switch(active.size) {
    is(MemSize.Byte) {
      accessBytes := 1.U
      alignmentMask := 0.U
      storeMask := 1.U
    }
    is(MemSize.Half) {
      accessBytes := 2.U
      alignmentMask := 1.U
      storeMask := 3.U
    }
    is(MemSize.Word) {
      accessBytes := 4.U
      alignmentMask := 3.U
      storeMask := ((BigInt(1) << math.min(4, BusBytes)) - 1).U
    }
    is(MemSize.DWord) {
      accessBytes := 8.U
      alignmentMask := 7.U
      storeMask := ((BigInt(1) << BusBytes) - 1).U
    }
  }

  val misaligned = busy && supportedKind && sizeSupported &&
    ((effectiveAddress & alignmentMask) =/= 0.U)
  val localFault = unsupported || misaligned

  val adapter = Module(new DataPathAdapter(geometry, PhysicalBits, tlbEntries))
  adapter.io.requestValid := busy && !localFault
  adapter.io.flush := io.translationFlush
  adapter.io.virtualAddress := effectiveAddress
  adapter.io.privilege := io.effectivePrivilege
  adapter.io.translateWrite := isStore
  adapter.io.write := isStore
  adapter.io.wdata := active.storeData
  adapter.io.wmask := storeMask
  adapter.io.size := active.size
  adapter.io.satpTranslationEnabled := io.satpTranslationEnabled
  adapter.io.satpRootPpn := io.satpRootPpn
  adapter.io.sum := io.supervisorSum
  adapter.io.mxr := io.supervisorMxr

  io.pteValid := adapter.io.pteValid
  io.pteAddress := adapter.io.pteAddress
  adapter.io.pteReady := io.pteReady
  adapter.io.pteData := io.pteData
  adapter.io.pteFault := io.pteFault

  val pmp = Module(new PmpChecker(Xlen, PmpConstants.MaxEntries, PhysicalBits))
  pmp.io.privilege := io.effectivePrivilege
  pmp.io.address := adapter.io.dataAddress
  pmp.io.bytes := accessBytes
  pmp.io.write := isStore
  pmp.io.execute := false.B
  pmp.io.config := io.pmpConfig
  pmp.io.pmpAddress := io.pmpAddress

  val pmpDenied = adapter.io.dataValid && io.pmpEnabled && !pmp.io.allow
  val permitMatches = io.storePermit.valid && sameRobToken(io.storePermit.bits, active.robToken)
  val storeMayExternalize = !isStore || permitMatches

  io.resolvedPhysicalValid := adapter.io.dataValid
  io.resolvedPhysicalAddress := adapter.io.dataAddress

  io.memoryRequest.valid := adapter.io.dataValid && !pmpDenied && !physicalIssued && storeMayExternalize
  io.memoryRequest.bits.txnId := nextTxn
  io.memoryRequest.bits.op := Mux(isStore, AetherMemOp.Write, AetherMemOp.Read)
  io.memoryRequest.bits.paddr := adapter.io.dataAddress
  io.memoryRequest.bits.size := active.size
  io.memoryRequest.bits.wdata := active.storeData
  io.memoryRequest.bits.wmask := Mux(isStore, storeMask, 0.U)
  io.memoryRequest.bits.atomicOp := AtomicOp.None
  io.memoryRequest.bits.attributes := io.resolvedAttributes

  when(io.memoryRequest.fire) {
    physicalIssued := true.B
    activeTxn := nextTxn
    nextTxn := nextTxn + 1.U
  }

  // A one-outstanding LSU may discard a stale response with a different
  // transaction ID, but only the exact active ID can complete the adapter.
  io.memoryResponse.ready := physicalIssued
  val matchingResponse = io.memoryResponse.fire && io.memoryResponse.bits.txnId === activeTxn
  adapter.io.dataReady := pmpDenied || matchingResponse
  adapter.io.dataRdata := io.memoryResponse.bits.rdata
  // Multi-beat physical responses are outside this first blocking slice. Fail
  // closed instead of silently treating a non-final beat as an architectural
  // completion.
  adapter.io.dataFault := pmpDenied ||
    (matchingResponse && (io.memoryResponse.bits.fault || !io.memoryResponse.bits.last))

  def extendedLoad(data: UInt): UInt = {
    val result = WireDefault(data)
    switch(active.size) {
      is(MemSize.Byte) {
        val byte = data(7, 0)
        result := Mux(active.unsigned, byte.pad(Xlen), Cat(Fill(Xlen - 8, byte(7)), byte))
      }
      is(MemSize.Half) {
        val half = data(15, 0)
        result := Mux(active.unsigned, half.pad(Xlen), Cat(Fill(Xlen - 16, half(15)), half))
      }
      is(MemSize.Word) {
        val word = data(31, 0)
        if (Xlen == 32) result := word
        else result := Mux(active.unsigned, word.pad(Xlen), Cat(Fill(Xlen - 32, word(31)), word))
      }
      is(MemSize.DWord) {
        result := data
      }
    }
    result
  }

  io.completion.valid := false.B
  io.completion.bits := 0.U.asTypeOf(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
  io.completion.bits.robToken := active.robToken
  io.completion.bits.producerTag := active.producerTag
  io.completion.bits.valueRef := active.valueRef

  val adapterDone = busy && !localFault && adapter.io.requestComplete
  when(busy && localFault) {
    io.completion.valid := true.B
    io.completion.bits.exception.valid := true.B
    when(unsupported) {
      io.completion.bits.exception.cause := MachineExceptionCode.IllegalInstruction.U
      io.completion.bits.exception.value := active.rawInst.pad(Xlen)
    }.otherwise {
      io.completion.bits.exception.cause := Mux(
        isLoad,
        MachineExceptionCode.LoadAddressMisaligned.U,
        MachineExceptionCode.StoreAddressMisaligned.U
      )
      io.completion.bits.exception.value := effectiveAddress
    }
  }.elsewhen(adapterDone) {
    val fault = adapter.io.pageFault || adapter.io.accessFault
    io.completion.valid := true.B
    io.completion.bits.hasValue := isLoad && !fault
    io.completion.bits.value := extendedLoad(adapter.io.readData)
    io.completion.bits.exception.valid := fault
    io.completion.bits.exception.cause := Mux(
      adapter.io.pageFault,
      Mux(isLoad, MachineExceptionCode.LoadPageFault.U, MachineExceptionCode.StorePageFault.U),
      Mux(isLoad, MachineExceptionCode.LoadAccessFault.U, MachineExceptionCode.StoreAccessFault.U)
    )
    io.completion.bits.exception.value := effectiveAddress
  }

  io.memoryTrace.valid := matchingResponse && io.memoryResponse.bits.last && !io.memoryResponse.bits.fault
  io.memoryTrace.bits := 0.U.asTypeOf(new TinyMemoryTrace(Xlen, PhysicalBits, IdentityBits, GenerationBits))
  io.memoryTrace.bits.robToken := active.robToken
  io.memoryTrace.bits.paddr := adapter.io.dataAddress
  io.memoryTrace.bits.write := isStore
  io.memoryTrace.bits.wdata := Mux(isStore, active.storeData, 0.U)
  io.memoryTrace.bits.wmask := Mux(isStore, storeMask, 0.U)

  when(io.completion.valid) {
    busy := false.B
    physicalIssued := false.B
  }
}
