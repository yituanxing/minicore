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
  * This module deliberately separates arbitration/state from the eventual MMIO
  * register map. The next integration slice can place the same state machine
  * behind the AetherCore data bus without changing claim/complete semantics.
  */
class MachinePlic(
    val sourceCount: Int = 8,
    val priorityBits: Int = 3
) extends Module {
  require(sourceCount > 0, s"PLIC must expose at least one source, got $sourceCount")
  require(priorityBits > 0, s"PLIC priority width must be positive, got $priorityBits")

  private val sourceIdBits = log2Ceil(sourceCount + 1)

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
    val enabled = Output(UInt(sourceCount.W))
    val threshold = Output(UInt(priorityBits.W))
    val inService = Output(UInt(sourceCount.W))
  })

  val priorities = RegInit(VecInit(Seq.fill(sourceCount)(0.U(priorityBits.W))))
  val enabled = RegInit(0.U(sourceCount.W))
  val threshold = RegInit(0.U(priorityBits.W))
  val inService = RegInit(0.U(sourceCount.W))

  val pending = io.sources & ~inService

  var selectedId = 0.U(sourceIdBits.W)
  var selectedPriority = 0.U(priorityBits.W)
  for (index <- 0 until sourceCount) {
    val priority = priorities(index)
    val eligible = pending(index) && enabled(index) &&
      priority =/= 0.U && priority > threshold

    // Sources are visited in ascending ID order. Equal priority therefore keeps
    // the earlier (lower numbered) source, matching the PLIC tie-break rule.
    val wins = eligible && priority > selectedPriority
    selectedId = Mux(wins, (index + 1).U(sourceIdBits.W), selectedId)
    selectedPriority = Mux(wins, priority, selectedPriority)
  }

  io.claim := selectedId
  io.interrupt := selectedId =/= 0.U
  io.pending := pending
  io.enabled := enabled
  io.threshold := threshold
  io.inService := inService

  val priorityWriteIdValid = io.priorityWriteId > 0.U &&
    io.priorityWriteId <= sourceCount.U
  when(io.priorityWriteEnable && priorityWriteIdValid) {
    priorities(io.priorityWriteId - 1.U) := io.priorityWriteData
  }

  when(io.enableWrite) {
    enabled := io.enableWriteData
  }

  when(io.thresholdWrite) {
    threshold := io.thresholdWriteData
  }

  val claimMask = WireDefault(0.U(sourceCount.W))
  when(io.claimRead && selectedId =/= 0.U) {
    claimMask := (1.U(sourceCount.W) << (selectedId - 1.U))(sourceCount - 1, 0)
  }

  val completeIdValid = io.completeId > 0.U && io.completeId <= sourceCount.U
  val completeMask = WireDefault(0.U(sourceCount.W))
  when(io.completeWrite && completeIdValid) {
    completeMask := (1.U(sourceCount.W) << (io.completeId - 1.U))(sourceCount - 1, 0)
  }

  // Completion is applied before a same-cycle claim. Real MMIO accesses are
  // serialized, but this ordering keeps the state deterministic under direct
  // module-level verification as well.
  when(claimMask =/= 0.U || completeMask =/= 0.U) {
    inService := (inService & ~completeMask) | claimMask
  }
}
