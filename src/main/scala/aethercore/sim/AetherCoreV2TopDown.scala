package aethercore.sim

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import aethercore.core.v2.{ExecutionClass, MemoryOperationKind}

/** Raw, observation-only facts for P8 causal performance attribution.
  *
  * The counter bank owns the classification rules so mutually-exclusive
  * accounting can be tested independently from Linux and from the production
  * scheduler. Nothing in this bundle is consumed by TinyPagedCore.
  */
class V2CausalPerformanceEvents extends Bundle {
  val frontValid = Bool()
  val backendReady = Bool()

  val commit = Bool()
  val robNonEmpty = Bool()
  val headValid = Bool()
  val headClass = ExecutionClass()

  val lsuBusy = Bool()
  val lifetimeValid = Bool()
  val memoryKind = MemoryOperationKind()
  val physicalAddressValid = Bool()
  val writeLike = Bool()
  val writePermitMatched = Bool()
  val physicalRequestIssued = Bool()
  val completionPending = Bool()
  val memoryRequestValid = Bool()
  val memoryRequestReady = Bool()
  val memoryRequestFire = Bool()
}

/** Causal cycle counters. Top-down and critical-CPI families each partition all
  * measured cycles. Memory kind and memory stage each independently partition
  * exactly the legacy `lsuBusy` parent counter.
  */
class V2CausalPerformanceCounters extends Bundle {
  val cycles = UInt(64.W)

  val flow = UInt(64.W)
  val frontendBound = UInt(64.W)
  val backendBound = UInt(64.W)

  val criticalRetire = UInt(64.W)
  val criticalRobEmpty = UInt(64.W)
  val criticalCompute = UInt(64.W)
  val criticalBranch = UInt(64.W)
  val criticalMemory = UInt(64.W)
  val criticalSystem = UInt(64.W)
  val criticalOther = UInt(64.W)

  val lsuBusy = UInt(64.W)
  val memoryKindLoad = UInt(64.W)
  val memoryKindStore = UInt(64.W)
  val memoryKindAtomic = UInt(64.W)
  val memoryKindOther = UInt(64.W)

  val memoryStageResolve = UInt(64.W)
  val memoryStagePermit = UInt(64.W)
  val memoryStageRequestBackpressure = UInt(64.W)
  val memoryStageRequestFire = UInt(64.W)
  val memoryStageResponse = UInt(64.W)
  val memoryStageCompletion = UInt(64.W)
  val memoryStageOther = UInt(64.W)

  val resolveLoad = UInt(64.W)
  val resolveStore = UInt(64.W)
  val resolveAtomic = UInt(64.W)
  val responseLoad = UInt(64.W)
  val responseStore = UInt(64.W)
  val responseAtomic = UInt(64.W)
  val completionLoad = UInt(64.W)
  val completionStore = UInt(64.W)
  val completionAtomic = UInt(64.W)
  val permitStore = UInt(64.W)
  val permitAtomic = UInt(64.W)
}

/** Simulation-only causal classifier and accumulator. */
class V2CausalPerformanceCounterBank extends Module {
  val io = IO(new Bundle {
    val events = Input(new V2CausalPerformanceEvents)
    val counters = Output(new V2CausalPerformanceCounters)
  })

  private val cycles = RegInit(0.U(64.W))
  cycles := cycles + 1.U

  private def count(event: Bool): UInt = {
    val value = RegInit(0.U(64.W))
    when(event) { value := value + 1.U }
    value
  }

  // Intel-style top-down ownership at the frontend/backend handoff. When both
  // sides are unable to make progress, backend owns the cycle because making
  // the frontend perfect would not make that cycle dispatch.
  private val flow = io.events.frontValid && io.events.backendReady
  private val frontendBound = !io.events.frontValid && io.events.backendReady
  private val backendBound = !io.events.backendReady

