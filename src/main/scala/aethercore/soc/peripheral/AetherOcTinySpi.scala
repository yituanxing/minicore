package aethercore.soc.peripheral

import chisel3._
import chisel3.util._

/** OpenCores tiny_spi-compatible, CPU-driven SPI master.
  *
  * Register and double-buffer semantics follow Thomas Chou's tiny_spi core
  * (Copyright (C) 2010 Authors, LGPL-2.1-or-later):
  *   0 RX shift register, 4 buffered TX/RX, 8 TXR/TXE and IRQ enables,
  *  12 mode (CPOL/CPHA), 16 half-period divider.
  *
  * The original project's warranty disclaimer applies: this implementation
  * is provided without warranty of merchantability or fitness for any purpose.
  * The chip select intentionally belongs to a separate GPIO controller, as
  * required by the upstream Linux spi-oc-tiny driver. There is no DMA master.
  */
object AetherOcTinySpiMap {
  val RxData = 0
  val TxData = 4
  val Status = 8
  val Control = 12
  val Baud = 16
}

class AetherOcTinySpi(
    val dataBits: Int = 64,
    val baudWidth: Int = 8,
    val clockFrequencyHz: Long = 50_000_000L
) extends Module {
  require(dataBits == 64)
  require(baudWidth >= 1 && baudWidth <= 16)
  // This is the clock driven into the Chisel Module, not an internal PLL.
  // A board with a different clock must select matching boardDevices *and*
  // emit its DTB from the exact same frequency contract.
  require(clockFrequencyHz > 0)

  val io = IO(new Bundle {
    val request = Input(Bool())
    val write = Input(Bool())
    val offset = Input(UInt(5.W))
    val wdata = Input(UInt(dataBits.W))
    val complete = Input(Bool())
    val ready = Output(Bool())
    val rdata = Output(UInt(dataBits.W))
    val fault = Output(Bool())
    val interrupt = Output(Bool())
    val sclk = Output(Bool())
    val mosi = Output(Bool())
    val miso = Input(Bool())
  })

  val idle :: phase2 :: phase1 :: Nil = Enum(3)
  val phase = RegInit(idle)
  val shift = RegInit(0.U(8.W))
  val buffer = RegInit(0.U(8.W))
  val buffered = RegInit(false.B)
  val bitsRemaining = RegInit(0.U(3.W))
  val halfPeriod = RegInit(0.U(baudWidth.W))
  val countdown = RegInit(0.U(baudWidth.W))
  val mode = RegInit(0.U(2.W))
  val irqEnable = RegInit(0.U(2.W))
  val sampledMiso = RegInit(false.B)

  val writeFire = io.request && io.write && io.complete
  val writeTx = writeFire && io.offset === AetherOcTinySpiMap.TxData.U
  val writeStatus = writeFire && io.offset === AetherOcTinySpiMap.Status.U
  val writeMode = writeFire && io.offset === AetherOcTinySpiMap.Control.U
  val writeBaud = writeFire && io.offset === AetherOcTinySpiMap.Baud.U

  // The shift register and TX buffer swap at the beginning of a byte and
  // immediately after its final received bit. Simultaneous buffer writes
  // take precedence so Linux may queue a second byte without waiting.
  val shiftingBit = phase === phase1 && countdown === 0.U
  // phase2 outputs CPOL XOR CPHA; phase1 outputs its inverse. Therefore the
  // phase2 -> phase1 transition is the sampling edge in *both* SPI phases:
  // leading for CPHA=0, trailing for CPHA=1. The phase1 -> phase2 transition
  // subsequently advances the output shift register. Sampling on phase1
  // completion would read at the wrong edge, especially for SD SPI mode 0.
  when(phase === phase2 && countdown === 0.U) {
    sampledMiso := io.miso
  }
  val loaded = (phase === idle && buffered) ||
    (shiftingBit && bitsRemaining === 0.U && buffered)
  val receivedShift = Cat(shift(6, 0), sampledMiso)

  val nextPhase = WireDefault(phase)
  val nextCount = WireDefault(halfPeriod)
  val nextBits = WireDefault(bitsRemaining)
  switch(phase) {
    is(idle) {
      when(buffered) {
        nextPhase := phase2
        nextBits := 7.U
      }
    }
    is(phase2) {
      when(countdown === 0.U) { nextPhase := phase1 }
        .otherwise { nextCount := countdown - 1.U }
    }
    is(phase1) {
      when(countdown === 0.U) {
        nextBits := bitsRemaining - 1.U
        when(bitsRemaining === 0.U) {
          when(buffered) {
            nextBits := 7.U
            nextPhase := phase2
          }.otherwise {
            nextPhase := idle
          }
        }.otherwise { nextPhase := phase2 }
      }.otherwise { nextCount := countdown - 1.U }
    }
  }
  phase := nextPhase
  countdown := nextCount
  bitsRemaining := nextBits

  when(writeMode) { mode := io.wdata(1, 0) }
  when(writeStatus) { irqEnable := io.wdata(1, 0) }
  when(writeBaud) { halfPeriod := io.wdata(baudWidth - 1, 0) }

  when(loaded) { shift := buffer }
    .elsewhen(shiftingBit) { shift := receivedShift }
  when(writeTx) { buffer := io.wdata(7, 0) }
    .elsewhen(loaded) { buffer := Mux(phase === idle, shift, receivedShift) }
  when(writeTx) { buffered := true.B }
    .elsewhen(loaded) { buffered := false.B }

  val txReady = !buffered
  val txEmpty = phase === idle
  io.interrupt := (txReady && irqEnable(1)) || (txEmpty && irqEnable(0))
  io.sclk := Mux(phase === idle, mode(1),
    Mux(phase === phase2, mode(1) ^ mode(0), !(mode(1) ^ mode(0))))
  io.mosi := shift(7)
  io.ready := true.B
  io.fault := false.B
  io.rdata := MuxLookup(io.offset, 0.U(dataBits.W))(
    Seq(
      AetherOcTinySpiMap.RxData.U -> shift.pad(dataBits),
      AetherOcTinySpiMap.TxData.U -> buffer.pad(dataBits),
      AetherOcTinySpiMap.Status.U -> Cat(txReady, txEmpty).pad(dataBits)
    ))
}
