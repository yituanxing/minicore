package aethercore.core.v2

import chisel3._
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.memory.AetherMemOp

/**
  * Experimental Path-C extension of TinyMemoryBackend.
  *
  * The qualified parent remains the owner of ROB/Commit/CSR/PMP/completion,
  * transaction lifetime and retirement-time memory traces. This subclass only
  * replaces the head-only memory-request selection with TinySelectiveLoadIssue
  * and adds fail-closed gates at the already-public PTW/physical-memory seams.
  * If the experiment is promoted, these few overrides should be folded back
  * into TinyMemoryBackend rather than retaining a permanent parallel backend.
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
  preHeadLoadIssue.io.block :=
    branchIssue.io.request.valid ||
      dependencyBackend.io.acceptedRecovery.valid ||
      dependencyBackend.io.acceptedPrivilegedRecovery.valid

  // Last-connect override of the parent's exact-head-only request builder. The
  // parent LSU itself remains unchanged and therefore retains its qualified
  // one-outstanding, transaction-ID, fault and completion behavior.
  lsu.io.request.valid := preHeadLoadIssue.io.request.valid
  lsu.io.request.bits := preHeadLoadIssue.io.request.bits
  preHeadLoadIssue.io.request.ready := lsu.io.request.ready

  // The parent's compute selector already blocks on lsu.io.request.valid. After
  // the override above, a ready Memory request (head or conservative pre-head)
  // keeps the existing Memory-over-compute single-launch priority. The parent's
  // global PopCount assertion therefore continues to prove launch width <= 1.

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

  // Recompute the parent's PTW PMP predicate at the same public seam. A younger
  // TLB miss may occupy internal walker state, but no page-table memory request
  // is made visible until the load is the exact head. Once head matches, the
  // qualified parent walker resumes without a replay structure.
  val preHeadPtwPmpFault = lsu.io.pteValid && isaLocal.hasPmp.B && !ptwPmp.io.allow
  io.pteValid := lsu.io.pteValid && !preHeadPtwPmpFault && !speculative
  io.pteAddress := lsu.io.pteAddress
  lsu.io.pteReady := !speculative && Mux(preHeadPtwPmpFault, true.B, io.pteReady)
  lsu.io.pteData := io.pteData
  lsu.io.pteFault := !speculative && (
    preHeadPtwPmpFault || (io.pteValid && io.pteFault)
  )

  // PMA is supplied by the platform from the resolved physical address. A
  // younger read may externalize only if replaying the read would be harmless;
  // MMIO, ordered and side-effecting regions wait until exact-head permission.
  val speculativeReadSafe =
    io.resolvedAttributes.idempotent &&
      !io.resolvedAttributes.sideEffecting &&
      !io.resolvedAttributes.ordered
  val speculativeMemoryPermit = !speculative || (
    lsu.io.memoryRequest.bits.op === AetherMemOp.Read && speculativeReadSafe
  )

  io.memoryRequest.valid := lsu.io.memoryRequest.valid && speculativeMemoryPermit
  io.memoryRequest.bits := lsu.io.memoryRequest.bits
  lsu.io.memoryRequest.ready := io.memoryRequest.ready && speculativeMemoryPermit

  when(speculative && lsu.io.memoryRequest.valid) {
    assert(lsu.io.memoryRequest.bits.op === AetherMemOp.Read,
      "only an ordinary read may reach the pre-head PMA gate")
  }
  when(io.memoryRequest.fire && speculative) {
    assert(speculativeReadSafe,
      "pre-head physical read must be idempotent, non-side-effecting and non-ordered")
  }
  when(io.pteValid) {
    assert(!speculative, "pre-head lifetime must not externalize page-table traffic")
  }
}