  // One-wide in-order retirement gives this small core a natural critical
  // point: on every non-retire cycle, classify the oldest architectural state.
  private val criticalRetire = io.events.commit
  private val criticalNoRetire = !io.events.commit
  private val criticalRobEmpty = criticalNoRetire && !io.events.robNonEmpty
  private val criticalHead = criticalNoRetire && io.events.robNonEmpty
  private val headCompute = io.events.headValid &&
    (io.events.headClass === ExecutionClass.Integer ||
      io.events.headClass === ExecutionClass.MulDiv)
  private val headBranch = io.events.headValid && io.events.headClass === ExecutionClass.Branch
  private val headMemory = io.events.headValid && io.events.headClass === ExecutionClass.Memory
  private val headSystem = io.events.headValid && io.events.headClass === ExecutionClass.System
  private val criticalCompute = criticalHead && headCompute
  private val criticalBranch = criticalHead && headBranch
  private val criticalMemory = criticalHead && headMemory
  private val criticalSystem = criticalHead && headSystem
  private val criticalOther = criticalHead && !(headCompute || headBranch || headMemory || headSystem)

  // Preserve the legacy lsuBusy parent exactly. Lifetime-invalid and unknown
  // kinds fall into explicit `other` buckets rather than disappearing.
  private val memoryActive = io.events.lsuBusy
  private val kindLoad = memoryActive && io.events.lifetimeValid &&
    io.events.memoryKind === MemoryOperationKind.Load
  private val kindStore = memoryActive && io.events.lifetimeValid &&
    io.events.memoryKind === MemoryOperationKind.Store
  private val kindAtomic = memoryActive && io.events.lifetimeValid &&
    io.events.memoryKind === MemoryOperationKind.Atomic
  private val kindOther = memoryActive && !(kindLoad || kindStore || kindAtomic)

  // Strict priority makes the memory stage family mutually exclusive. The
  // request-fire bucket is split from physical-issued because M1 deliberately
  // reports physicalRequestIssued in the handshake cycle itself.
  private val stageCompletion = memoryActive && io.events.lifetimeValid &&
    io.events.completionPending
  private val stageRequestFire = memoryActive && io.events.lifetimeValid &&
    !stageCompletion && io.events.memoryRequestFire
  private val stageResponse = memoryActive && io.events.lifetimeValid &&
    !stageCompletion && !stageRequestFire && io.events.physicalRequestIssued
  private val stageResolve = memoryActive && io.events.lifetimeValid &&
    !stageCompletion && !stageRequestFire && !stageResponse &&
    !io.events.physicalAddressValid
  private val stagePermit = memoryActive && io.events.lifetimeValid &&
    !stageCompletion && !stageRequestFire && !stageResponse && !stageResolve &&
    io.events.writeLike && !io.events.writePermitMatched
  private val stageRequestBackpressure = memoryActive && io.events.lifetimeValid &&
    !stageCompletion && !stageRequestFire && !stageResponse && !stageResolve &&
    !stagePermit && io.events.memoryRequestValid && !io.events.memoryRequestReady
  private val stageOther = memoryActive && !(
    stageCompletion || stageRequestFire || stageResponse || stageResolve ||
      stagePermit || stageRequestBackpressure
  )

  // These are instrumentation invariants, not production assertions. A bad
  // counter must fail fast instead of silently steering architecture work.
  assert(PopCount(Cat(flow, frontendBound, backendBound)) === 1.U)
  assert(PopCount(Cat(
    criticalRetire,
    criticalRobEmpty,
    criticalCompute,
    criticalBranch,
    criticalMemory,
    criticalSystem,
    criticalOther
  )) === 1.U)
  when(memoryActive) {
    assert(PopCount(Cat(kindLoad, kindStore, kindAtomic, kindOther)) === 1.U)
    assert(PopCount(Cat(
      stageResolve,
      stagePermit,
      stageRequestBackpressure,
      stageRequestFire,
      stageResponse,
      stageCompletion,
      stageOther
    )) === 1.U)
  }
  when(io.events.memoryRequestFire) {
    assert(io.events.memoryRequestValid && io.events.memoryRequestReady)
  }

  io.counters.cycles := cycles
  io.counters.flow := count(flow)
  io.counters.frontendBound := count(frontendBound)
  io.counters.backendBound := count(backendBound)

  io.counters.criticalRetire := count(criticalRetire)
  io.counters.criticalRobEmpty := count(criticalRobEmpty)
  io.counters.criticalCompute := count(criticalCompute)
  io.counters.criticalBranch := count(criticalBranch)
  io.counters.criticalMemory := count(criticalMemory)
  io.counters.criticalSystem := count(criticalSystem)
  io.counters.criticalOther := count(criticalOther)

  io.counters.lsuBusy := count(memoryActive)
  io.counters.memoryKindLoad := count(kindLoad)
  io.counters.memoryKindStore := count(kindStore)
  io.counters.memoryKindAtomic := count(kindAtomic)
  io.counters.memoryKindOther := count(kindOther)

