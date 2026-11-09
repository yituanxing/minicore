package aethercore.core

import chisel3._
import chisel3.util._

/** Experimental live-CSR PMP geometry cache for FPGA area/timing A/B.
  *
  * On reset, or after a one-cycle `refresh` pulse, exactly `entries` cycles
  * rebuild the range table using a single NAPOT/TOR geometry lane. While
  * `busy`, ranges must not be consumed by any fetch/PTW access. CSR inputs
  * must remain stable throughout refresh. This module is deliberately *not*
  * connected to the Product SoC: front-end/PTW stalls and CSR visibility
  * ownership must be proved before integration. Data-side PMP snapshots
  * continue to require their independent combinational decoder.
  */
class PmpSerialRangePredecoder(
    val xlen: Int,
    val entries: Int = PmpConstants.MaxEntries,
    val paddrBits: Int
) extends Module {
  require(entries >= 2 && entries <= PmpConstants.MaxEntries)
  private val geometry = PmpGeometry(xlen, paddrBits)
  private val pmpAddressBits = geometry.encodedAddressBits
  private val indexBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val config = Input(Vec(entries, UInt(8.W)))
    val pmpAddress = Input(Vec(entries, UInt(pmpAddressBits.W)))
    val refresh = Input(Bool())
    val busy = Output(Bool())
    val ready = Output(Bool())
    val ranges = Output(Vec(entries, new PmpDecodedEntry(paddrBits)))
  })

  // A decoded entry is only architecturally visible after the *whole* table
  // is refreshed. Do not reset the data RAM/FF payload, only the ready bit.
  private val cached = Reg(Vec(entries, new PmpDecodedEntry(paddrBits)))
  private val index = RegInit(0.U(indexBits.W))
  private val refreshing = RegInit(true.B)
  private val initialized = RegInit(false.B)

  // The second lane of a two-entry reference decoder decodes any entry i;
  // its predecessor address is provided as lane zero for TOR's lower bound.
  // Mode OFF and all-ones NAPOT obey the same exact reference implementation.
  private val geometryLane = Module(new PmpRangeDecoder(xlen, 2, paddrBits))
  private val priorAddresses = VecInit(
    Seq(0.U(pmpAddressBits.W)) ++ (0 until entries - 1).map(io.pmpAddress(_)))
  geometryLane.io.config(0) := 0.U
  geometryLane.io.pmpAddress(0) := priorAddresses(index)
  geometryLane.io.config(1) := io.config(index)
  geometryLane.io.pmpAddress(1) := io.pmpAddress(index)

  io.busy := refreshing || io.refresh
  io.ready := initialized && !io.busy
  io.ranges := cached

  when(io.refresh) {
    assert(!refreshing, "PMP CSR changed during serial range refresh")
    refreshing := true.B
    initialized := false.B
    index := 0.U
  }.elsewhen(refreshing) {
    cached(index) := geometryLane.io.ranges(1)
    when(index === (entries - 1).U) {
      refreshing := false.B
      initialized := true.B
    }.otherwise {
      index := index + 1.U
    }
  }
}
