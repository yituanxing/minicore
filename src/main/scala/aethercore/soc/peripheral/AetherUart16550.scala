package aethercore.soc.peripheral

import chisel3._
import chisel3.util._

object AetherUart16550Map {
  val Data: Int = 0
  val InterruptEnable: Int = 1
  val InterruptIdentification: Int = 2
  val LineControl: Int = 3
  val ModemControl: Int = 4
  val LineStatus: Int = 5
  val Scratch: Int = 7
}

/**
  * Small ns16550-compatible UART peripheral used by AetherSoC.
  *
  * This module owns UART register/FIFO/interrupt state only. Address decoding
  * of the UART region remains a SoC-fabric responsibility; the peripheral sees
  * a 3-bit register offset for a transaction already selected by the parent.
  *
  * complete is the exact terminal memory-response acceptance pulse. State
  * changes, RX pops and TX bytes happen only on that pulse, preserving the
  * qualified Linux shell's historical transaction lifetime while moving UART
  * ownership out of the platform wrapper.
  */
class AetherUart16550(
    val dataBits: Int = 64,
    val rxDepth: Int = 16,
    val resetDivisor: Int = 1
) extends Module {
  require(dataBits >= 32 && dataBits % 8 == 0)
  require(rxDepth > 0)
  require(resetDivisor >= 1 && resetDivisor <= 0xffff)

  val io = IO(new Bundle {
    val request = Input(Bool())
    val write = Input(Bool())
    val offset = Input(UInt(3.W))
    val wdata = Input(UInt(dataBits.W))
    val wmask = Input(UInt((dataBits / 8).W))
    val complete = Input(Bool())

    val ready = Output(Bool())
    val rdata = Output(UInt(dataBits.W))
    val fault = Output(Bool())

    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())

    val txValid = Output(Bool())
    val txByte = Output(UInt(8.W))
    // Physical serializer readiness. A TX data-register write remains
    // backpressured until the downstream PHY can accept the byte.
    val txReady = Input(Bool())
    // Live ns16550 divisor consumed by the physical serializer/deserializer.
    val baudDivisor = Output(UInt(16.W))

    val interrupt = Output(Bool())
    val rxInterrupt = Output(Bool())
  })

  // AetherSoC v0 physical PHY is 8N1; expose the same reset framing in LCR
  // so software-visible state and the pin-level serializer agree from reset.
  private val lcr = RegInit("h03".U(8.W))
  private val ier = RegInit(0.U(8.W))
  private val dll = RegInit((resetDivisor & 0xff).U(8.W))
  private val dlm = RegInit(((resetDivisor >> 8) & 0xff).U(8.W))
  private val mcr = RegInit(0.U(8.W))
  private val scr = RegInit(0.U(8.W))
  private val dlab = lcr(7)
  io.baudDivisor := Cat(dlm, dll)

  private val rx = Module(new Queue(UInt(8.W), rxDepth))
  rx.io.enq.valid := io.rxValid
  rx.io.enq.bits := io.rxByte
  io.rxReady := rx.io.enq.ready

  private val rxAvailable = rx.io.deq.valid
  private val rxByte = rx.io.deq.bits
  private val rxInterrupt = ier(0) && rxAvailable
  private val txHoldingEmpty = io.txReady
  private val threInterrupt = ier(1) && txHoldingEmpty
  private val combinedInterrupt = rxInterrupt || threInterrupt

  io.rxInterrupt := rxInterrupt
  io.interrupt := combinedInterrupt

  private val txDataWrite =
    io.request && io.write &&
      io.offset === AetherUart16550Map.Data.U && !dlab
  io.ready := !txDataWrite || io.txReady
  io.fault := false.B

  val readData = WireDefault(0.U(dataBits.W))
  switch(io.offset) {
    is(AetherUart16550Map.Data.U) {
      readData := Mux(dlab, dll, rxByte).pad(dataBits)
    }
    is(AetherUart16550Map.InterruptEnable.U) {
      readData := Mux(dlab, dlm, ier).pad(dataBits)
    }
    is(AetherUart16550Map.InterruptIdentification.U) {
      readData := Mux(
        rxInterrupt,
        4.U,
        Mux(threInterrupt, 2.U, 1.U)
      ).pad(dataBits)
    }
    is(AetherUart16550Map.LineControl.U) {
      readData := lcr.pad(dataBits)
    }
    is(AetherUart16550Map.ModemControl.U) {
      readData := mcr.pad(dataBits)
    }
    is(AetherUart16550Map.LineStatus.U) {
      val transmitterStatus = Mux(txHoldingEmpty, "h60".U(8.W), 0.U(8.W))
      readData := (transmitterStatus | rxAvailable.asUInt).pad(dataBits)
    }
    is(AetherUart16550Map.Scratch.U) {
      readData := scr.pad(dataBits)
    }
  }
  io.rdata := readData

  private val terminalFire = io.request && io.complete && io.ready
  private val readDataFire =
    terminalFire && !io.write && io.offset === AetherUart16550Map.Data.U && !dlab
  rx.io.deq.ready := readDataFire

  when(terminalFire && io.write) {
    switch(io.offset) {
      is(AetherUart16550Map.Data.U) {
        when(dlab) {
          dll := io.wdata(7, 0)
        }
      }
      is(AetherUart16550Map.InterruptEnable.U) {
        when(dlab) {
          dlm := io.wdata(7, 0)
        }.otherwise {
          ier := io.wdata(7, 0)
        }
      }
      is(AetherUart16550Map.LineControl.U) {
        lcr := io.wdata(7, 0)
      }
      is(AetherUart16550Map.ModemControl.U) {
        mcr := io.wdata(7, 0)
      }
      is(AetherUart16550Map.Scratch.U) {
        scr := io.wdata(7, 0)
      }
    }
  }

  io.txValid :=
    terminalFire && io.write &&
      io.offset === AetherUart16550Map.Data.U && !dlab
  io.txByte := io.wdata(7, 0)
}
