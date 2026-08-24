package aethercore.sim

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import aethercore.core.v2.{ExecutionClass, MemoryOperationKind, OrderingClass, TinySchedulingEntry}

/** One-cycle, observation-only memory decomposition sample.
  *
  * "Ready younger load" counters are scheduling exposure only. They deliberately
  * do not claim that a younger load may externalize a physical read: PMA/device
  * side effects, precise faults and memory ordering still require a separately
  * qualified architecture mechanism.
  */
class V2MemoryDecompositionEvents extends Bundle {
  val memoryHeadLoad = Bool()
  val memoryHeadStore = Bool()
  val memoryHeadAtomic = Bool()
  val memoryIssueLoad = Bool()
  val memoryIssueStore = Bool()
  val memoryIssueAtomic = Bool()

  val memoryHeadLsuBusy = Bool()
  val memoryHeadPtwActive = Bool()

  val readyYoungerLoad = Bool()
  val readyYoungerLoadAge1 = Bool()
  val readyYoungerLoadAge2 = Bool()
  val readyYoungerLoadAge3 = Bool()
  val readyYoungerLoadLsuIdle = Bool()

  // Candidate load whose older live instructions are only ordinary Integer /
  // MulDiv work. Completed older entries are allowed. Branch, Memory, System,
  // non-Normal ordering and decoded exceptions close this frontier.
  val readyYoungerLoadComputeFrontier = Bool()
  val readyYoungerLoadComputeFrontierLsuIdle = Bool()

  // Candidate load behind a live Memory head, with only completed or ordinary
  // compute entries between head and candidate. This measures demand for a
  // future second memory transaction without pretending an LSQ exists today.
  val readyYoungerLoadBehindMemoryHead = Bool()
  val readyYoungerLoadBehindMemoryHeadLsuBusy = Bool()
}

class V2MemoryDecompositionCounters extends Bundle {
  val memoryHeadLoad = UInt(64.W)
  val memoryHeadStore = UInt(64.W)
  val memoryHeadAtomic = UInt(64.W)
  val memoryIssueLoad = UInt(64.W)
  val memoryIssueStore = UInt(64.W)
  val memoryIssueAtomic = UInt(64.W)

  val memoryHeadLsuBusy = UInt(64.W)
  val memoryHeadPtwActive = UInt(64.W)

  val readyYoungerLoad = UInt(64.W)
  val readyYoungerLoadAge1 = UInt(64.W)
  val readyYoungerLoadAge2 = UInt(64.W)
  val readyYoungerLoadAge3 = UInt(64.W)
  val readyYoungerLoadLsuIdle = UInt(64.W)
  val readyYoungerLoadComputeFrontier = UInt(64.W)
  val readyYoungerLoadComputeFrontierLsuIdle = UInt(64.W)
  val readyYoungerLoadBehindMemoryHead = UInt(64.W)
  val readyYoungerLoadBehindMemoryHeadLsuBusy = UInt(64.W)
}

/** Pure predicate layer so focused tests can freeze what an "opportunity" means
  * without instantiating Linux, OpenSBI, the LSU, or any architectural owner.
  */
class V2MemoryDecompositionProbe(val xlen: Int = 64, val entries: Int = 4) extends Module {
  require(xlen == 32 || xlen == 64)
  require(entries == 4, s"first memory decomposition probe freezes the ROB4 window, got $entries")

  val io = IO(new Bundle {
    val window = Input(Vec(entries, new TinySchedulingEntry(xlen)))
    val lsuBusy = Input(Bool())
    val ptwActive = Input(Bool())
    val memoryIssue = Input(Bool())
    val memoryIssueKind = Input(MemoryOperationKind())
    val events = Output(new V2MemoryDecompositionEvents)
  })

  private def live(entry: TinySchedulingEntry): Bool = entry.valid && !entry.complete
  private def isMemory(entry: TinySchedulingEntry): Bool =
    entry.uop.executionClass === ExecutionClass.Memory
  private def readyNormalLoad(entry: TinySchedulingEntry): Bool =
    live(entry) && isMemory(entry) &&
      entry.uop.decoded.memory.kind === MemoryOperationKind.Load &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      !entry.uop.decoded.exception.valid &&
      entry.dependenciesValid && entry.operandsReady

