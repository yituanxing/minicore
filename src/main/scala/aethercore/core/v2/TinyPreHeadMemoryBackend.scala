package aethercore.core.v2

import chisel3._
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.memory.AetherMemOp

/**
  * Bounded Path-C extension of TinyMemoryBackend for evidence-driven pre-head
  * Load overlap.
  *
  * The qualified parent remains the owner of ROB/Commit/CSR/PMP/completion,
  * transaction lifetime and retirement-time memory traces. This subclass only
  * replaces the head-only memory-request selection with TinySelectiveLoadIssue
  * and adds fail-closed gates at the already-public PTW/physical-memory seams.
  */
class TinyPreHeadMemoryBackend(
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
  private val IdentityBitsLocal = TinyRobGeometry.IndexBits
  private val GenerationBitsLocal = TinyRobGeometry.GenerationBits

  private def sameToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  val preHeadLoadIssue = Module(new TinySelectiveLoadIssue(xlenLocal))
  preHeadLoadIssue.io.window := dependencyBackend.io.schedulingWindow
  preHeadLoadIssue.io.allocated := dependencyBackend.io.allocated
  preHeadLoadIssue.io.available := !lsu.io.busy

  // Keep the launch cut independent of completion/recovery feedback. In
  // particular, an exact-head Load may produce a same-cycle fault completion;
  // feeding accepted recovery back into this request.valid would form a
  // completion -> LSU request -> selector block -> completion combinational loop.
  // Selector policy already treats Branch/System/Memory/serialization/known
  // exceptions as hard barriers for younger Loads.
  preHeadLoadIssue.io.block := branchIssue.io.request.valid

  // Last-connect override of the parent's exact-head request builder. The
  // current M1 LSU remains unchanged, preserving its one-outstanding lifetime,
  // transaction IDs, faults, Decoupled completion and observation status.
  lsu.io.request.valid := preHeadLoadIssue.io.request.valid
  lsu.io.request.bits := preHeadLoadIssue.io.request.bits
  preHeadLoadIssue.io.request.ready := lsu.io.request.ready

  val acceptedPreHead = RegInit(false.B)
  val acceptedToken = Reg(new RobToken(IdentityBitsLocal, GenerationBitsLocal))
  when(lsu.io.request.fire) {
    acceptedPreHead := preHeadLoadIssue.io.preHead
    acceptedToken := lsu.io.request.bits.robToken
    when(preHeadLoadIssue.io.preHead) {
      assert(lsu.io.request.bits.kind === MemoryOperationKind.Load,
        "pre-head backend may launch only an ordinary Load")
    }
  }
  when(lsu.io.completion.fire) {
    acceptedPreHead := false.B
  }

  // LSU intake flow-through may expose translation/memory signals on the same
  // cycle as request.fire, before the accepted-token registers update.
  val intakePreHead = lsu.io.request.fire && preHeadLoadIssue.io.preHead
  val workingPreHead = Mux(lsu.io.busy, acceptedPreHead, intakePreHead)
  val workingToken = Wire(new RobToken(IdentityBitsLocal, GenerationBitsLocal))
  workingToken := acceptedToken
  when(!lsu.io.busy) {
    workingToken := lsu.io.request.bits.robToken
  }

  val currentHead = dependencyBackend.io.schedulingWindow(0)
  val headPermitMatches = currentHead.valid &&
    currentHead.uop.executionClass === ExecutionClass.Memory &&
    sameToken(currentHead.uop.robToken, workingToken)
  val speculative = workingPreHead && !headPermitMatches

  val safetyGate = Module(new TinyPreHeadSafetyGate)
  safetyGate.io.speculative := speculative
  safetyGate.io.memoryValid := lsu.io.memoryRequest.valid
  safetyGate.io.memoryOp := lsu.io.memoryRequest.bits.op
  safetyGate.io.attributes := io.resolvedAttributes

  // A younger TLB miss may occupy internal translation state, but the first
  // slice never externalizes speculative page-table traffic. Once the Load is
  // exact head, the existing walker resumes with no added replay structure.
  // The pre-head gate still prevents speculative PTW traffic from
  // externalizing. Once the request is permitted, PMP is owned by the parent
  // TinyPagedCore after fetch/data arbitration and any denial returns through
  // the ordinary io.pteFault path.
  io.pteValid := lsu.io.pteValid && safetyGate.io.ptePermit
  io.pteAddress := lsu.io.pteAddress
  lsu.io.pteReady := safetyGate.io.ptePermit && io.pteReady
  lsu.io.pteData := io.pteData
  lsu.io.pteFault := safetyGate.io.ptePermit && io.pteValid && io.pteFault

  // PMA comes from the already-resolved physical address. A younger physical
  // read may externalize only when replay-safe; MMIO/ordered/side-effecting
  // regions remain exact-head. Stores and atomics can never be selected pre-head.
  io.memoryRequest.valid := lsu.io.memoryRequest.valid && safetyGate.io.memoryPermit
  io.memoryRequest.bits := lsu.io.memoryRequest.bits
  lsu.io.memoryRequest.ready := io.memoryRequest.ready && safetyGate.io.memoryPermit

  when(speculative && lsu.io.memoryRequest.valid) {
    assert(lsu.io.memoryRequest.bits.op === AetherMemOp.Read,
      "only an ordinary read may reach the pre-head PMA gate")
  }
  when(io.memoryRequest.fire && speculative) {
    assert(safetyGate.io.memoryPermit,
      "pre-head physical read must satisfy the replay-safe PMA gate")
  }
  when(io.pteValid) {
    assert(!speculative, "pre-head lifetime must not externalize page-table traffic")
  }
}
