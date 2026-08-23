package aethercore.sim

import chisel3._

/**
  * Experiment-only exact ROB occupancy histogram for the P8 ROB8 A/B.
  *
  * Keep this separate from the frozen P8.0 counter bank until the geometry is
  * promoted. It observes the production occupancy output only and cannot feed
  * any signal back into dispatch, issue, completion or Commit.
  */
class V2Rob8OccupancyCounterBank extends Module {
  val io = IO(new Bundle {
    val occupancy = Input(UInt(4.W))
    val counters = Output(Vec(9, UInt(64.W)))
  })

  for (level <- 0 to 8) {
    val counter = RegInit(0.U(64.W))
    when(io.occupancy === level.U) {
      counter := counter + 1.U
    }
    io.counters(level) := counter
  }

  assert(io.occupancy <= 8.U, "ROB8 experiment observed occupancy above eight entries")
}

/**
  * Host-visible P8 top plus exact ROB8 occupancy evidence.
  *
  * All existing P8 counters remain available from the parent top. The extra
  * outputs are experiment-only and allow the bounded A/B to distinguish a true
  * ROB8-full stall from time spent at occupancies 5..7.
  */
class AetherCoreV2Rob8ExperimentTop extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisible {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val occupancy = Module(new V2Rob8OccupancyCounterBank)
  occupancy.io.occupancy := core.io.occupancy

  val ioPerfRobExact0 = IO(Output(UInt(64.W)))
  val ioPerfRobExact1 = IO(Output(UInt(64.W)))
  val ioPerfRobExact2 = IO(Output(UInt(64.W)))
  val ioPerfRobExact3 = IO(Output(UInt(64.W)))
  val ioPerfRobExact4 = IO(Output(UInt(64.W)))
  val ioPerfRobExact5 = IO(Output(UInt(64.W)))
  val ioPerfRobExact6 = IO(Output(UInt(64.W)))
  val ioPerfRobExact7 = IO(Output(UInt(64.W)))
  val ioPerfRobExact8 = IO(Output(UInt(64.W)))

  ioPerfRobExact0 := occupancy.io.counters(0)
  ioPerfRobExact1 := occupancy.io.counters(1)
  ioPerfRobExact2 := occupancy.io.counters(2)
  ioPerfRobExact3 := occupancy.io.counters(3)
  ioPerfRobExact4 := occupancy.io.counters(4)
  ioPerfRobExact5 := occupancy.io.counters(5)
  ioPerfRobExact6 := occupancy.io.counters(6)
  ioPerfRobExact7 := occupancy.io.counters(7)
  ioPerfRobExact8 := occupancy.io.counters(8)
}
