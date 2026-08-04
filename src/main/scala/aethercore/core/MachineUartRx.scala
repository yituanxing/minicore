package aethercore.core

import chisel3._
import chisel3.util._

object MachineUartRxMap {
  val Data: Int = 0x0
  val Status: Int = 0x4
  val Control: Int = 0x8
}

/** Deterministic receive-only UART model for the first interrupt-driven
  * FreeRTOS platform.
  *
  * Bytes enter through the simulator-facing rxValid/rxByte interface and are
  * consumed by reading the data register. The receive interrupt is
  * level-sensitive while the FIFO is non-empty and RX interrupts are enabled,
  * which maps directly onto one PLIC source. A sticky overrun bit records bytes
  * presented while the FIFO cannot accept data.
  */
class MachineUartRx(
    val depth: Int = 4,
    val addressBits: Int = 4
) extends Module {
  require(depth > 0 && (depth & (depth - 1)) == 0,
    s"UART RX FIFO depth must be a positive power of two, got $depth")
  require(depth <= 255, s"UART RX status count is 8 bits, got depth=$depth")
  require(addressBits >= 4, s"UART RX register map needs at least 4 address bits")

  private val pointerBits = log2Ceil(depth)
  private val countBits = log2Ceil(depth + 1)

  val io = IO(new Bundle {
    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())

    val request = Input(Bool())
    val write = Input(Bool())
    val address = Input(UInt(addressBits.W))
    val wdata = Input(UInt(32.W))
    val wmask = Input(UInt(4.W))

    val ready = Output(Bool())
    val rdata = Output(UInt(32.W))
    val fault = Output(Bool())

    val interrupt = Output(Bool())
    val interruptEnable = Output(Bool())
    val count = Output(UInt(countBits.W))
    val overrun = Output(Bool())
  })

  private def mergeBytes(oldValue: UInt, newValue: UInt, mask: UInt): UInt = {
    Cat((0 until 4).reverse.map { byte =>
      val high = byte * 8 + 7
      val low = byte * 8
      Mux(mask(byte), newValue(high, low), oldValue(high, low))
    })
  }

  private def increment(pointer: UInt): UInt = {
    if (depth == 1) 0.U else pointer + 1.U
  }

  val storage = Reg(Vec(depth, UInt(8.W)))
  val head = RegInit(0.U(pointerBits.W))
  val tail = RegInit(0.U(pointerBits.W))
  val count = RegInit(0.U(countBits.W))
  val overrun = RegInit(false.B)
  val interruptEnable = RegInit(false.B)

  val dataHit = io.address === MachineUartRxMap.Data.U(addressBits.W)
  val statusHit = io.address === MachineUartRxMap.Status.U(addressBits.W)
  val controlHit = io.address === MachineUartRxMap.Control.U(addressBits.W)
  val implemented = dataHit || statusHit || controlHit
  val aligned = io.address(1, 0) === 0.U
  val directionLegal = !io.write || !dataHit
  val accepted = io.request && aligned && implemented && directionLegal

  io.ready := io.request
  io.fault := io.request && (!aligned || !implemented || !directionLegal)

  val nonEmpty = count =/= 0.U
  val full = count === depth.U
  val pop = accepted && !io.write && dataHit && nonEmpty
  io.rxReady := !full || pop
  val push = io.rxValid && io.rxReady

  val dataRead = Mux(nonEmpty, storage(head), 0.U)
  val statusRead = Cat(
    0.U(16.W),
    count.pad(8),
    0.U(6.W),
    overrun,
    nonEmpty
  )
  val controlRead = Cat(0.U(31.W), interruptEnable)

  val readData = WireDefault(0.U(32.W))
  when(dataHit) { readData := Cat(0.U(24.W), dataRead) }
  when(statusHit) { readData := statusRead }
  when(controlHit) { readData := controlRead }
  io.rdata := Mux(accepted && !io.write, readData, 0.U)

  val mergedControl = mergeBytes(controlRead, io.wdata, io.wmask)
  when(accepted && io.write && controlHit) {
    interruptEnable := mergedControl(0)
  }

  // Status bit 1 is write-one-to-clear. Other status bits are read-only.
  when(accepted && io.write && statusHit && io.wmask(0) && io.wdata(1)) {
    overrun := false.B
  }

  when(push) {
    storage(tail) := io.rxByte
    tail := increment(tail)
  }
  when(pop) {
    head := increment(head)
  }

  switch(Cat(push, pop)) {
    is("b10".U) { count := count + 1.U }
    is("b01".U) { count := count - 1.U }
  }

  when(io.rxValid && !io.rxReady) {
    overrun := true.B
  }

  io.interrupt := interruptEnable && nonEmpty
  io.interruptEnable := interruptEnable
  io.count := count
  io.overrun := overrun
}
