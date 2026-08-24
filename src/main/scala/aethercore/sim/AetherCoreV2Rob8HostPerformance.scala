package aethercore.sim

import chisel3._

/**
  * ROB8-only extension of the qualified P8 host-visible simulation shell.
  *
  * The parent already owns the frozen P8 counter bank and exports rob0..rob4.
  * This wrapper adds only the four occupancy buckets that become reachable when
  * TinyRobGeometry grows to eight entries. Production core/backend interfaces
  * remain untouched, and none of these observation-only counters feed back into
  * architectural behavior.
  */
class AetherCoreV2Rob8MeasuredOpenSbiRV64SimTopHostVisible
    extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisible {

  private val rob5 = RegInit(0.U(64.W))
  private val rob6 = RegInit(0.U(64.W))
  private val rob7 = RegInit(0.U(64.W))
  private val rob8 = RegInit(0.U(64.W))

  when(core.io.occupancy === 5.U) { rob5 := rob5 + 1.U }
  when(core.io.occupancy === 6.U) { rob6 := rob6 + 1.U }
  when(core.io.occupancy === 7.U) { rob7 := rob7 + 1.U }
  when(core.io.occupancy === 8.U) { rob8 := rob8 + 1.U }

  val ioPerfRob5 = IO(Output(UInt(64.W)))
  val ioPerfRob6 = IO(Output(UInt(64.W)))
  val ioPerfRob7 = IO(Output(UInt(64.W)))
  val ioPerfRob8 = IO(Output(UInt(64.W)))

  ioPerfRob5 := rob5
  ioPerfRob6 := rob6
  ioPerfRob7 := rob7
  ioPerfRob8 := rob8
}
