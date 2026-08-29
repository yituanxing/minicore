package aethercore.soc.phy

import chisel3._
import chisel3.util._

/**
  * Synthesizable 8N1 serial PHY for the AetherSoC ns16550 register block.
  *
  * uartClockTick represents one tick of the software-visible ns16550 input
  * clock declared by the board DTS. One serial bit therefore lasts
  * 16 * baudDivisor ticks, matching the standard 16550 divisor contract.
  *
  * TX owns a one-byte serializer and exposes ready only while idle.
  * RX uses a two-flop input synchronizer, validates the start bit at its
  * midpoint, samples eight data bits LSB-first, validates the stop bit, and
  * holds the recovered byte until the register block accepts it.
  */
class AetherUart8N1Phy extends Module {
  private val DivisorBits = 16
  private val TickCounterBits = DivisorBits + 4

  val io = IO(new Bundle {
    val uartClockTick = Input(Bool())
    val baudDivisor = Input(UInt(DivisorBits.W))

    val txValid = Input(Bool())
    val txByte = Input(UInt(8.W))
    val txReady = Output(Bool())

    val rxValid = Output(Bool())
    val rxByte = Output(UInt(8.W))
    val rxReady = Input(Bool())

    val serialTx = Output(Bool())
    val serialRx = Input(Bool())
  })

  private val effectiveDivisor =
    Mux(io.baudDivisor === 0.U, 1.U(DivisorBits.W), io.baudDivisor)
  private val ticksPerBit = effectiveDivisor << 4
  private val halfBitTicks = effectiveDivisor << 3

  // --------------------------------------------------------------------------
  // TX: start(0), 8 data bits LSB-first, stop(1)
  // --------------------------------------------------------------------------
  private val txActive = RegInit(false.B)
  private val txFrame = RegInit("b1000000001".U(10.W))
  private val txBitIndex = RegInit(0.U(4.W))
  private val txTickCount = RegInit(0.U(TickCounterBits.W))

  io.txReady := !txActive
  io.serialTx := Mux(txActive, txFrame(txBitIndex), true.B)

  when(io.txValid && io.txReady) {
    txActive := true.B
    txFrame := Cat(1.U(1.W), io.txByte, 0.U(1.W))
    txBitIndex := 0.U
    txTickCount := 0.U
  }.elsewhen(txActive && io.uartClockTick) {
    when(txTickCount === ticksPerBit - 1.U) {
      txTickCount := 0.U
      when(txBitIndex === 9.U) {
        txActive := false.B
      }.otherwise {
        txBitIndex := txBitIndex + 1.U
      }
    }.otherwise {
      txTickCount := txTickCount + 1.U
    }
  }

  // --------------------------------------------------------------------------
  // RX: synchronize, validate start midpoint, sample data mid-bit, stop=1.
  // --------------------------------------------------------------------------
  private val rxMeta = RegInit(true.B)
  private val rxSync = RegInit(true.B)
  rxMeta := io.serialRx
  rxSync := rxMeta

  private val sIdle :: sStart :: sData :: sStop :: Nil = Enum(4)
  private val rxState = RegInit(sIdle)
  private val rxTickCount = RegInit(0.U(TickCounterBits.W))
  private val rxBitIndex = RegInit(0.U(3.W))
  private val rxShift = RegInit(0.U(8.W))
  private val rxPendingValid = RegInit(false.B)
  private val rxPendingByte = RegInit(0.U(8.W))

  io.rxValid := rxPendingValid
  io.rxByte := rxPendingByte

  when(rxPendingValid && io.rxReady) {
    rxPendingValid := false.B
  }

  switch(rxState) {
    is(sIdle) {
      rxTickCount := 0.U
      when(!rxPendingValid && !rxSync) {
        rxState := sStart
        rxTickCount := 0.U
        rxBitIndex := 0.U
        rxShift := 0.U
      }
    }

    is(sStart) {
      when(io.uartClockTick) {
        when(rxTickCount === halfBitTicks - 1.U) {
          rxTickCount := 0.U
          when(!rxSync) {
            rxState := sData
          }.otherwise {
            rxState := sIdle
          }
        }.otherwise {
          rxTickCount := rxTickCount + 1.U
        }
      }
    }

    is(sData) {
      when(io.uartClockTick) {
        when(rxTickCount === ticksPerBit - 1.U) {
          rxTickCount := 0.U
          when(rxSync) {
            val bitMask = (1.U(8.W) << rxBitIndex)(7, 0)
            rxShift := rxShift | bitMask
          }
          when(rxBitIndex === 7.U) {
            rxState := sStop
          }.otherwise {
            rxBitIndex := rxBitIndex + 1.U
          }
        }.otherwise {
          rxTickCount := rxTickCount + 1.U
        }
      }
    }

    is(sStop) {
      when(io.uartClockTick) {
        when(rxTickCount === ticksPerBit - 1.U) {
          rxTickCount := 0.U
          when(rxSync) {
            rxPendingByte := rxShift
            rxPendingValid := true.B
          }
          rxState := sIdle
        }.otherwise {
          rxTickCount := rxTickCount + 1.U
        }
      }
    }
  }
}
