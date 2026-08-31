package aethercore.soc

import chisel3._

/**
  * Production-oriented physical FPGA boundary.
  *
  * The qualified AetherCoreV2FpgaSoC remains the functional implementation.
  * This wrapper intentionally exposes only signals required by a real board:
  * AXI memory, architectural clock-enable inputs, and the serial pins.
  *
  * Simulation/debug observability (CommitTrace, cache counters, interrupt
  * mirrors, exit status, timer snapshots and halt state) is deliberately not
  * propagated to top-level pins.  Keeping those signals unobserved allows FPGA
  * synthesis to prune logic that exists only for host-side attribution without
  * changing any CPU/SoC architectural behavior.
  */
class AetherCoreV2FpgaPhysicalSoC extends Module {
  private val PaddrBits = 56
  private val DataBits = 64
  private val TxnIdBits = 4

  val io = IO(new Bundle {
    val axi = new Axi4MasterIO(PaddrBits, DataBits, TxnIdBits)

    val uartClockTick = Input(Bool())
    val timebaseTick = Input(Bool())
    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
  })

  private val soc = Module(new AetherCoreV2FpgaSoC)

  io.axi.aw.valid := soc.io.axi.aw.valid
  io.axi.aw.bits := soc.io.axi.aw.bits
  soc.io.axi.aw.ready := io.axi.aw.ready

  io.axi.w.valid := soc.io.axi.w.valid
  io.axi.w.bits := soc.io.axi.w.bits
  soc.io.axi.w.ready := io.axi.w.ready

  soc.io.axi.b.valid := io.axi.b.valid
  soc.io.axi.b.bits := io.axi.b.bits
  io.axi.b.ready := soc.io.axi.b.ready

  io.axi.ar.valid := soc.io.axi.ar.valid
  io.axi.ar.bits := soc.io.axi.ar.bits
  soc.io.axi.ar.ready := io.axi.ar.ready

  soc.io.axi.r.valid := io.axi.r.valid
  soc.io.axi.r.bits := io.axi.r.bits
  io.axi.r.ready := soc.io.axi.r.ready

  soc.io.uartClockTick := io.uartClockTick
  soc.io.timebaseTick := io.timebaseTick
  soc.io.serialRx := io.serialRx
  io.serialTx := soc.io.serialTx
}
