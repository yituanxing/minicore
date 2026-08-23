package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, CommitTrace, PrivilegeMode}
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.core.{MachineCsrBit, MachineCsrFile, PmpChecker, PmpConstants, PmpGeometry}
import aethercore.memory.{AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * F6 composition harness matured after F7 with conservative selective compute.
  *
  * Memory remains one-outstanding/head-issued, System remains head-owned, and
  * Branch remains head-issued so the existing precise recovery contract stays
  * unchanged. Only side-effect-free Integer/MulDiv work may issue oldest-ready
  * from the read-only scheduling window. Commit, CSR/trap state, store
  * visibility, translation and PMP ownership remain unchanged.
  */
class TinyMemoryBackend(
    val config: CoreConfig,
    val geometry: PageTableGeometry,
    val tlbEntries: Int = 8,
    val txnIdBits: Int = 2,
    val enableAsyncInterrupts: Boolean = false,
    val withMachineExternalInterrupt: Boolean = false,
    val withSupervisorExternalInterrupt: Boolean = false,
    val allowAtomics: Boolean = false
) extends Module {
  private val isa = config.isa
  private val xlen = isa.xlen
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val Entries = TinyRobGeometry.Entries
  private val PhysicalBits = config.platform.paddrBits
  private val BusBits = config.platform.busDataBits
  private val PmpAddressBits = PmpGeometry(xlen, PhysicalBits).encodedAddressBits

  require(geometry.xlen == xlen, s"F6 geometry XLEN=${geometry.xlen} does not match core XLEN=$xlen")
  require(
    isa.pageTableGeometries.contains(geometry),
    s"F6 integration geometry ${geometry.name} must belong to profile ${config.name}"
  )
  require(
    BusBits == xlen,
    s"first F6 integration requires busDataBits == XLEN, got bus=$BusBits xlen=$xlen"
  )
  require(!withMachineExternalInterrupt || enableAsyncInterrupts,
    "machine external interrupt wiring requires the F7 asynchronous owner")
  require(!withSupervisorExternalInterrupt || enableAsyncInterrupts,
    "supervisor external interrupt wiring requires the F7 asynchronous owner")
  require(!withSupervisorExternalInterrupt || isa.hasS,
    "supervisor external interrupt wiring requires S-mode")
  require(!allowAtomics || isa.hasA,
    "A-extension LSU opt-in requires an ISA profile containing A")

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val commit = Output(new CommitTrace(xlen = xlen, paddrBits = PhysicalBits, busDataBits = BusBits))
    val branchRedirect = Valid(new RecoveryRedirect(xlen))
    val privilegedRedirect = Valid(new PrivilegedRedirect(xlen))
    val currentPrivilege = Output(UInt(2.W))
    // Read-only architectural context exported for the F7 instruction-side
    // translation/PMP owner. MachineCsrFile remains the single mutable owner.
    val frontendSatpTranslationEnabled = Output(Bool())
    val frontendSatpRootPpn = Output(UInt(geometry.ppnBits.W))
    val frontendSupervisorMxr = Output(Bool())
    val frontendPmpConfig = Output(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val frontendPmpAddress = Output(Vec(PmpConstants.MaxEntries, UInt(PmpAddressBits.W)))
    val time = if (isa.hasTimeCounter) Some(Input(UInt(64.W))) else None
    val occupancy = Output(UInt(log2Ceil(Entries + 1).W))

    // F7 async contract. A qualified interrupt closes dispatch immediately,
    // but CSR trap state changes only after the ROB drains to a clean boundary.
    val async = if (enableAsyncInterrupts) Some(new Bundle {
      val boundaryPc = Input(UInt(xlen.W))
      val timerPending = Input(Bool())
      val machineExternalPending =
        if (withMachineExternalInterrupt) Some(Input(Bool())) else None
      val supervisorExternalPending =
        if (withSupervisorExternalInterrupt) Some(Input(Bool())) else None
      val interruptHold = Output(Bool())
      val wakeRequest = Output(Bool())
      val wfiWaiting = Output(Bool())
      val interruptRedirect = Output(Valid(UInt(xlen.W)))
    }) else None

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

    // Retirement-time system consequence for a future frontend/fetch TLB owner.
    // The current F6 backend also consumes this pulse for its data translation TLB.
    val translationFence = Output(Bool())
  })

  private def sameRobToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  val dependencyBackend = Module(new TinyDependencyBackend(xlen))
  val branchIssue = Module(new TinyOldestIssue(xlen))
  val selectiveIssue = Module(new TinySelectiveComputeIssue(xlen))
  val execution = Module(new TinySelectiveExecutionCluster(xlen, isa.hasC))
  val system = Module(new TinySystemCompletion(
    isa,
    allowSfenceVma = true,
    allowWfi = enableAsyncInterrupts
  ))
  val csrFile = Module(new MachineCsrFile(
    isa,
    PhysicalBits,
    withMachineExternalInterrupt = enableAsyncInterrupts && withMachineExternalInterrupt,
    withSupervisorExternalInterrupt = enableAsyncInterrupts && withSupervisorExternalInterrupt
  ))
  val lsu = Module(new TinyBlockingLsu(
    geometry,
    paddrBits = PhysicalBits,
    tlbEntries = tlbEntries,
    txnIdBits = txnIdBits,
    allowAtomics = allowAtomics
  ))
  val ptwPmp = Module(new PmpChecker(xlen, PmpConstants.MaxEntries, PhysicalBits))

  private val retiring = dependencyBackend.io.retiring
  private val retiringSystem = retiring.valid &&
    retiring.bits.uop.executionClass === ExecutionClass.System
  private val trapAtRetire = retiring.valid && retiring.bits.exception.valid
  private val returnAtRetire = retiringSystem &&
    !retiring.bits.exception.valid &&
    retiring.bits.privileged.trapReturn
  private val sfenceAtRetire = retiringSystem &&
    !retiring.bits.exception.valid &&
    retiring.bits.uop.decoded.system.kind === SystemOperationKind.SfenceVma
  private val wfiAtRetire = enableAsyncInterrupts.B && retiringSystem &&
    !retiring.bits.exception.valid &&
    retiring.bits.uop.decoded.system.kind === SystemOperationKind.Wfi
  private val privilegedBoundary = trapAtRetire || returnAtRetire

  private val rawMachineTimer =
    if (enableAsyncInterrupts) io.async.get.timerPending else false.B
  private val rawMachineExternal =
    if (enableAsyncInterrupts && withMachineExternalInterrupt)
      io.async.get.machineExternalPending.get
    else false.B
  private val rawSupervisorExternal =
    if (enableAsyncInterrupts && withSupervisorExternalInterrupt)
      io.async.get.supervisorExternalPending.get
    else false.B

  csrFile.io.timerInterrupt := rawMachineTimer
  if (enableAsyncInterrupts && withMachineExternalInterrupt) {
    csrFile.io.externalInterrupt.get := rawMachineExternal
  }
  if (enableAsyncInterrupts && withSupervisorExternalInterrupt) {
    csrFile.io.supervisorExternalInterruptPending.get := rawSupervisorExternal
  }

  private val rawSupervisorTimer =
    if (enableAsyncInterrupts && isa.hasSupervisorTimerInterrupt)
      csrFile.io.supervisorTimerPending.get
    else false.B
  private val machineTimerQualified =
    if (enableAsyncInterrupts) csrFile.io.machineTimerInterrupt else false.B
  private val machineExternalQualified =
    if (enableAsyncInterrupts && withMachineExternalInterrupt)
      csrFile.io.machineExternalInterrupt.get
    else false.B
  private val supervisorTimerQualified =
    if (enableAsyncInterrupts && isa.hasSupervisorTimerInterrupt)
      csrFile.io.supervisorTimerInterrupt.get
    else false.B
  private val supervisorExternalQualified =
    if (enableAsyncInterrupts && withSupervisorExternalInterrupt)
      csrFile.io.supervisorExternalInterrupt.get
    else false.B

  private val rawWakeRequest =
    rawMachineTimer || rawMachineExternal || rawSupervisorTimer || rawSupervisorExternal
  private val qualifiedInterrupt =
    machineExternalQualified || machineTimerQualified ||
      supervisorExternalQualified || supervisorTimerQualified
  private val interruptFlag = BigInt(1) << (xlen - 1)
  private val interruptCause = Mux(
    machineExternalQualified,
    (interruptFlag | BigInt(MachineCsrBit.MachineExternalInterrupt)).U(xlen.W),
    Mux(
      machineTimerQualified,
      (interruptFlag | BigInt(MachineCsrBit.MachineTimerInterrupt)).U(xlen.W),
      Mux(
        supervisorExternalQualified,
        (interruptFlag | BigInt(MachineCsrBit.SupervisorExternalInterrupt)).U(xlen.W),
        (interruptFlag | BigInt(MachineCsrBit.SupervisorTimerInterrupt)).U(xlen.W)
      )
    )
  )

  private val wfiWaiting = if (enableAsyncInterrupts) Some(RegInit(false.B)) else None
  if (enableAsyncInterrupts) {
    when(wfiAtRetire && !rawWakeRequest) {
      wfiWaiting.get := true.B
    }
    when(wfiWaiting.get && rawWakeRequest) {
      wfiWaiting.get := false.B
    }
  }

  // Delay asynchronous trap entry until every already-accepted instruction has
  // either retired or been recovered. This yields a clean architectural next-PC
  // boundary without deriving mepc/sepc from speculative frontend state.
  private val interruptTake = enableAsyncInterrupts.B && qualifiedInterrupt &&
    dependencyBackend.io.occupancy === 0.U && !privilegedBoundary
  if (enableAsyncInterrupts) {
    io.async.get.interruptHold := qualifiedInterrupt
    io.async.get.wakeRequest := rawWakeRequest
    io.async.get.wfiWaiting := wfiWaiting.get
    io.async.get.interruptRedirect.valid := interruptTake
    io.async.get.interruptRedirect.bits := csrFile.io.trapVector
    when(interruptTake) {
      wfiWaiting.get := false.B
    }
  }

  // P8.2 generalized recovery makes retirement intentionally depend on the
  // current accepted completion so recovery can atomically suppress an older
  // head retirement. Keep LSU context inputs off that combinational path: the
  // head cannot become a new memory operation until the next clock anyway.
  // CSR ordinary-write forwarding is captured here; trap/xRET transitions need
  // one conservative refresh cycle after their state update. SFENCE data-TLB
  // invalidation is likewise replayed in that protected cycle.
  private val lsuEffectivePrivilege =
    RegNext(csrFile.io.effectiveDataPrivilege, PrivilegeMode.Machine.U(2.W))
  private val lsuContextRefresh =
    RegNext(retiringSystem || privilegedBoundary || interruptTake, false.B)
  private val lsuTranslationFlush = RegNext(sfenceAtRetire, false.B)

  private val asyncDispatchBlock =
    enableAsyncInterrupts.B && (qualifiedInterrupt || wfiWaiting.map(identity).getOrElse(false.B))
  dependencyBackend.io.dispatch.valid := io.dispatch.valid && !privilegedBoundary && !asyncDispatchBlock
  dependencyBackend.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := dependencyBackend.io.dispatch.ready && !privilegedBoundary && !asyncDispatchBlock
  io.allocated := dependencyBackend.io.allocated
  io.occupancy := dependencyBackend.io.occupancy

  // Branch keeps the frozen head-only execution/recovery policy. Selective
  // compute sees the whole read-only scheduling window but cannot select Branch,
  // Memory or System by construction.
  branchIssue.io.head := dependencyBackend.io.head
  branchIssue.io.head.valid := dependencyBackend.io.head.valid &&
    dependencyBackend.io.head.bits.executionClass === ExecutionClass.Branch &&
    !dependencyBackend.io.head.bits.decoded.exception.valid
  branchIssue.io.headDependenciesValid := dependencyBackend.io.headDependenciesValid
  branchIssue.io.headRs1 := dependencyBackend.io.headRs1
  branchIssue.io.headRs2 := dependencyBackend.io.headRs2
  branchIssue.io.headOperandsReady := dependencyBackend.io.headOperandsReady

  selectiveIssue.io.window := dependencyBackend.io.schedulingWindow
  selectiveIssue.io.allocated := dependencyBackend.io.allocated
  selectiveIssue.io.availability := execution.io.computeAvailability
  // A just-validated recovery must not launch a younger lifetime that is being
  // killed this cycle. A not-yet-issued head memory request also has launch
  // priority so the first selective slice remains globally single-issue.
  selectiveIssue.io.block :=
    dependencyBackend.io.acceptedRecovery.valid ||
      dependencyBackend.io.acceptedPrivilegedRecovery.valid ||
      lsu.io.request.valid

  // Branch wins the one execution launch slot when the exact head is ready;
  // otherwise oldest-ready safe compute may use it. Once a branch has launched,
  // its once-only latch lets younger compute overlap while the branch resolves.
  private val executionRequests = Module(new Arbiter(
    new ExecutionRequest(xlen, IdentityBits, GenerationBits),
    2
  ))
  executionRequests.io.in(0) <> branchIssue.io.request
  executionRequests.io.in(1) <> selectiveIssue.io.request
  execution.io.request <> executionRequests.io.out

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
  io.frontendSatpTranslationEnabled := csrFile.io.satpTranslationEnabled
  io.frontendSatpRootPpn := csrFile.io.satpRootPpn
  io.frontendSupervisorMxr := csrFile.io.supervisorMxr
  io.frontendPmpConfig := csrFile.io.pmpConfig
  io.frontendPmpAddress := csrFile.io.pmpAddress

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
    !memoryAlreadyIssued &&
    !lsuContextRefresh &&
    !lsuTranslationFlush
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

  // The once-only latch belongs to the current head lifetime, not to an
  // unbounded history of numeric tokens. RobToken generation may legitimately
  // wrap after the head has moved through other lifetimes; remembering a token
  // across that change would eventually suppress a new instruction that reuses
  // the same index/generation pair.
  when(memoryIssuedValid &&
       (!head.valid || !sameRobToken(memoryIssuedToken, head.bits.robToken))) {
    memoryIssuedValid := false.B
  }
  // A new request on the replacement head wins over the stale-latch clear in
  // the same cycle and becomes the new once-only owner.
  when(lsu.io.request.fire) {
    memoryIssuedValid := true.B
    memoryIssuedToken := head.bits.robToken
  }

  // The first selective slice remains one-launch-per-cycle across Branch,
  // Memory and compute. Memory gets priority through selectiveIssue.block;
  // branch-vs-compute priority is owned by executionRequests above.
  assert(PopCount(Cat(
    branchIssue.io.request.fire,
    selectiveIssue.io.request.fire,
    lsu.io.request.fire
  )) <= 1.U, "A8 selective backend must remain single-issue per cycle")

  // Ordinary stores and atomic writers may become externally visible only while
  // the exact lifetime is the ROB head. LR is read-only and does not need this
  // permission. A locally failing SC also never externalizes.
  private val headAtomicWriter = allowAtomics.B &&
    head.bits.decoded.memory.kind === MemoryOperationKind.Atomic &&
    head.bits.decoded.memory.atomicOp =/= AtomicOp.None &&
    head.bits.decoded.memory.atomicOp =/= AtomicOp.Lr
  lsu.io.storePermit.valid := headIsMemory && (
    head.bits.decoded.memory.kind === MemoryOperationKind.Store || headAtomicWriter
  )
  lsu.io.storePermit.bits := head.bits.robToken

  lsu.io.effectivePrivilege := lsuEffectivePrivilege
  lsu.io.satpTranslationEnabled := csrFile.io.satpTranslationEnabled
  lsu.io.satpRootPpn := csrFile.io.satpRootPpn
  lsu.io.supervisorSum := csrFile.io.supervisorSum
  lsu.io.supervisorMxr := csrFile.io.supervisorMxr
  // Preserve the architectural fence pulse at retirement for frontend owners,
  // but replay the data-side flush through a register so generalized recovery's
  // completion-dependent retirement cannot feed back into the LSU completion.
  lsu.io.translationFlush := lsuTranslationFlush
  io.translationFence := sfenceAtRetire
  lsu.io.pmpEnabled := isa.hasPmp.B
  lsu.io.pmpConfig := csrFile.io.pmpConfig
  lsu.io.pmpAddress := csrFile.io.pmpAddress
  if (allowAtomics) {
    // Privileged architectural boundaries conservatively invalidate an LR
    // reservation. Ordinary stores/SC/AMO clear it inside the LSU itself.
    lsu.io.reservationClear.get :=
      trapAtRetire || returnAtRetire || wfiAtRetire || interruptTake
  }

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

  // A8 completion transport permits independent execution/LSU/System progress
  // while preserving one accepted ROB completion per cycle.
  val completions = Module(new TinyCompletionArbiter(xlen, 3))
  completions.io.in(0) <> system.io.completion
  completions.io.in(1) <> lsu.io.completion
  completions.io.in(2) <> execution.io.response
  dependencyBackend.io.completion.valid := completions.io.out.valid
  dependencyBackend.io.completion.bits := completions.io.out.bits
  completions.io.out.ready := true.B

  io.branchRedirect.valid := dependencyBackend.io.acceptedRecovery.valid
  io.branchRedirect.bits := 0.U.asTypeOf(new RecoveryRedirect(xlen))
  io.branchRedirect.bits.robToken := dependencyBackend.io.acceptedRecovery.bits.robToken
  io.branchRedirect.bits.target := dependencyBackend.io.acceptedRecovery.bits.branchTarget

  csrFile.io.writeEnable := retiringSystem &&
    !retiring.bits.exception.valid &&
    retiring.bits.privileged.csrWriteValid
  csrFile.io.writeAddr := retiring.bits.privileged.csrAddress
  csrFile.io.writeData := retiring.bits.privileged.csrWriteData
  if (isa.hasTimeCounter) {
    csrFile.io.time.get := io.time.get
  }
  csrFile.io.trapEnter := trapAtRetire || interruptTake
  csrFile.io.trapPc := Mux(
    interruptTake,
    if (enableAsyncInterrupts) io.async.get.boundaryPc else 0.U,
    retiring.bits.uop.decoded.pc
  )
  csrFile.io.trapCause := Mux(interruptTake, interruptCause, retiring.bits.exception.cause)
  csrFile.io.trapValue := Mux(interruptTake, 0.U, retiring.bits.exception.value)
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
  // CommitTrace.valid remains an instruction-retirement pulse. interrupt is an
  // independent architectural boundary event and may therefore be true while
  // valid is false (notably after xRET or while waking from WFI).
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
  io.commit.interrupt := interruptTake
  io.commit.interruptCause := Mux(interruptTake, interruptCause, 0.U)
  io.commit.interruptPc := Mux(
    interruptTake,
    if (enableAsyncInterrupts) io.async.get.boundaryPc else 0.U,
    0.U
  )

  when(retiringTraceMatches) {
    pendingTraceValid(retiring.bits.uop.robToken.index) := false.B
  }

  // Recovery kills every younger lifetime. Memory remains strict head-only, so
  // no younger memory request can have externalized; speculative compute is
  // side-effect free and is rejected later if an already-issued killed response
  // returns with its stale lifetime identity.
  when(dependencyBackend.io.acceptedRecovery.valid ||
       dependencyBackend.io.acceptedPrivilegedRecovery.valid) {
    for (index <- 0 until Entries) {
      pendingTraceValid(index) := false.B
    }
  }

  if (enableAsyncInterrupts) {
    assert(!(interruptTake && dependencyBackend.io.commit.valid),
      "F7 async interrupt must enter only at an empty clean ROB boundary")
    assert(!(io.async.get.interruptRedirect.valid && io.privilegedRedirect.valid),
      "async and ROB-owned privileged redirects must be mutually exclusive")
  }
}
