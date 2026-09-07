package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, PrivilegeMode}
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.core.{PhysicalAddressNarrowing, PmpConstants, TranslationUnit}
import aethercore.memory.{AetherMemRequest, AetherMemResponse}

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
      allowAtomics = allowAtomics,
      externalDataTranslation = true
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
  val loadUnit = Module(new TinySharedTranslationLoadUnit(
    geometry,
    paddrBits = PhysicalBitsLocal,
    tlbEntries = tlbEntries,
    txnIdBits = txnIdBits,
    externalTranslation = true
  ))
  val loadIssue = Module(new TinyPhysicalLoadQueueIssue(xlenLocal))

  loadIssue.io.slots := dependencyBackend.io.physicalSchedulingSlots
  loadIssue.io.headIndex := dependencyBackend.io.headIndex
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

  // --------------------------------------------------------------------------
  // One shared data TranslationUnit for parent Store/Atomic + Load slot0/slot1.
  //
  // Owner encoding:
  //   0 = exact-head parent LSU
  //   1 = LoadQ2 slot0
  //   2 = LoadQ2 slot1
  //
  // Exact-head translation has priority. Younger Loads may probe the shared TLB,
  // but a miss is not accepted unless that exact token owns ROB head. This
  // preserves the frozen no-speculative-PTW rule while still allowing pre-head
  // TLB hits to launch replay-safe physical reads.
  // --------------------------------------------------------------------------
  private val sharedTranslation = Module(new TranslationUnit(
    geometry,
    tlbEntries = tlbEntries,
    externalWalkGate = true,
    implementedPaddrBits = PhysicalBitsLocal
  ))

  private val parentTranslation = lsu.io.translationRequest.get
  private val loadTranslations = loadUnit.io.translationRequests.get
  private val parentTranslationToken = lsu.io.translationToken.get
  private val loadTranslationTokens = loadUnit.io.translationTokens.get

  private val parentTranslationHead =
    parentTranslation.valid && head.valid &&
      sameToken(parentTranslationToken, head.bits.robToken)
  private val load0TranslationHead =
    loadTranslations(0).valid && head.valid &&
      sameToken(loadTranslationTokens(0), head.bits.robToken)
  private val load1TranslationHead =
    loadTranslations(1).valid && head.valid &&
      sameToken(loadTranslationTokens(1), head.bits.robToken)

  private val loadTranslationRoundRobin = RegInit(false.B)
  private val activeTranslationOwnerValid = RegInit(false.B)
  private val activeTranslationOwner = RegInit(0.U(2.W))

  private val selectedTranslationOwner = WireDefault(0.U(2.W))
  private val selectedTranslationValid = WireDefault(false.B)
  private val selectedTranslationIsHead = WireDefault(false.B)

  when(parentTranslationHead) {
    selectedTranslationOwner := 0.U
    selectedTranslationValid := true.B
    selectedTranslationIsHead := true.B
  }.elsewhen(load0TranslationHead) {
    selectedTranslationOwner := 1.U
    selectedTranslationValid := true.B
    selectedTranslationIsHead := true.B
  }.elsewhen(load1TranslationHead) {
    selectedTranslationOwner := 2.U
    selectedTranslationValid := true.B
    selectedTranslationIsHead := true.B
  }.elsewhen(parentTranslation.valid) {
    // Parent policy should make this exact-head already; keep a fail-safe path
    // rather than silently dropping a live inherited LSU request.
    selectedTranslationOwner := 0.U
    selectedTranslationValid := true.B
  }.elsewhen(loadTranslations(0).valid && loadTranslations(1).valid) {
    selectedTranslationOwner := Mux(loadTranslationRoundRobin, 2.U, 1.U)
    selectedTranslationValid := true.B
  }.elsewhen(loadTranslations(0).valid) {
    selectedTranslationOwner := 1.U
    selectedTranslationValid := true.B
  }.elsewhen(loadTranslations(1).valid) {
    selectedTranslationOwner := 2.U
    selectedTranslationValid := true.B
  }

  private val selectedTranslationBits = Mux(
    selectedTranslationOwner === 0.U,
    parentTranslation.bits,
    Mux(
      selectedTranslationOwner === 1.U,
      loadTranslations(0).bits,
      loadTranslations(1).bits
    )
  )

  sharedTranslation.io.requestValid :=
    selectedTranslationValid && !activeTranslationOwnerValid
  sharedTranslation.io.kill := false.B
  sharedTranslation.io.flush := io.translationFence
  sharedTranslation.io.virtualAddress := selectedTranslationBits.virtualAddress
  sharedTranslation.io.privilege := selectedTranslationBits.privilege
  sharedTranslation.io.write := selectedTranslationBits.write
  sharedTranslation.io.execute := false.B
  sharedTranslation.io.satpTranslationEnabled :=
    selectedTranslationBits.satpTranslationEnabled
  sharedTranslation.io.satpRootPpn := selectedTranslationBits.satpRootPpn
  sharedTranslation.io.sum := selectedTranslationBits.sum
  sharedTranslation.io.mxr := selectedTranslationBits.mxr
  sharedTranslation.io.walkAllowed.get := selectedTranslationIsHead

  parentTranslation.ready := false.B
  loadTranslations(0).ready := false.B
  loadTranslations(1).ready := false.B
  when(!activeTranslationOwnerValid && selectedTranslationValid) {
    when(selectedTranslationOwner === 0.U) {
      parentTranslation.ready := sharedTranslation.io.requestReady
    }.elsewhen(selectedTranslationOwner === 1.U) {
      loadTranslations(0).ready := sharedTranslation.io.requestReady
    }.otherwise {
      loadTranslations(1).ready := sharedTranslation.io.requestReady
    }
  }

  // Rotate only among speculative Load probes. A miss with walkAllowed=false
  // therefore cannot permanently starve the other slot's potential TLB hit.
  when(!activeTranslationOwnerValid &&
       selectedTranslationValid &&
       !selectedTranslationIsHead &&
       selectedTranslationOwner =/= 0.U) {
    loadTranslationRoundRobin := selectedTranslationOwner === 1.U
  }

  private val responseOwner = Mux(
    activeTranslationOwnerValid,
    activeTranslationOwner,
    selectedTranslationOwner
  )

  private val parentTranslationResponse = lsu.io.translationResponse.get
  private val loadTranslationResponses = loadUnit.io.translationResponses.get

  parentTranslationResponse.valid :=
    sharedTranslation.io.responseValid && responseOwner === 0.U
  loadTranslationResponses(0).valid :=
    sharedTranslation.io.responseValid && responseOwner === 1.U
  loadTranslationResponses(1).valid :=
    sharedTranslation.io.responseValid && responseOwner === 2.U

  parentTranslationResponse.bits.physicalAddress :=
    sharedTranslation.io.physicalAddress
  parentTranslationResponse.bits.pageFault := sharedTranslation.io.pageFault
  parentTranslationResponse.bits.accessFault := sharedTranslation.io.accessFault

  for (index <- 0 until 2) {
    loadTranslationResponses(index).bits.physicalAddress :=
      sharedTranslation.io.physicalAddress
    loadTranslationResponses(index).bits.pageFault := sharedTranslation.io.pageFault
    loadTranslationResponses(index).bits.accessFault := sharedTranslation.io.accessFault
  }

  sharedTranslation.io.responseReady := Mux(
    responseOwner === 0.U,
    parentTranslationResponse.ready,
    Mux(
      responseOwner === 1.U,
      loadTranslationResponses(0).ready,
      loadTranslationResponses(1).ready
    )
  )

  private val translationRequestFire =
    sharedTranslation.io.requestValid && sharedTranslation.io.requestReady
  private val translationResponseFire =
    sharedTranslation.io.responseValid && sharedTranslation.io.responseReady
  private val sameCycleTranslationCompletion =
    translationRequestFire && translationResponseFire

  when(translationRequestFire) {
    activeTranslationOwner := selectedTranslationOwner
    activeTranslationOwnerValid := !sameCycleTranslationCompletion
  }
  when(activeTranslationOwnerValid && translationResponseFire) {
    activeTranslationOwnerValid := false.B
  }
  when(io.translationFence) {
    activeTranslationOwnerValid := false.B
  }

  // The child/private PTW seams disappear in external-translation mode.
  lsu.io.pteReady := false.B
  lsu.io.pteData := io.pteData
  lsu.io.pteFault := false.B
  loadUnit.io.pteReady := false.B
  loadUnit.io.pteData := io.pteData
  loadUnit.io.pteFault := false.B

  // One PTW + one Supervisor PMP qualification serves all three data
  // lifetimes. Preserve the qualified PA56 wiring exactly; only a narrower
  // implemented platform inserts the fail-closed architectural-PA boundary.
  ptwPmp.io.privilege := PrivilegeMode.Supervisor.U
  ptwPmp.io.bytes := geometry.pteBytes.U
  ptwPmp.io.write := false.B
  ptwPmp.io.execute := false.B
  ptwPmp.io.config := csrFile.io.pmpConfig
  ptwPmp.io.pmpAddress := csrFile.io.pmpAddress
  sharedTranslation.io.pteData := io.pteData

  if (PhysicalBitsLocal >= geometry.architecturalPhysicalAddressBits) {
    val sharedPteAddress =
      sharedTranslation.io.pteAddress.pad(PhysicalBitsLocal)
    ptwPmp.io.address := sharedPteAddress
    val sharedPtePmpFault =
      sharedTranslation.io.pteValid && isaLocal.hasPmp.B && !ptwPmp.io.allow

    io.pteValid := sharedTranslation.io.pteValid && !sharedPtePmpFault
    io.pteAddress := sharedPteAddress
    sharedTranslation.io.pteReady :=
      Mux(sharedPtePmpFault, true.B, io.pteReady)
    sharedTranslation.io.pteFault :=
      sharedPtePmpFault || (io.pteValid && io.pteFault)
  } else {
    val (sharedPteAddress, sharedPteOutOfRange) =
      PhysicalAddressNarrowing(sharedTranslation.io.pteAddress, PhysicalBitsLocal)
    val sharedPteRangeFault =
      sharedTranslation.io.pteValid && sharedPteOutOfRange

    ptwPmp.io.address := sharedPteAddress
    val sharedPtePmpFault =
      sharedTranslation.io.pteValid && !sharedPteOutOfRange &&
        isaLocal.hasPmp.B && !ptwPmp.io.allow

    io.pteValid :=
      sharedTranslation.io.pteValid && !sharedPteRangeFault && !sharedPtePmpFault
    io.pteAddress := sharedPteAddress
    sharedTranslation.io.pteReady :=
      Mux(sharedPteRangeFault || sharedPtePmpFault, true.B, io.pteReady)
    sharedTranslation.io.pteFault :=
      sharedPteRangeFault || sharedPtePmpFault || (io.pteValid && io.pteFault)
  }

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
  // Physical request namespace + response demux.
  //
  // 0/1: dual Load slot identities
  // 2:   original exact-head LSU
  // --------------------------------------------------------------------------
  private val parentPhysicalValid = selectParentPma && lsu.io.memoryRequest.valid
  private val loadPhysicalValid = !selectParentPma && loadUnit.io.memoryRequest.valid
  private val parentPrivateTxn = Reg(UInt(txnIdBits.W))

  io.memoryRequest.valid := parentPhysicalValid || loadPhysicalValid
  io.memoryRequest.bits := Mux(parentPhysicalValid, lsu.io.memoryRequest.bits, loadUnit.io.memoryRequest.bits)
  when(parentPhysicalValid) {
    io.memoryRequest.bits.txnId := 2.U
  }

  lsu.io.memoryRequest.ready := parentPhysicalValid && io.memoryRequest.ready
  loadUnit.io.memoryRequest.ready := loadPhysicalValid && io.memoryRequest.ready

  when(lsu.io.memoryRequest.fire) {
    parentPrivateTxn := lsu.io.memoryRequest.bits.txnId
  }

  private val responseIsLoad = io.memoryResponse.bits.txnId < 2.U
  private val responseIsParent = io.memoryResponse.bits.txnId === 2.U

  loadUnit.io.memoryResponse.valid := io.memoryResponse.valid && responseIsLoad
  loadUnit.io.memoryResponse.bits := io.memoryResponse.bits

  lsu.io.memoryResponse.valid := io.memoryResponse.valid && responseIsParent
  lsu.io.memoryResponse.bits := io.memoryResponse.bits
  lsu.io.memoryResponse.bits.txnId := parentPrivateTxn

  io.memoryResponse.ready := Mux(
    responseIsLoad,
    loadUnit.io.memoryResponse.ready,
    Mux(responseIsParent, lsu.io.memoryResponse.ready, false.B)
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
