package aethercore.sim

import chisel3._
import aethercore.core.{MachinePlicMmio, MachinePlicMmioMap, MachineUartRx}

/** Interrupt-capable AetherCore platform slice.
  *
  * The historical machine-context defaults remain unchanged. Real OS profiles
  * can select a different PLIC context window and UART source ID without
  * forking the controller implementation; N5 uses QEMU-virt hart0 Supervisor
  * context and source ID 10.
  */
class MachineInterruptPlatform(
    val addressBits: Int = 32,
    val plicBase: BigInt = BigInt("0c000000", 16),
    val uartBase: BigInt = BigInt("10000000", 16),
    val sourceCount: Int = 8,
    val plicEnableBase: Int = MachinePlicMmioMap.Enable,
    val plicThresholdOffset: Int = MachinePlicMmioMap.Threshold,
    val plicClaimCompleteOffset: Int = MachinePlicMmioMap.ClaimComplete,
    val uartSourceId: Int = 1
) extends Module {
  require(addressBits >= 32, s"platform MMIO map requires at least 32 address bits")
  require(sourceCount > 0 && sourceCount <= 63,
    s"two-word one-based platform PLIC supports 1..63 real sources, got $sourceCount")
  require(uartSourceId > 0 && uartSourceId <= sourceCount,
    s"UART PLIC source ID must be in 1..$sourceCount, got $uartSourceId")

  private val plicSpan = BigInt("00400000", 16)
  private val uartSpan = BigInt(0x10)

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

    val externalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
  })

  val plic = Module(new MachinePlicMmio(
    sourceCount = sourceCount,
    addressBits = 24,
    enableBase = plicEnableBase,
    thresholdOffset = plicThresholdOffset,
    claimCompleteOffset = plicClaimCompleteOffset
  ))
  val uart = Module(new MachineUartRx(depth = 4, addressBits = 4))

  uart.io.rxValid := io.rxValid
  uart.io.rxByte := io.rxByte
  io.rxReady := uart.io.rxReady

  val plicSelected = io.address >= plicBase.U(addressBits.W) &&
    io.address < (plicBase + plicSpan).U(addressBits.W)
  val uartSelected = io.address >= uartBase.U(addressBits.W) &&
    io.address < (uartBase + uartSpan).U(addressBits.W)

  plic.io.request := io.request && plicSelected
  plic.io.write := io.write
  plic.io.address := (io.address - plicBase.U)(23, 0)
  plic.io.wdata := io.wdata
  plic.io.wmask := io.wmask

  uart.io.request := io.request && uartSelected
  uart.io.write := io.write
  uart.io.address := (io.address - uartBase.U)(3, 0)
  uart.io.wdata := io.wdata
  uart.io.wmask := io.wmask

  val sources = (uart.io.interrupt.asUInt << (uartSourceId - 1)).pad(sourceCount)
  plic.io.sources := sources

  val selected = plicSelected || uartSelected
  io.ready := Mux(plicSelected, plic.io.ready, Mux(uartSelected, uart.io.ready, io.request))
  io.rdata := Mux(plicSelected, plic.io.rdata, Mux(uartSelected, uart.io.rdata, 0.U))
  io.fault := Mux(
    plicSelected,
    plic.io.fault,
    Mux(uartSelected, uart.io.fault, io.request && !selected)
  )

  io.externalInterrupt := plic.io.interrupt
  io.uartInterrupt := uart.io.interrupt
}
