package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, PrivilegeMode}
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.core.PmpConstants
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}

/**
  * P8 bounded non-blocking-memory experiment.
  *
  * Ordinary Loads move to TinyDualReplaySafeLoadUnit. Store/SC/AMO remain on
  * the qualified parent TinyBlockingLsu and remain exact-head-only. The design
  * therefore adds only two speculative/read lifetimes; it does not introduce a
  * general LSQ, store speculation or memory-dependence prediction.
  */
class TinyLoadQueueMemoryBackend(
    config: CoreConfig,
    geometry: PageTableGeometry,
    tlbEntries: Int = 8,
    txnIdBits: Int = 2,
    enableAsyncInterrupts: Boolean = false,
    withMachineExternalInterrupt: Boolean = false,
    withSupervisorExternalInterrupt: Boolean = false,
    allowAtomics: Boolean = false
) extends TinyMemoryBackend(
      config,
      geometry,
      tlbEntries = tlbEntries,
      txnIdBits = txnIdBits,
      enableAsyncInterrupts = enableAsyncInterrupts,
      withMachineExternalInterrupt = withMachineExternalInterrupt,
      withSupervisorExternalInterrupt = withSupervisorExternalInterrupt,
      allowAtomics = allowAtomics
    ) {
  private val isaLocal = config.isa
  private val xlenLocal = isaLocal.xlen
  private val PhysicalBitsLocal = config.platform.paddrBits
  private val IdentityBitsLocal = TinyRobGeometry.IndexBits
  private val GenerationBitsLocal = TinyRobGeometry.GenerationBits
  private val EntriesLocal = TinyRobGeometry.Entries

  require(txnIdBits >= 2,
    "two-slot Load queue reserves external txnId 0/1 for Loads and 2 for exact-head memory")

  private def sameToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  private val head = dependencyBackend.io.head
  private val headOrdinaryLoad = head.valid &&
    head.bits.executionClass === ExecutionClass.Memory &&
    head.bits.decoded.memory.kind === MemoryOperationKind.Load &&
    !head.bits.decoded.exception.valid

  // --------------------------------------------------------------------------
  // Exact-head Store/Atomic owner remains the original LSU.
  // --------------------------------------------------------------------------
  private val parentIssuedValid = RegInit(false.B)
  private val parentIssuedToken = Reg(new RobToken(IdentityBitsLocal, GenerationBitsLocal))
  private val headParentMemory = head.valid &&
    head.bits.executionClass === ExecutionClass.Memory &&
    head.bits.decoded.memory.kind =/= MemoryOperationKind.Load &&
    !head.bits.decoded.exception.valid
  private val parentAlreadyIssued = parentIssuedValid &&
    sameToken(parentIssuedToken, head.bits.robToken)

  // Last-connect replaces only the parent's request.valid policy. Its request
  // bits remain the already-qualified exact-head materialization.
  lsu.io.request.valid := headParentMemory &&
    dependencyBackend.io.headDependenciesValid &&
    dependencyBackend.io.headOperandsReady &&
    !parentAlreadyIssued

  when(parentIssuedValid &&
       (!head.valid || !sameToken(parentIssuedToken, head.bits.robToken))) {
    parentIssuedValid := false.B
  }
  when(lsu.io.request.fire) {
    parentIssuedValid := true.B
    parentIssuedToken := lsu.io.request.bits.robToken
  }

  // --------------------------------------------------------------------------
  // Two-slot ordinary Load owner + ROB4 selector.
  // --------------------------------------------------------------------------
  val loadUnit = Module(new TinyDualReplaySafeLoadUnit(
    geometry,
    paddrBits = PhysicalBitsLocal,
    tlbEntries = tlbEntries,
    txnIdBits = txnIdBits
  ))
  val loadIssue = Module(new TinyLoadQueueIssue(xlenLocal))

  loadIssue.io.window := dependencyBackend.io.schedulingWindow
  loadIssue.io.allocated := dependencyBackend.io.allocated
  loadIssue.io.available := !loadUnit.io.full
  loadIssue.io.bypassable := loadUnit.io.bypassable
  // Preserve one global launch per cycle. Branch and exact-head Store/Atomic
  // keep priority over speculative/ordinary Load selection.
  loadIssue.io.block := branchIssue.io.request.valid || lsu.io.request.valid
  loadUnit.io.request <> loadIssue.io.request

  // The parent's selective-compute scheduler must now also yield to a Load
  // launch. This is the same one-launch contract, merely with a fourth source.
  selectiveIssue.io.block :=
    branchIssue.io.request.valid ||
      dependencyBackend.io.acceptedRecovery.valid ||
      dependencyBackend.io.acceptedPrivilegedRecovery.valid ||
      lsu.io.request.valid ||
      loadIssue.io.request.valid

  assert(PopCount(Cat(
    branchIssue.io.request.fire,
    selectiveIssue.io.request.fire,
    lsu.io.request.fire,
    loadIssue.io.request.fire
  )) <= 1.U, "LoadQ2 backend must remain single-launch per cycle")

  loadUnit.io.head.valid := head.valid
  loadUnit.io.head.bits := head.bits.robToken
  loadUnit.io.effectivePrivilege := csrFile.io.effectiveDataPrivilege
  loadUnit.io.satpTranslationEnabled := csrFile.io.satpTranslationEnabled
  loadUnit.io.satpRootPpn := csrFile.io.satpRootPpn
  loadUnit.io.supervisorSum := csrFile.io.supervisorSum
  loadUnit.io.supervisorMxr := csrFile.io.supervisorMxr
  loadUnit.io.translationFlush := io.translationFence
  loadUnit.io.pmpEnabled := isaLocal.hasPmp.B
  loadUnit.io.pmpConfig := csrFile.io.pmpConfig
  loadUnit.io.pmpAddress := csrFile.io.pmpAddress

  // The exact-head parent and LoadQ2 already serialize through one physical
  // PMA/request lane below. Reuse LoadQ2's productized shared data-PMP checker
  // for the parent too; the parent's private checker is therefore disabled.
  // Translation, PTW PMP, storePermit and atomic/PMA checks remain in the parent.
  lsu.io.pmpEnabled := false.B

  // --------------------------------------------------------------------------
  // Shared PTW seam. Only an exact-head lifetime can request a walk.
  // Parent Store/Atomic gets deterministic priority, although architectural
  // head ownership normally makes the two candidates mutually exclusive.
  // --------------------------------------------------------------------------
  private val parentPte = lsu.io.pteValid
  private val loadPte = loadUnit.io.pteValid
  private val selectParentPte = parentPte
  private val selectedPteValid = parentPte || loadPte
  private val selectedPteAddress = Mux(selectParentPte, lsu.io.pteAddress, loadUnit.io.pteAddress)

  ptwPmp.io.privilege := PrivilegeMode.Supervisor.U
  ptwPmp.io.address := selectedPteAddress
  ptwPmp.io.bytes := geometry.pteBytes.U
  ptwPmp.io.write := false.B
  ptwPmp.io.execute := false.B
  ptwPmp.io.config := csrFile.io.pmpConfig
  ptwPmp.io.pmpAddress := csrFile.io.pmpAddress
  private val selectedPtePmpFault = selectedPteValid && isaLocal.hasPmp.B && !ptwPmp.io.allow

  io.pteValid := selectedPteValid && !selectedPtePmpFault
  io.pteAddress := selectedPteAddress

  lsu.io.pteReady := selectParentPte &&
    Mux(selectedPtePmpFault, true.B, io.pteReady)
  lsu.io.pteData := io.pteData
  lsu.io.pteFault := selectParentPte &&
    (selectedPtePmpFault || (io.pteValid && io.pteFault))

  loadUnit.io.pteReady := !selectParentPte && loadPte &&
    Mux(selectedPtePmpFault, true.B, io.pteReady)
  loadUnit.io.pteData := io.pteData
  loadUnit.io.pteFault := !selectParentPte && loadPte &&
    (selectedPtePmpFault || (io.pteValid && io.pteFault))

  // --------------------------------------------------------------------------
  // Shared PMA lookup. Exact-head Store/Atomic gets priority; otherwise the
  // dual Load unit time-multiplexes its two resolved addresses through the one
  // existing attribute seam.
  //
  // Deliberately do not derive PMA ownership from the current physical-request
  // handshake. physicalRequestIssued contains io.memoryRequest.fire, whose
  // ready path returns through this arbiter and creates a combinational cycle.
  // A live resolved parent Store/Atomic conservatively owns PMA until its
  // lifetime drains; younger Loads must not bypass that older memory anyway.
  // --------------------------------------------------------------------------
  private val parentNeedsPma = lsu.io.resolvedPhysicalValid
  private val loadNeedsPma = loadUnit.io.resolvedPhysicalValid
  private val selectParentPma = parentNeedsPma

  io.resolvedPhysicalValid := parentNeedsPma || loadNeedsPma
  io.resolvedPhysicalAddress := Mux(
    selectParentPma,
    lsu.io.resolvedPhysicalAddress,
    loadUnit.io.resolvedPhysicalAddress
  )
  lsu.io.resolvedAttributes := io.resolvedAttributes
  loadUnit.io.resolvedAttributes := io.resolvedAttributes

  // --------------------------------------------------------------------------
  // One unified data-PMP lane for exact-head parent Store/Atomic and LoadQ2.
  //
  // Parent already owns the PMA/physical lane whenever parentNeedsPma is true,
  // so feeding its candidate into the LoadQ2 shared checker cannot reduce
  // physical issue width. LR is read-only; Store/SC/other AMOs need write PMP.
  // --------------------------------------------------------------------------
  private val parentPmpCandidate = selectParentPma && lsu.io.memoryRequest.valid
  private val parentPmpWrite =
    lsu.io.memoryRequest.bits.op === AetherMemOp.Write ||
      (lsu.io.memoryRequest.bits.op === AetherMemOp.Atomic &&
        lsu.io.memoryRequest.bits.atomicOp =/= AtomicOp.Lr)

  loadUnit.io.auxPmpValid := parentPmpCandidate
  loadUnit.io.auxPmpAddress := lsu.io.resolvedPhysicalAddress
  loadUnit.io.auxPmpSize := lsu.io.memoryRequest.bits.size
  loadUnit.io.auxPmpWrite := parentPmpWrite

  private val parentPmpDenied =
    parentPmpCandidate && isaLocal.hasPmp.B && !loadUnit.io.auxPmpAllow
  private val parentPmpAllowed =
    parentPmpCandidate && (!isaLocal.hasPmp.B || loadUnit.io.auxPmpAllow)

  // --------------------------------------------------------------------------
  // Physical request namespace + response demux.
  //
  // 0/1: dual Load slot identities
  // 2:   original exact-head LSU
  // --------------------------------------------------------------------------
  private val parentPhysicalValid = parentPmpAllowed
  private val loadPhysicalValid = !selectParentPma && loadUnit.io.memoryRequest.valid
  private val parentPrivateTxn = Reg(UInt(txnIdBits.W))
  private val parentPmpFaultPending = RegInit(false.B)

  io.memoryRequest.valid := parentPhysicalValid || loadPhysicalValid
  io.memoryRequest.bits := Mux(parentPhysicalValid, lsu.io.memoryRequest.bits, loadUnit.io.memoryRequest.bits)
  when(parentPhysicalValid) {
    io.memoryRequest.bits.txnId := 2.U
  }

  // A denied parent access completes the child's request lifetime locally but
  // never externalizes memory. A synthetic fault response on the following
  // cycle lets the already-qualified TinyBlockingLsu construct the exact same
  // Store/Load/AMO access-fault completion through its existing adapter path.
  lsu.io.memoryRequest.ready :=
    Mux(parentPmpDenied, true.B, parentPhysicalValid && io.memoryRequest.ready)
  loadUnit.io.memoryRequest.ready := loadPhysicalValid && io.memoryRequest.ready

  when(lsu.io.memoryRequest.fire) {
    parentPrivateTxn := lsu.io.memoryRequest.bits.txnId
    when(parentPmpDenied) {
      parentPmpFaultPending := true.B
    }
  }
  when(lsu.io.completion.fire) {
    parentPmpFaultPending := false.B
  }

  private val responseIsLoad = io.memoryResponse.bits.txnId < 2.U
  private val responseIsParent = io.memoryResponse.bits.txnId === 2.U

  loadUnit.io.memoryResponse.valid := io.memoryResponse.valid && responseIsLoad
  loadUnit.io.memoryResponse.bits := io.memoryResponse.bits

  private val externalParentResponse = io.memoryResponse.valid && responseIsParent
  lsu.io.memoryResponse.valid := parentPmpFaultPending || externalParentResponse
  lsu.io.memoryResponse.bits := io.memoryResponse.bits
  lsu.io.memoryResponse.bits.txnId := parentPrivateTxn
  when(parentPmpFaultPending) {
    lsu.io.memoryResponse.bits.rdata := 0.U
    lsu.io.memoryResponse.bits.fault := true.B
    lsu.io.memoryResponse.bits.last := true.B
  }
  when(parentPmpFaultPending && lsu.io.memoryResponse.fire) {
    parentPmpFaultPending := false.B
  }

  io.memoryResponse.ready := Mux(
    responseIsLoad,
    loadUnit.io.memoryResponse.ready,
    Mux(
      responseIsParent,
      lsu.io.memoryResponse.ready && !parentPmpFaultPending,
      false.B
    )
  )

  when(io.memoryResponse.valid) {
    assert(responseIsLoad || responseIsParent,
      "LoadQ2 physical response txnId must identify Load slot 0/1 or exact-head owner 2")
  }

  // --------------------------------------------------------------------------
  // Merge memory completions into the parent's existing completion class.
  // --------------------------------------------------------------------------
  val memoryCompletions = Module(new Arbiter(
    new ExecutionResponse(xlenLocal, IdentityBitsLocal, GenerationBitsLocal),
    2
  ))
  memoryCompletions.io.in(0).valid := lsu.io.completion.valid
  memoryCompletions.io.in(0).bits := lsu.io.completion.bits
  lsu.io.completion.ready := memoryCompletions.io.in(0).ready
  memoryCompletions.io.in(1) <> loadUnit.io.completion

  completions.io.in(1).valid := memoryCompletions.io.out.valid
  completions.io.in(1).bits := memoryCompletions.io.out.bits
  memoryCompletions.io.out.ready := completions.io.in(1).ready

  io.lsuBusy := lsu.io.busy || loadUnit.io.busy

  // --------------------------------------------------------------------------
  // Retirement-time Load trace ownership. The parent already keeps the exact-
  // head Store/Atomic trace table; this parallel table adds only dual-Load
  // physical-read addresses and reveals them at retirement under full token
  // generation matching.
  // --------------------------------------------------------------------------
  private val loadTraceValid = RegInit(VecInit(Seq.fill(EntriesLocal)(false.B)))
  private val loadTrace = Reg(Vec(
    EntriesLocal,
    new TinyMemoryTrace(xlenLocal, PhysicalBitsLocal, IdentityBitsLocal, GenerationBitsLocal)
  ))

  when(dependencyBackend.io.allocated.valid) {
    loadTraceValid(dependencyBackend.io.allocated.bits.robToken.index) := false.B
  }
  when(loadUnit.io.memoryTrace.valid) {
    val index = loadUnit.io.memoryTrace.bits.robToken.index
    loadTraceValid(index) := true.B
    loadTrace(index) := loadUnit.io.memoryTrace.bits
  }

  private val retiring = dependencyBackend.io.retiring
  private val retiringIsLoad = retiring.valid &&
    retiring.bits.uop.executionClass === ExecutionClass.Memory &&
    retiring.bits.uop.decoded.memory.kind === MemoryOperationKind.Load
  private val retiringLoadTrace = loadTrace(retiring.bits.uop.robToken.index)
  private val retiringLoadTraceMatches = retiringIsLoad &&
    loadTraceValid(retiring.bits.uop.robToken.index) &&
    sameToken(retiringLoadTrace.robToken, retiring.bits.uop.robToken)

  when(retiringIsLoad) {
    val committedLoad = !retiring.bits.exception.valid && retiringLoadTraceMatches
    io.commit.memValid := committedLoad
    io.commit.memWrite := false.B
    io.commit.memAddr := retiringLoadTrace.paddr
    io.commit.memWdata := 0.U
    io.commit.memWmask := 0.U
  }

  when(retiringLoadTraceMatches) {
    loadTraceValid(retiring.bits.uop.robToken.index) := false.B
  }
  when(dependencyBackend.io.acceptedRecovery.valid ||
       dependencyBackend.io.acceptedPrivilegedRecovery.valid) {
    for (index <- 0 until EntriesLocal) {
      loadTraceValid(index) := false.B
    }
  }

  // A head ordinary Load must be owned by the dual unit, never by the parent
  // LSU. This prevents duplicate externalization when a pre-head Load reaches
  // the commit head while one of its read transactions is still outstanding.
  when(headOrdinaryLoad) {
    assert(!lsu.io.request.valid, "ordinary Load must not enter exact-head parent LSU in LoadQ2 mode")
  }
}
