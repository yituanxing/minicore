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

class V2F7PagedCoreSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim
    with V2F7PagedCoreChecks
