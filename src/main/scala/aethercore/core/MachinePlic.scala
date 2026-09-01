package aethercore.core

import chisel3._
import chisel3.util._

/** Deterministic single-context Platform-Level Interrupt Controller foundation.
  *
  * Source IDs are one-based; ID zero is permanently reserved for "no
  * interrupt". Inputs are level-sensitive. Claiming a source marks it in
  * service so it cannot be claimed again until completion. If the source level
  * remains asserted after completion, it immediately becomes pending again.
  *
  * Arbitration/state is kept independent from the MMIO register map so direct
  * module tests and bus-facing integration share exactly the same claim and
  * completion semantics.
  */
class MachinePlic(
    val sourceCount: Int = 8,
    val priorityBits: Int = 3,
    val implementedSourceMask: Option[BigInt] = None
) extends Module {
  require(sourceCount > 0, s"PLIC must expose at least one source, got $sourceCount")
  require(priorityBits > 0, s"PLIC priority width must be positive, got $priorityBits")

  private val activeSourceMask =
    implementedSourceMask.getOrElse((BigInt(1) << sourceCount) - 1)
  require(activeSourceMask >= 0 && (activeSourceMask >> sourceCount) == 0,
    s"PLIC implemented-source mask 0x${activeSourceMask.toString(16)} exceeds sourceCount=$sourceCount")

  private val sourceIdBits = log2Ceil(sourceCount + 1)
  private def sourceImplemented(index: Int): Boolean =
    ((activeSourceMask >> index) & 1) != 0

  val io = IO(new Bundle {
    val sources = Input(UInt(sourceCount.W))

    val priorityWriteEnable = Input(Bool())
    val priorityWriteId = Input(UInt(sourceIdBits.W))
    val priorityWriteData = Input(UInt(priorityBits.W))

    val enableWrite = Input(Bool())
    val enableWriteData = Input(UInt(sourceCount.W))

    val thresholdWrite = Input(Bool())
    val thresholdWriteData = Input(UInt(priorityBits.W))

    val claimRead = Input(Bool())
    val claim = Output(UInt(sourceIdBits.W))

    val completeWrite = Input(Bool())
    val completeId = Input(UInt(sourceIdBits.W))

    val interrupt = Output(Bool())
    val pending = Output(UInt(sourceCount.W))
    val priorities = Output(Vec(sourceCount, UInt(priorityBits.W)))
    val enabled = Output(UInt(sourceCount.W))
    val threshold = Output(UInt(priorityBits.W))
    val inService = Output(UInt(sourceCount.W))
  })

  val priorities = Wire(Vec(sourceCount, UInt(priorityBits.W)))
  for (index <- 0 until sourceCount) {
    if (sourceImplemented(index)) {
      val priority = RegInit(0.U(priorityBits.W))
      when(io.priorityWriteEnable && io.priorityWriteId === (index + 1).U) {
        priority := io.priorityWriteData
      }
      priorities(index) := priority
    } else {
      priorities(index) := 0.U
    }
  }

  val enabled = RegInit(0.U(sourceCount.W))
  val threshold = RegInit(0.U(priorityBits.W))
  val inService = RegInit(0.U(sourceCount.W))
  private val activeMask = activeSourceMask.U(sourceCount.W)

  val pending = io.sources & activeMask & ~inService

  var selectedId = 0.U(sourceIdBits.W)
  var selectedPriority = 0.U(priorityBits.W)
  for (index <- 0 until sourceCount) {
    if (sourceImplemented(index)) {
      val priority = priorities(index)
      val eligible = pending(index) && enabled(index) &&
        priority =/= 0.U && priority > threshold

      // Sources are visited in ascending ID order. Equal priority therefore keeps
      // the earlier (lower numbered) source, matching the PLIC tie-break rule.
      val wins = eligible && priority > selectedPriority
      selectedId = Mux(wins, (index + 1).U(sourceIdBits.W), selectedId)
      selectedPriority = Mux(wins, priority, selectedPriority)
    }
  }

  io.claim := selectedId
  io.interrupt := selectedId =/= 0.U
  io.pending := pending
  io.priorities := priorities
  io.enabled := enabled
  io.threshold := threshold
  io.inService := inService

  when(io.enableWrite) {
    enabled := io.enableWriteData & activeMask
  }

  when(io.thresholdWrite) {
    threshold := io.thresholdWriteData
  }

  val claimMask = WireDefault(0.U(sourceCount.W))
  val completeMask = WireDefault(0.U(sourceCount.W))
  for (index <- 0 until sourceCount) {
    if (sourceImplemented(index)) {
      val sourceId = index + 1
      val sourceMask = (BigInt(1) << index).U(sourceCount.W)
      when(io.claimRead && selectedId === sourceId.U) {
        claimMask := sourceMask
      }
      when(io.completeWrite && io.completeId === sourceId.U) {
        completeMask := sourceMask
      }
    }
  }

  // Completion is applied before a same-cycle claim. Real MMIO accesses are
  // serialized, but this ordering keeps the state deterministic under direct
  // module-level verification as well.
  when(claimMask =/= 0.U || completeMask =/= 0.U) {
    inService := ((inService & ~completeMask) | claimMask) & activeMask
  }
}