  io.counters.memoryStageResolve := count(stageResolve)
  io.counters.memoryStagePermit := count(stagePermit)
  io.counters.memoryStageRequestBackpressure := count(stageRequestBackpressure)
  io.counters.memoryStageRequestFire := count(stageRequestFire)
  io.counters.memoryStageResponse := count(stageResponse)
  io.counters.memoryStageCompletion := count(stageCompletion)
  io.counters.memoryStageOther := count(stageOther)

  io.counters.resolveLoad := count(stageResolve && kindLoad)
  io.counters.resolveStore := count(stageResolve && kindStore)
  io.counters.resolveAtomic := count(stageResolve && kindAtomic)
  io.counters.responseLoad := count(stageResponse && kindLoad)
  io.counters.responseStore := count(stageResponse && kindStore)
  io.counters.responseAtomic := count(stageResponse && kindAtomic)
  io.counters.completionLoad := count(stageCompletion && kindLoad)
  io.counters.completionStore := count(stageCompletion && kindStore)
  io.counters.completionAtomic := count(stageCompletion && kindAtomic)
  io.counters.permitStore := count(stagePermit && kindStore)
  io.counters.permitAtomic := count(stagePermit && kindAtomic)
}

/** P8.4 causal instrumentation layered on the frozen host-visible P8 counters.
  *
  * All production scheduling signals are observed through read-only probes. The
  * inherited legacy P8 outputs remain bit-for-bit present for historical
  * comparability; this class only adds new simulation outputs.
  */
class AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttribution
    extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisible {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val causal = Module(new V2CausalPerformanceCounterBank)
  private val events = WireInit(0.U.asTypeOf(new V2CausalPerformanceEvents))

  private val dispatchValid = BoringUtils.tapAndRead(core.backend.io.dispatch.valid)
  private val dispatchReady = BoringUtils.tapAndRead(core.backend.io.dispatch.ready)
  private val head = BoringUtils.tapAndRead(core.backend.dependencyBackend.io.schedulingWindow(0))
  private val lifetime = BoringUtils.tapAndRead(core.backend.lsu.io.lifetimeStatus)

  private val observedCommitValid = BoringUtils.tapAndRead(core.io.commit.valid)
  private val observedOccupancy = BoringUtils.tapAndRead(core.io.occupancy)
  private val observedLsuBusy = BoringUtils.tapAndRead(core.io.lsuBusy)
  private val observedMemoryRequestValid = BoringUtils.tapAndRead(core.io.memoryRequest.valid)
  private val observedMemoryRequestReady = BoringUtils.tapAndRead(core.io.memoryRequest.ready)

  events.frontValid := dispatchValid
  events.backendReady := dispatchReady
  events.commit := observedCommitValid
  events.robNonEmpty := observedOccupancy =/= 0.U
  events.headValid := head.valid
  events.headClass := head.uop.executionClass

  events.lsuBusy := observedLsuBusy
  events.lifetimeValid := lifetime.valid
  events.memoryKind := lifetime.kind
  events.physicalAddressValid := lifetime.physicalAddressValid
  events.writeLike := lifetime.writeLike
  events.writePermitMatched := lifetime.writePermitMatched
  events.physicalRequestIssued := lifetime.physicalRequestIssued
  events.completionPending := lifetime.completionPending
  events.memoryRequestValid := observedMemoryRequestValid
  events.memoryRequestReady := observedMemoryRequestReady
  events.memoryRequestFire := observedMemoryRequestValid && observedMemoryRequestReady

  causal.io.events := events

  val ioTopDownCycles = IO(Output(UInt(64.W)))
  val ioTopDownFlow = IO(Output(UInt(64.W)))
  val ioTopDownFrontendBound = IO(Output(UInt(64.W)))
  val ioTopDownBackendBound = IO(Output(UInt(64.W)))

  val ioCriticalRetire = IO(Output(UInt(64.W)))
  val ioCriticalRobEmpty = IO(Output(UInt(64.W)))
  val ioCriticalCompute = IO(Output(UInt(64.W)))
  val ioCriticalBranch = IO(Output(UInt(64.W)))
  val ioCriticalMemory = IO(Output(UInt(64.W)))
  val ioCriticalSystem = IO(Output(UInt(64.W)))
  val ioCriticalOther = IO(Output(UInt(64.W)))

  val ioCausalLsuBusy = IO(Output(UInt(64.W)))
  val ioMemoryKindLoad = IO(Output(UInt(64.W)))
  val ioMemoryKindStore = IO(Output(UInt(64.W)))
  val ioMemoryKindAtomic = IO(Output(UInt(64.W)))
  val ioMemoryKindOther = IO(Output(UInt(64.W)))

  val ioMemoryStageResolve = IO(Output(UInt(64.W)))
  val ioMemoryStagePermit = IO(Output(UInt(64.W)))
  val ioMemoryStageRequestBackpressure = IO(Output(UInt(64.W)))
  val ioMemoryStageRequestFire = IO(Output(UInt(64.W)))
  val ioMemoryStageResponse = IO(Output(UInt(64.W)))
  val ioMemoryStageCompletion = IO(Output(UInt(64.W)))
  val ioMemoryStageOther = IO(Output(UInt(64.W)))

  val ioMemoryResolveLoad = IO(Output(UInt(64.W)))
  val ioMemoryResolveStore = IO(Output(UInt(64.W)))
  val ioMemoryResolveAtomic = IO(Output(UInt(64.W)))
  val ioMemoryResponseLoad = IO(Output(UInt(64.W)))
  val ioMemoryResponseStore = IO(Output(UInt(64.W)))
  val ioMemoryResponseAtomic = IO(Output(UInt(64.W)))
  val ioMemoryCompletionLoad = IO(Output(UInt(64.W)))
  val ioMemoryCompletionStore = IO(Output(UInt(64.W)))
  val ioMemoryCompletionAtomic = IO(Output(UInt(64.W)))
  val ioMemoryPermitStore = IO(Output(UInt(64.W)))
  val ioMemoryPermitAtomic = IO(Output(UInt(64.W)))

  ioTopDownCycles := causal.io.counters.cycles
  ioTopDownFlow := causal.io.counters.flow
  ioTopDownFrontendBound := causal.io.counters.frontendBound
  ioTopDownBackendBound := causal.io.counters.backendBound

  ioCriticalRetire := causal.io.counters.criticalRetire
  ioCriticalRobEmpty := causal.io.counters.criticalRobEmpty
  ioCriticalCompute := causal.io.counters.criticalCompute
  ioCriticalBranch := causal.io.counters.criticalBranch
  ioCriticalMemory := causal.io.counters.criticalMemory
  ioCriticalSystem := causal.io.counters.criticalSystem
  ioCriticalOther := causal.io.counters.criticalOther

  ioCausalLsuBusy := causal.io.counters.lsuBusy
  ioMemoryKindLoad := causal.io.counters.memoryKindLoad
  ioMemoryKindStore := causal.io.counters.memoryKindStore
  ioMemoryKindAtomic := causal.io.counters.memoryKindAtomic
  ioMemoryKindOther := causal.io.counters.memoryKindOther

  ioMemoryStageResolve := causal.io.counters.memoryStageResolve
  ioMemoryStagePermit := causal.io.counters.memoryStagePermit
  ioMemoryStageRequestBackpressure := causal.io.counters.memoryStageRequestBackpressure
  ioMemoryStageRequestFire := causal.io.counters.memoryStageRequestFire
  ioMemoryStageResponse := causal.io.counters.memoryStageResponse
  ioMemoryStageCompletion := causal.io.counters.memoryStageCompletion
  ioMemoryStageOther := causal.io.counters.memoryStageOther

  ioMemoryResolveLoad := causal.io.counters.resolveLoad
  ioMemoryResolveStore := causal.io.counters.resolveStore
  ioMemoryResolveAtomic := causal.io.counters.resolveAtomic
  ioMemoryResponseLoad := causal.io.counters.responseLoad
  ioMemoryResponseStore := causal.io.counters.responseStore
  ioMemoryResponseAtomic := causal.io.counters.responseAtomic
  ioMemoryCompletionLoad := causal.io.counters.completionLoad
  ioMemoryCompletionStore := causal.io.counters.completionStore
  ioMemoryCompletionAtomic := causal.io.counters.completionAtomic
  ioMemoryPermitStore := causal.io.counters.permitStore
  ioMemoryPermitAtomic := causal.io.counters.permitAtomic
}