  // A completed entry no longer blocks an exposure probe. An incomplete older
  // entry is admitted only when it is side-effect-free ordinary compute.
  private def completedOrOrdinaryCompute(entry: TinySchedulingEntry): Bool = {
    val ordinaryCompute =
      (entry.uop.executionClass === ExecutionClass.Integer ||
        entry.uop.executionClass === ExecutionClass.MulDiv) &&
        entry.uop.decoded.ordering === OrderingClass.Normal &&
        !entry.uop.decoded.exception.valid
    !entry.valid || entry.complete || ordinaryCompute
  }

  private val head = io.window(0)
  private val headMemory = live(head) && isMemory(head)
  private val headKind = head.uop.decoded.memory.kind

  io.events := 0.U.asTypeOf(new V2MemoryDecompositionEvents)
  io.events.memoryHeadLoad := headMemory && headKind === MemoryOperationKind.Load
  io.events.memoryHeadStore := headMemory && headKind === MemoryOperationKind.Store
  io.events.memoryHeadAtomic := headMemory && headKind === MemoryOperationKind.Atomic
  io.events.memoryIssueLoad := io.memoryIssue && io.memoryIssueKind === MemoryOperationKind.Load
  io.events.memoryIssueStore := io.memoryIssue && io.memoryIssueKind === MemoryOperationKind.Store
  io.events.memoryIssueAtomic := io.memoryIssue && io.memoryIssueKind === MemoryOperationKind.Atomic
  io.events.memoryHeadLsuBusy := headMemory && io.lsuBusy
  io.events.memoryHeadPtwActive := headMemory && io.ptwActive

  private val youngerReady = Seq.tabulate(entries - 1) { offset =>
    readyNormalLoad(io.window(offset + 1))
  }
  io.events.readyYoungerLoadAge1 := youngerReady(0)
  io.events.readyYoungerLoadAge2 := youngerReady(1)
  io.events.readyYoungerLoadAge3 := youngerReady(2)
  io.events.readyYoungerLoad := youngerReady.reduce(_ || _)
  io.events.readyYoungerLoadLsuIdle := io.events.readyYoungerLoad && !io.lsuBusy

  private val computeFrontierCandidates = (1 until entries).map { age =>
    val olderSafe = (0 until age).map(index => completedOrOrdinaryCompute(io.window(index))).reduce(_ && _)
    readyNormalLoad(io.window(age)) && olderSafe
  }
  io.events.readyYoungerLoadComputeFrontier := computeFrontierCandidates.reduce(_ || _)
  io.events.readyYoungerLoadComputeFrontierLsuIdle :=
    io.events.readyYoungerLoadComputeFrontier && !io.lsuBusy

  private val behindMemoryHeadCandidates = (1 until entries).map { age =>
    val interveningSafe = if (age == 1) true.B else
      (1 until age).map(index => completedOrOrdinaryCompute(io.window(index))).reduce(_ && _)
    headMemory && readyNormalLoad(io.window(age)) && interveningSafe
  }
  io.events.readyYoungerLoadBehindMemoryHead := behindMemoryHeadCandidates.reduce(_ || _)
  io.events.readyYoungerLoadBehindMemoryHeadLsuBusy :=
    io.events.readyYoungerLoadBehindMemoryHead && io.lsuBusy
}

/** Independent accumulator for the measurement-only memory decomposition. */
class V2MemoryDecompositionCounterBank extends Module {
  val io = IO(new Bundle {
    val events = Input(new V2MemoryDecompositionEvents)
    val counters = Output(new V2MemoryDecompositionCounters)
  })

  private def count(event: Bool): UInt = {
    val value = RegInit(0.U(64.W))
    when(event) { value := value + 1.U }
    value
  }

  io.counters.memoryHeadLoad := count(io.events.memoryHeadLoad)
  io.counters.memoryHeadStore := count(io.events.memoryHeadStore)
  io.counters.memoryHeadAtomic := count(io.events.memoryHeadAtomic)
  io.counters.memoryIssueLoad := count(io.events.memoryIssueLoad)
  io.counters.memoryIssueStore := count(io.events.memoryIssueStore)
  io.counters.memoryIssueAtomic := count(io.events.memoryIssueAtomic)
  io.counters.memoryHeadLsuBusy := count(io.events.memoryHeadLsuBusy)
  io.counters.memoryHeadPtwActive := count(io.events.memoryHeadPtwActive)
  io.counters.readyYoungerLoad := count(io.events.readyYoungerLoad)
  io.counters.readyYoungerLoadAge1 := count(io.events.readyYoungerLoadAge1)
  io.counters.readyYoungerLoadAge2 := count(io.events.readyYoungerLoadAge2)
  io.counters.readyYoungerLoadAge3 := count(io.events.readyYoungerLoadAge3)
  io.counters.readyYoungerLoadLsuIdle := count(io.events.readyYoungerLoadLsuIdle)
  io.counters.readyYoungerLoadComputeFrontier := count(io.events.readyYoungerLoadComputeFrontier)
  io.counters.readyYoungerLoadComputeFrontierLsuIdle := count(io.events.readyYoungerLoadComputeFrontierLsuIdle)
  io.counters.readyYoungerLoadBehindMemoryHead := count(io.events.readyYoungerLoadBehindMemoryHead)
  io.counters.readyYoungerLoadBehindMemoryHeadLsuBusy := count(io.events.readyYoungerLoadBehindMemoryHeadLsuBusy)
}

