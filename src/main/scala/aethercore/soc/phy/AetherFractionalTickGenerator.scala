package aethercore.soc.phy

import chisel3._

/**
  * Vendor-neutral fractional clock-enable generator.
  *
  * Each source-clock cycle accumulates targetFrequencyHz. A one-cycle tick
  * is emitted whenever the accumulator crosses sourceFrequencyHz, preserving
  * the exact long-term frequency ratio without introducing a second Chisel
  * clock domain.
  *
  * This is suitable for board-level clock enables such as AetherSoC's 10 MHz
  * architectural timebase and 3.6864 MHz ns16550 reference clock. A concrete
  * FPGA port may later replace it with a PLL-derived enable without changing
  * the SoC-facing tick contract.
  */
class AetherFractionalTickGenerator(
    val sourceFrequencyHz: Long,
    val targetFrequencyHz: Long
) extends Module {
  require(sourceFrequencyHz > 0)
  require(targetFrequencyHz > 0)
  require(targetFrequencyHz <= sourceFrequencyHz)

  private val width =
    (BigInt(sourceFrequencyHz) + BigInt(targetFrequencyHz)).bitLength
  private val source = BigInt(sourceFrequencyHz).U(width.W)
  private val target = BigInt(targetFrequencyHz).U(width.W)

  val io = IO(new Bundle {
    val tick = Output(Bool())
  })

  private val accumulator = RegInit(0.U(width.W))
  private val sum = accumulator + target
  private val fire = sum >= source

  io.tick := fire
  accumulator := Mux(fire, sum - source, sum)
}
