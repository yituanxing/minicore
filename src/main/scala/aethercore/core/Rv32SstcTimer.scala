package aethercore.core

import chisel3._

/** CSR addresses and bits used by the bounded RV32 Sstc path.
  *
  * Keep these constants separate from the generic machine CSR map until the
  * N5 runtime proves the exact integration boundary. The pinned NuttX image
  * already emits time/timeh and stimecmp/stimecmph accesses.
  */
object Rv32SstcCsrAddress {
  val Mcounteren: Int = 0x306
  val Menvcfg: Int = 0x30a
  val Menvcfgh: Int = 0x31a
  val Stimecmp: Int = 0x14d
  val Stimecmph: Int = 0x15d
  val Time: Int = 0xc01
  val Timeh: Int = 0xc81
}

object Rv32SstcBit {
  val SupervisorTimerInterrupt: Int = 5
  val McounterenTime: Int = 1
  // menvcfg.STCE is bit 63. On RV32 it is bit 31 of menvcfgh.
  val MenvcfghStce: Int = 31
}

/** Minimal RV32 Sstc comparator/register state.
  *
  * The architectural time source remains platform-owned. This block owns only
  * stimecmp and derives the supervisor timer pending condition. Splitting the
  * comparator from CSR privilege/delegation logic keeps the real-time boundary
  * testable before it is wired into MachineCsrFile/AetherCore.
  */
class Rv32SstcTimer extends Module {
  val io = IO(new Bundle {
    val time = Input(UInt(64.W))

    val writeLow = Input(Bool())
    val writeHigh = Input(Bool())
    val writeData = Input(UInt(32.W))

    val readLow = Output(UInt(32.W))
    val readHigh = Output(UInt(32.W))
    val compare = Output(UInt(64.W))
    val pending = Output(Bool())
  })

  val stimecmpLow = RegInit("hffffffff".U(32.W))
  val stimecmpHigh = RegInit("hffffffff".U(32.W))

  when(io.writeLow) {
    stimecmpLow := io.writeData
  }
  when(io.writeHigh) {
    stimecmpHigh := io.writeData
  }

  val stimecmp = Cat(stimecmpHigh, stimecmpLow)
  io.readLow := stimecmpLow
  io.readHigh := stimecmpHigh
  io.compare := stimecmp
  io.pending := io.time >= stimecmp
}