/**
  * Stacked measurement top on the frozen #161 host-visible P8 top.
  *
  * All new paths are read-only probes and top-level simulation outputs. No
  * signal feeds back into dispatch, issue, execution, LSU, Commit, translation,
  * PMP/PMA, or the platform memory owner.
  */
class AetherCoreV2MemoryDecompositionTop extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisible {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val probe = Module(new V2MemoryDecompositionProbe(64, 4))
  private val counters = Module(new V2MemoryDecompositionCounterBank)

  private val window = BoringUtils.tapAndRead(core.backend.dependencyBackend.io.schedulingWindow)
  private val lsuRequestValid = BoringUtils.tapAndRead(core.backend.lsu.io.request.valid)
  private val lsuRequestReady = BoringUtils.tapAndRead(core.backend.lsu.io.request.ready)
  private val lsuRequestBits = BoringUtils.tapAndRead(core.backend.lsu.io.request.bits)

  probe.io.window := window
  probe.io.lsuBusy := core.io.lsuBusy
  probe.io.ptwActive := io.ptwValid
  probe.io.memoryIssue := lsuRequestValid && lsuRequestReady
  probe.io.memoryIssueKind := lsuRequestBits.kind
  counters.io.events := probe.io.events

  val ioMemHeadLoad = IO(Output(UInt(64.W)))
  val ioMemHeadStore = IO(Output(UInt(64.W)))
  val ioMemHeadAtomic = IO(Output(UInt(64.W)))
  val ioMemIssueLoad = IO(Output(UInt(64.W)))
  val ioMemIssueStore = IO(Output(UInt(64.W)))
  val ioMemIssueAtomic = IO(Output(UInt(64.W)))
  val ioMemHeadLsuBusy = IO(Output(UInt(64.W)))
  val ioMemHeadPtwActive = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoad = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoadAge1 = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoadAge2 = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoadAge3 = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoadLsuIdle = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoadComputeFrontier = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoadComputeFrontierLsuIdle = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoadBehindMemoryHead = IO(Output(UInt(64.W)))
  val ioReadyYoungerLoadBehindMemoryHeadLsuBusy = IO(Output(UInt(64.W)))

  ioMemHeadLoad := counters.io.counters.memoryHeadLoad
  ioMemHeadStore := counters.io.counters.memoryHeadStore
  ioMemHeadAtomic := counters.io.counters.memoryHeadAtomic
  ioMemIssueLoad := counters.io.counters.memoryIssueLoad
  ioMemIssueStore := counters.io.counters.memoryIssueStore
  ioMemIssueAtomic := counters.io.counters.memoryIssueAtomic
  ioMemHeadLsuBusy := counters.io.counters.memoryHeadLsuBusy
  ioMemHeadPtwActive := counters.io.counters.memoryHeadPtwActive
  ioReadyYoungerLoad := counters.io.counters.readyYoungerLoad
  ioReadyYoungerLoadAge1 := counters.io.counters.readyYoungerLoadAge1
  ioReadyYoungerLoadAge2 := counters.io.counters.readyYoungerLoadAge2
  ioReadyYoungerLoadAge3 := counters.io.counters.readyYoungerLoadAge3
  ioReadyYoungerLoadLsuIdle := counters.io.counters.readyYoungerLoadLsuIdle
  ioReadyYoungerLoadComputeFrontier := counters.io.counters.readyYoungerLoadComputeFrontier
  ioReadyYoungerLoadComputeFrontierLsuIdle := counters.io.counters.readyYoungerLoadComputeFrontierLsuIdle
  ioReadyYoungerLoadBehindMemoryHead := counters.io.counters.readyYoungerLoadBehindMemoryHead
  ioReadyYoungerLoadBehindMemoryHeadLsuBusy := counters.io.counters.readyYoungerLoadBehindMemoryHeadLsuBusy
}
