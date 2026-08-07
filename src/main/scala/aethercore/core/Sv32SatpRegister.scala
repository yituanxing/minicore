package aethercore.core

import chisel3._
import chisel3.util._

object Sv32Satp {
  val ModeBare: Int = 0
  val ModeSv32: Int = 1
  val ModeBit: Int = 31
  val AsidHigh: Int = 30
  val AsidLow: Int = 22
  val AsidBits: Int = 9
  val PpnHigh: Int = 21
  val PpnLow: Int = 0
  val PpnBits: Int = 22
}

/**
  * RV32 satp state for the bounded Sv32 bring-up profile.
  *
  * V2-B deliberately implements ASIDLEN=0. Sv32 writes retain the complete
  * 22-bit root PPN while every ASID bit reads as zero. Bare is represented by
  * the canonical all-zero value. This also gives deterministic behavior for a
  * software write that requests Bare while leaving reserved payload bits set.
  *
  * The register intentionally emits no flush pulse: the privileged ISA does
  * not make a satp write an implicit page-table-ordering or translation-cache
  * fence. SFENCE.VMA is a separate architectural operation and will be added
  * together with the TLB slice.
  */
class Sv32SatpRegister extends Module {
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeData = Input(UInt(32.W))

    val readData = Output(UInt(32.W))
    val translationEnabled = Output(Bool())
    val rootPpn = Output(UInt(Sv32Satp.PpnBits.W))
    val asid = Output(UInt(Sv32Satp.AsidBits.W))
  })

  val satp = RegInit(0.U(32.W))

  when(io.writeEnable) {
    when(io.writeData(Sv32Satp.ModeBit)) {
      // RV32 has only two MODE encodings: Bare=0 and Sv32=1. This bounded
      // implementation supports Sv32 and implements no ASID bits.
      satp := Cat(
        Sv32Satp.ModeSv32.U(1.W),
        0.U(Sv32Satp.AsidBits.W),
        io.writeData(Sv32Satp.PpnHigh, Sv32Satp.PpnLow)
      )
    }.otherwise {
      // Current privileged specifications require software to write zero to
      // the remaining satp fields when selecting Bare. Canonicalizing Bare to
      // zero keeps the implementation deterministic and fail-closed.
      satp := 0.U
    }
  }

  io.readData := satp
  io.translationEnabled := satp(Sv32Satp.ModeBit)
  io.rootPpn := satp(Sv32Satp.PpnHigh, Sv32Satp.PpnLow)
  io.asid := 0.U
}
