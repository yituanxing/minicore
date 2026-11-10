package aethercore.core.arith

import chisel3._
import chisel3.util._

/** Shared unsigned restoring divider primitive, independent of ROB, PRF,
  * XLEN-specific ISA signedness, completion/commit and the Tiny backend.
  *
  * One quotient bit per busy cycle. Caller is responsible for RV32/RV64 word
  * normalization, sign correction, divide-by-zero/overflow and lifetime tags.
  * Output is held stable under Decoupled backpressure.
  */
class UnsignedRestoringDivider(val width: Int) extends Module {
  require(width == 32 || width == 64, "supported RISC-V word widths are 32 and 64")
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new Bundle {
      val dividend = UInt(width.W)
      val divisor = UInt(width.W)
    }))
    val response = Decoupled(new Bundle {
      val quotient = UInt(width.W)
      val remainder = UInt(width.W)
    })
  })

  private val busy = RegInit(false.B)
  private val holding = RegInit(false.B)
  private val quotient = Reg(UInt(width.W))
  private val divisor = Reg(UInt(width.W))
  private val remainder = Reg(UInt((width + 1).W))
  private val iteration = Reg(UInt(log2Ceil(width + 1).W))
  private val resultQuotient = Reg(UInt(width.W))
  private val resultRemainder = Reg(UInt(width.W))

  io.request.ready := !busy && !holding
  io.response.valid := holding
  io.response.bits.quotient := resultQuotient
  io.response.bits.remainder := resultRemainder

  when(io.request.fire) {
    quotient := io.request.bits.dividend
    divisor := io.request.bits.divisor
    remainder := 0.U
    iteration := 0.U
    busy := true.B
  }

  when(busy) {
    val shiftedRemainder = Cat(remainder(width - 1, 0), quotient(width - 1))
    val shiftedQuotient = Cat(quotient(width - 2, 0), 0.U(1.W))
    val subtrahend = Cat(0.U(1.W), divisor)
    val subtract = shiftedRemainder >= subtrahend
    val nextRemainder = Mux(subtract, shiftedRemainder - subtrahend, shiftedRemainder)
    val nextQuotient = Mux(subtract, shiftedQuotient | 1.U, shiftedQuotient)
    when(iteration === (width - 1).U) {
      resultQuotient := nextQuotient
      resultRemainder := nextRemainder(width - 1, 0)
      holding := true.B
      busy := false.B
    }.otherwise {
      quotient := nextQuotient
      remainder := nextRemainder
      iteration := iteration + 1.U
    }
  }

  when(io.response.fire) {
    holding := false.B
  }
  assert(!(busy && holding), "divider must not execute while holding an undrained result")
}
