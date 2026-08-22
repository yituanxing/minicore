package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{CommitTrace, PrivilegeMode}
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.core.{MachineCsrFile, PmpChecker, PmpConstants}
import aethercore.memory.{AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * F6 composition harness: F5 precise retirement plus the correctness-first
  * one-outstanding LSU.
  *
  * This is deliberately a new phase harness rather than a mutation of the
  * frozen F5 TinyPrivilegedBackend. The same ROB/dependency/execute/CSR leaf
  * modules are composed here with one additional memory completion source.
  * Ordering/lifetime remains owned by TinyRob; the LSU owns translation/PMP and
  * one physical transaction; architectural memory trace becomes visible only
  * when a generation-matching ROB head retires.
  */
class TinyMemoryBackend(
    val config: CoreConfig,
    val geometry: PageTableGeometry,
    val tlbEntries: Int = 8,
    val txnIdBits: Int = 2
) extends Module {
  private val isa = config.isa
  private val xlen = isa.xlen
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val Entries = TinyRobGeometry.Entries
  private val PhysicalBits = config.platform.paddrBits
  private val BusBits = config.platform.busDataBits

  require(geometry.xlen == xlen, s"F6 geometry XLEN=${geometry.xlen} does not match core XLEN=$xlen")
  require(
    isa.pageTableGeometries.contains(geometry),
    s"F6 integration geometry ${geometry.name} must belong to profile ${config.name}"
  )
  require(
    BusBits == xlen,
    s"first F6 integration requires busDataBits == XLEN, got bus=$BusBits xlen=$xlen"
  )

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val commit = Output(new CommitTrace(xlen = xlen, paddrBits = PhysicalBits, busDataBits = BusBits))
    val branchRedirect = Valid(new RecoveryRedirect(xlen))
    val privilegedRedirect = Valid(new PrivilegedRedirect(xlen))
    val currentPrivilege = Output(UInt(2.W))
    val time = if (isa.hasTimeCounter) Some(Input(UInt(64.W))) else None
    val occupancy = Output(UInt(log2Ceil(Entries + 1).W))

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PhysicalBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(PhysicalBits.W))
    val resolvedAttributes = Input(new MemoryAttributes)

    val memoryRequest = Decoupled(new AetherMemRequest(PhysicalBits, xlen, txnIdBits))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(xlen, txnIdBits)))
    val lsuBusy = Output(Bool())
  })

  private def sameRobToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  val dependencyBackend = Module(new TinyDependencyBackend(xlen))
  val issue = Module(new TinyOldestIssue(xlen))
  val execution = Module(new TinyExecutionCluster(xlen, isa.hasC))
  val system = Module(new TinySystemCompletion(isa))
  val csrFile = Module(new MachineCsrFile(
    isa,
    PhysicalBits,
    withMachineExternalInterrupt = false,
    withSupervisorExternalInterrupt = false
  ))
  val lsu = Module(new TinyBlockingLsu(
    geometry,
    paddrBits = PhysicalBits,
    tlbEntries = tlbEntries,
    txnIdBits = txnIdBits
  ))
  val ptwPmp = Module(new PmpChecker(xlen, PmpConstants.MaxEntries, PhysicalBits))

  private val retiring = dependencyBackend.io.retiring
  private val retiringSystem = retiring.valid &&
    retiring.bits.uop.executionClass === ExecutionClass.System
  private val trapAtRetire = retiring.valid && retiring.bits.exception.valid
  private val returnAtRetire = retiringSystem &&
    !retiring.bits.exception.valid &&
    retiring.bits.privileged.trapReturn
  private val privilegedBoundary = trapAtRetire || returnAtRetire

  dependencyBackend.io.dispatch.valid := io.dispatch.valid && !privilegedBoundary
  dependencyBackend.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := dependencyBackend.io.dispatch.ready && !privilegedBoundary
  io.allocated := dependencyBackend.io.allocated
  io.occupancy := dependencyBackend.io.occupancy

  // F3 normal execution keeps its frozen supported-class policy. Memory gets a
  // separate oldest-only issue path below; system/predecoded exceptions remain
  // owned by TinySystemCompletion.
  issue.io.head := dependencyBackend.io.head
  issue.io.head.valid := dependencyBackend.io.head.valid &&
    dependencyBackend.io.head.bits.executionClass =/= ExecutionClass.System &&
    dependencyBackend.io.head.bits.executionClass =/= ExecutionClass.Memory &&
    !dependencyBackend.io.head.bits.decoded.exception.valid
  issue.io.headDependenciesValid := dependencyBackend.io.headDependenciesValid
  issue.io.headRs1 := dependencyBackend.io.headRs1
  issue.io.headRs2 := dependencyBackend.io.headRs2
  issue.io.headOperandsReady := dependencyBackend.io.headOperandsReady
  execution.io.request <> issue.io.request

  system.io.head := dependencyBackend.io.head
  system.io.headDependenciesValid := dependencyBackend.io.headDependenciesValid
  system.io.headRs1 := dependencyBackend.io.headRs1
  system.io.headOperandsReady := dependencyBackend.io.headOperandsReady

  csrFile.io.readAddr := system.io.csrReadAddr
  system.io.csrReadData := csrFile.io.readData
  system.io.csrReadImplemented := csrFile.io.readImplemented
  system.io.csrReadWritable := csrFile.io.readWritable
  system.io.currentPrivilege := csrFile.io.currentPrivilege
  io.currentPrivilege := csrFile.io.currentPrivilege

  // One-shot oldest-only memory issue. The architectural rs1/rs2 dependency
  // values are materialized only after F2 says the current ROB head is ready.
  private val memoryIssuedValid = RegInit(false.B)
  private val memoryIssuedToken = Reg(new RobToken(IdentityBits, GenerationBits))
  private val head = dependencyBackend.io.head
  private val headIsMemory = head.valid &&
    head.bits.executionClass === ExecutionClass.Memory &&
    !head.bits.decoded.exception.valid
  private val memoryAlreadyIssued = memoryIssuedValid &&
    sameRobToken(memoryIssuedToken, head.bits.robToken)

  lsu.io.request.valid := headIsMemory &&
    dependencyBackend.io.headDependenciesValid &&
    dependencyBackend.io.headOperandsReady &&
    !memoryAlreadyIssued
  lsu.io.request.bits := 0.U.asTypeOf(new TinyMemoryRequest(xlen, IdentityBits, GenerationBits))
  lsu.io.request.bits.robToken := head.bits.robToken
  lsu.io.request.bits.producerTag := head.bits.producerTag
  lsu.io.request.bits.valueRef := head.bits.valueRef
  lsu.io.request.bits.kind := head.bits.decoded.memory.kind
  lsu.io.request.bits.size := head.bits.decoded.memory.size
  lsu.io.request.bits.unsigned := head.bits.decoded.memory.unsigned
  lsu.io.request.bits.atomicOp := head.bits.decoded.memory.atomicOp
  lsu.io.request.bits.base := dependencyBackend.io.headRs1.value
  lsu.io.request.bits.offset := head.bits.decoded.immediate
  lsu.io.request.bits.storeData := dependencyBackend.io.headRs2.value
  lsu.io.request.bits.rawInst := head.bits.decoded.rawInst

  when(lsu.io.request.fire) {
    memoryIssuedValid := true.B
    memoryIssuedToken := head.bits.robToken
  }
  when(!head.valid) {
    memoryIssuedValid := false.B
  }

  // A store may become externally visible only while the exact store lifetime
  // is the ROB head. Translation/PMP still have to succeed inside the LSU.
  lsu.io.storePermit.valid := headIsMemory &&
    head.bits.decoded.memory.kind === MemoryOperationKind.Store
  lsu.io.storePermit.bits := head.bits.robToken

  lsu.io.effectivePrivilege := csrFile.io.effectiveDataPrivilege
  lsu.io.satpTranslationEnabled := csrFile.io.satpTranslationEnabled
  lsu.io.satpRootPpn := csrFile.io.satpRootPpn
  lsu.io.supervisorSum := csrFile.io.supervisorSum
  lsu.io.supervisorMxr := csrFile.io.supervisorMxr
  // SFENCE.VMA is still deliberately outside F6's first integrated slice.
  lsu.io.translationFlush := false.B
  lsu.io.pmpEnabled := isa.hasPmp.B
  lsu.io.pmpConfig := csrFile.io.pmpConfig
  lsu.io.pmpAddress := csrFile.io.pmpAddress

  // Page-table reads are implicit Supervisor-mode accesses and must themselves
  // pass PMP before leaving the core. This mirrors the qualified v1 composition:
  // a denied PTE fetch is consumed locally and reported to the walker as an
  // access fault; no external PTW request is emitted.
  ptwPmp.io.privilege := PrivilegeMode.Supervisor.U
  ptwPmp.io.address := lsu.io.pteAddress
  ptwPmp.io.bytes := geometry.pteBytes.U
  ptwPmp.io.write := false.B
  ptwPmp.io.execute := false.B
  ptwPmp.io.config := csrFile.io.pmpConfig
  ptwPmp.io.pmpAddress := csrFile.io.pmpAddress
  private val ptwPmpFault = lsu.io.pteValid && isa.hasPmp.B && !ptwPmp.io.allow

  io.pteValid := lsu.io.pteValid && !ptwPmpFault
  io.pteAddress := lsu.io.pteAddress
  lsu.io.pteReady := Mux(ptwPmpFault, true.B, io.pteReady)
  lsu.io.pteData := io.pteData
  lsu.io.pteFault := ptwPmpFault || (io.pteValid && io.pteFault)

  io.resolvedPhysicalValid := lsu.io.resolvedPhysicalValid
  io.resolvedPhysicalAddress := lsu.io.resolvedPhysicalAddress
  lsu.io.resolvedAttributes := io.resolvedAttributes

  io.memoryRequest.valid := lsu.io.memoryRequest.valid
  io.memoryRequest.bits := lsu.io.memoryRequest.bits
  lsu.io.memoryRequest.ready := io.memoryRequest.ready
  lsu.io.memoryResponse.valid := io.memoryResponse.valid
  lsu.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := lsu.io.memoryResponse.ready
  io.lsuBusy := lsu.io.busy

  // Oldest-only composition means these sources are mutually exclusive by
  // execution class/lifetime. Keep the assertion because a future scheduler
  // must preserve explicit completion arbitration rather than silently adding
  // a wide implicit CDB.
  private val completionCount = PopCount(Cat(
    system.io.completion.valid,
    lsu.io.completion.valid,
    execution.io.response.valid
  ))
  assert(completionCount <= 1.U,
    "F6 oldest-only backend produced more than one completion in one cycle")

  dependencyBackend.io.completion.valid :=
    system.io.completion.valid || lsu.io.completion.valid || execution.io.response.valid
  dependencyBackend.io.completion.bits := Mux(
    system.io.completion.valid,
    system.io.completion.bits,
    Mux(lsu.io.completion.valid, lsu.io.completion.bits, execution.io.response.bits)
  )
  execution.io.response.ready := !system.io.completion.valid && !lsu.io.completion.valid

  io.branchRedirect.valid := dependencyBackend.io.acceptedRecovery.valid
  io.branchRedirect.bits := 0.U.asTypeOf(new RecoveryRedirect(xlen))
  io.branchRedirect.bits.robToken := dependencyBackend.io.acceptedRecovery.bits.robToken
  io.branchRedirect.bits.target := dependencyBackend.io.acceptedRecovery.bits.branchTarget

  csrFile.io.writeEnable := retiringSystem &&
    !retiring.bits.exception.valid &&
    retiring.bits.privileged.csrWriteValid
  csrFile.io.writeAddr := retiring.bits.privileged.csrAddress
  csrFile.io.writeData := retiring.bits.privileged.csrWriteData
  csrFile.io.timerInterrupt := false.B
  if (isa.hasTimeCounter) {
    csrFile.io.time.get := io.time.get
  }
  csrFile.io.trapEnter := trapAtRetire
  csrFile.io.trapPc := retiring.bits.uop.decoded.pc
  csrFile.io.trapCause := retiring.bits.exception.cause
  csrFile.io.trapValue := retiring.bits.exception.value
  csrFile.io.trapReturn := returnAtRetire
  csrFile.io.trapReturnSupervisor :=
    returnAtRetire && retiring.bits.privileged.trapReturnSupervisor

  io.privilegedRedirect.valid := privilegedBoundary
  io.privilegedRedirect.bits := 0.U.asTypeOf(new PrivilegedRedirect(xlen))
  io.privilegedRedirect.bits.robToken := retiring.bits.uop.robToken
  io.privilegedRedirect.bits.target := Mux(trapAtRetire, csrFile.io.trapVector, csrFile.io.returnPc)
  io.privilegedRedirect.bits.kind := Mux(
    trapAtRetire,
    PrivilegedRedirectKind.Trap,
    PrivilegedRedirectKind.Return
  )

  // Physical traces are not architectural merely because the memory bus has
  // responded. Hold them under the ROB generation and reveal them only if that
  // exact lifetime reaches retirement without an exception.
  private val pendingTraceValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  private val pendingTrace = Reg(Vec(
    Entries,
    new TinyMemoryTrace(xlen, PhysicalBits, IdentityBits, GenerationBits)
  ))

  when(dependencyBackend.io.allocated.valid) {
    pendingTraceValid(dependencyBackend.io.allocated.bits.robToken.index) := false.B
  }
  when(lsu.io.memoryTrace.valid) {
    pendingTraceValid(lsu.io.memoryTrace.bits.robToken.index) := true.B
    pendingTrace(lsu.io.memoryTrace.bits.robToken.index) := lsu.io.memoryTrace.bits
  }

  private val retiringTrace = pendingTrace(retiring.bits.uop.robToken.index)
  private val retiringTraceMatches = retiring.valid &&
    pendingTraceValid(retiring.bits.uop.robToken.index) &&
    sameRobToken(retiringTrace.robToken, retiring.bits.uop.robToken)
  private val retiringMemory = retiring.valid &&
    retiring.bits.uop.executionClass === ExecutionClass.Memory
  private val committedMemory = retiringMemory &&
    !retiring.bits.exception.valid &&
    retiringTraceMatches

  // Start from the already-qualified F5 commit semantics and only replace the
  // memory observation fields with the generation-tagged F6 retirement trace.
  private val baseCommit = dependencyBackend.io.commit
  io.commit := 0.U.asTypeOf(new CommitTrace(
    xlen = xlen,
    paddrBits = PhysicalBits,
    busDataBits = BusBits
  ))
  io.commit.valid := baseCommit.valid
  io.commit.pc := baseCommit.pc
  io.commit.inst := baseCommit.inst
  io.commit.rawInst := baseCommit.rawInst
  io.commit.instBytes := baseCommit.instBytes
  io.commit.rd := baseCommit.rd
  io.commit.rdWrite := baseCommit.rdWrite
  io.commit.rdData := baseCommit.rdData
  io.commit.memValid := committedMemory
  io.commit.memWrite := committedMemory && retiringTrace.write
  io.commit.memAddr := retiringTrace.paddr
  io.commit.memWdata := retiringTrace.wdata
  io.commit.memWmask := retiringTrace.wmask
  io.commit.exception := baseCommit.exception
  io.commit.exceptionCause := baseCommit.exceptionCause
  io.commit.exceptionValue := baseCommit.exceptionValue
  io.commit.interrupt := baseCommit.interrupt
  io.commit.interruptCause := baseCommit.interruptCause
  io.commit.interruptPc := baseCommit.interruptPc

  when(retiringTraceMatches) {
    pendingTraceValid(retiring.bits.uop.robToken.index) := false.B
  }

  // Any recovery kills every younger lifetime. No younger memory uOp can have
  // issued in the current oldest-only policy, but clearing the side table here
  // preserves the ownership invariant when issue policy evolves later.
  when(dependencyBackend.io.acceptedRecovery.valid ||
       dependencyBackend.io.acceptedPrivilegedRecovery.valid) {
    for (index <- 0 until Entries) {
      pendingTraceValid(index) := false.B
    }
  }
}
