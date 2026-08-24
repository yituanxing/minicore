package aethercore

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Independent v2 stage entry points for fast, stage-local CI selection.
  *
  * The check traits remain reusable, but no longer have to be mixed into the
  * legacy/cross-width DatapathWidthSpec just to become executable tests.
  */
class V2F1RobCommitSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F1RobCommitChecks

class V2F2DependencySpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F2DependencyChecks

class V2F3ExecutionSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F3ExecutionChecks
    with V2F3ExecutionSemanticChecks
    with V2P8BranchResponseFlowThroughChecks

class V2F4RecoverySpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F4RecoveryChecks

class V2F5PrivilegedSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F5PrivilegedChecks

class V2F6BlockingLsuSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F6BlockingLsuChecks
    with V2F6MemoryBackendChecks
    with V2F6VirtualMemoryChecks
    with V2F6Sv32MemoryChecks
    with V2F6SfenceChecks
    with V2F6MemoryClosureChecks
    with V2A8CompletionChecks
    with V2A8SchedulingViewChecks
    with V2A8SelectiveIssueChecks
    with V2A8SelectiveExecutionChecks
    with V2A8SelectiveBarrierChecks
    with V2A8ProductionSelectiveIssueChecks
    with V2P8PerformanceChecks
    with V2P8LsuIntakeFlowThroughChecks

class V2A8CompletionSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2A8CompletionChecks

class V2A8SchedulingViewSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2A8SchedulingViewChecks

class V2A8SelectiveIssueSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2A8SelectiveIssueChecks
    with V2P8PreHeadLoadSelectorChecks

class V2A8SelectiveExecutionSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2A8SelectiveExecutionChecks
    with V2A8SelectiveBarrierChecks

class V2A8ProductionSelectiveIssueSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2A8ProductionSelectiveIssueChecks

class V2P8PerformanceSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2P8PerformanceChecks

class V2F7SemanticDecodeSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F7SemanticDecodeChecks

class V2F7BareCoreSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F7BareCoreChecks
    with V2F7GenerationWrapChecks

class V2F7PagedCoreSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F7PagedCoreChecks

class V2F7AsyncInterruptSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F7AsyncInterruptChecks

class V2F7AtomicLsuSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F7AtomicLsuChecks

class V2F7AtomicCoreSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F7AtomicCoreChecks
    with V2F7OpenSbiAtomicChecks
