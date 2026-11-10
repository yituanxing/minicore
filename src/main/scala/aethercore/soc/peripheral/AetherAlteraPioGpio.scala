package aethercore.soc.peripheral

import chisel3._
import chisel3.util._

/** A one-pin, MMIO Altera PIO-compatible GPIO for spi-oc-tiny chip select.
  *
  * Linux gpio-altera binds `altr,pio-1.0`, reads DATA at byte offset 0,
  * and programs DIR at offset 4. The inactive, undriven pin is pulled high.
  * No GPIO interrupt or DMA path is provided.
  */
class AetherAlteraPioGpio extends Module {
  val io = IO(new Bundle {
    val request = Input(Bool())
    val write = Input(Bool())
    val offset = Input(UInt(4.W))
    val wdata = Input(UInt(32.W))
    val complete = Input(Bool())
    val ready = Output(Bool())
    val rdata = Output(UInt(32.W))
    val fault = Output(Bool())
    val pin = Output(Bool())
  })

  val data = RegInit(1.U(1.W))
  val direction = RegInit(0.U(1.W))
  val readValue = Mux(direction(0), data(0), true.B)
  io.pin := readValue
  io.ready := true.B
  io.fault := false.B
  io.rdata := MuxLookup(io.offset, 0.U(32.W))(
    Seq(0.U -> readValue.asUInt, 4.U -> direction.pad(32)))

  when(io.request && io.write && io.complete) {
    switch(io.offset) {
      is(0.U) { data := io.wdata(0) }
      is(4.U) { direction := io.wdata(0) }
    }
  }
}
