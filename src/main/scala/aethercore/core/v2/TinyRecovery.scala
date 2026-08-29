package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.CommitTrace

/**
  * Frontend-facing consequence of an already validated normal branch recovery.
  *
  * This is intentionally smaller than ExecutionResponse: the frontend needs a
  * surviving instruction identity and a target PC, not producer/value identities
  * or execution-unit details. Trap/exception redirects belong to F5.
  */
class RecoveryRedirect(val xlen: Int) extends Bundle {
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val robToken = new RobToken(IdentityBits, GenerationBits)
  val target = UInt(xlen.W)
}

/**
  * F4 thin composition.
  *
  * It reuses the F2 dependency substrate and the F3 issue/execution units. The
  * only new external behavior is a redirect derived from TinyRob.acceptedRecovery.
  * Raw branch functional-unit responses never reach this redirect seam directly.
  */
class TinyRecoveryBackend(val xlen: Int, val hasCompressed: Boolean = false) extends Module {
  require(xlen == 32 || xlen == 64, s"v2 F4 backend XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val commit = Output(new CommitTrace(xlen = xlen))
    val redirect = Valid(new RecoveryRedirect(xlen))
    val occupancy = Output(UInt(log2Ceil(TinyRobGeometry.Entries + 1).W))
  })

  val dependencyBackend = Module(new TinyDependencyBackend(xlen))
  val issue = Module(new TinyOldestIssue(xlen))
  val execution = Module(new TinyExecutionCluster(xlen, hasCompressed))

  dependencyBackend.io.dispatch.valid := io.dispatch.valid
  dependencyBackend.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := dependencyBackend.io.dispatch.ready
  io.allocated := dependencyBackend.io.allocated
  io.commit := dependencyBackend.io.commit
  io.occupancy := dependencyBackend.io.occupancy

  issue.io.head := dependencyBackend.io.head
  issue.io.headDependenciesValid := dependencyBackend.io.headDependenciesValid
  issue.io.headRs1 := dependencyBackend.io.headRs1
  issue.io.headRs2 := dependencyBackend.io.headRs2
  issue.io.headOperandsReady := dependencyBackend.io.headOperandsReady
  execution.io.request <> issue.io.request

  dependencyBackend.io.completion.valid := execution.io.response.valid
  dependencyBackend.io.completion.bits := execution.io.response.bits
  execution.io.response.ready := true.B

  io.redirect.valid := dependencyBackend.io.acceptedRecovery.valid
  io.redirect.bits := 0.U.asTypeOf(new RecoveryRedirect(xlen))
  io.redirect.bits.robToken := dependencyBackend.io.acceptedRecovery.bits.robToken
  io.redirect.bits.target := dependencyBackend.io.acceptedRecovery.bits.branchTarget
}
