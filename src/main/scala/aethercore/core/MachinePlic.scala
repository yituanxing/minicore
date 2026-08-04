package aethercore.core

import chisel3._
import chisel3.util._

/** Minimal single-hart Machine-mode PLIC foundation.
  *
  * Source zero is permanently reserved. Sources 1..sourceCount are edge-latched
  * into pending bits. The claim result is the enabled pending source with the
  * greatest priority strictly above the hart threshold; ties select the lower
  * source ID. A claim pulse consumes exactly the returned pending bit.
  *
  * This block intentionally provides the arbitration/gateway contract only. It
  * is not advertised as a complete platform PLIC until its MMIO register file,
  * core MEIP wiring, UART source and FreeRTOS ISR path are integrated.
  */
class MachinePlic(val sourceCount: Int = 8, val priorityBits: Int = 3) extends Module {
  require(sourceCount >= 1)
  require(priorityBits >= 1)

  private val sourceIdBits = log2Ceil(sourceCount + 1)

  val io = IO(new Bundle {
    val sources = Input(UInt(sourceCount.W))
    val enable = Input(UInt(sourceCount.W))
    val priorities = Input(Vec(sourceCount, UInt(priorityBits.W)))
    val threshold = Input(UInt(priorityBits.W))

    val claim = Input(Bool())
    val complete = Input(Valid(UInt(sourceIdBits.W)))

    val pending = Output(UInt(sourceCount.W))
    val interrupt = Output(Bool())
    val claimId = Output(UInt(sourceIdBits.W))
  })

  val previousSources = RegNext(io.sources, 0.U)
  val risingSources = io.sources & ~previousSources
  val pending = RegInit(0.U(sourceCount.W))

  val selectedId = WireDefault(0.U(sourceIdBits.W))
  val selectedPriority = WireDefault(0.U(priorityBits.W))

  // Iterate from high to low IDs so an equal-priority lower ID overwrites the
  // previous selection and therefore wins the architectural tie break.
  for (index <- (sourceCount - 1) to 0 by -1) {
    val sourceId = index + 1
    val eligible = pending(index) && io.enable(index) &&
      io.priorities(index) > io.threshold
    when(eligible && io.priorities(index) >= selectedPriority) {
      selectedId := sourceId.U
      selectedPriority := io.priorities(index)
    }
  }

  val claimedMask = Mux(
    io.claim && selectedId =/= 0.U,
    UIntToOH(selectedId - 1.U, sourceCount),
    0.U(sourceCount.W)
  )

  // Newly observed edges win over a simultaneous claim clear, preventing a
  // source transition at the claim boundary from being lost.
  pending := (pending & ~claimedMask) | risingSources

  io.pending := pending
  io.claimId := selectedId
  io.interrupt := selectedId =/= 0.U

  // Completion is part of the externally visible protocol even though an
  // edge-latched gateway needs no additional state in this first slice.
  dontTouch(io.complete)
}
