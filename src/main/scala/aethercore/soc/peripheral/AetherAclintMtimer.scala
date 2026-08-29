package aethercore.soc.peripheral

import chisel3._
import chisel3.util._

/**
  * AetherSoC ACLINT/CLINT machine-timer subdevice.
  *
  * The SoC fabric owns the architectural addresses for MTIMECMP and MTIME.
  * This peripheral owns only timer state and MTIP generation. The selected
  * register is supplied as semantic routing metadata so the timer can later sit
  * behind a reusable MMIO fabric without knowing the board address map.
  */
class AetherAclintMtimer(
    val dataBits: Int = 64
) extends Module {
  require(dataBits == 64, "AetherSoC v0 MTIMER currently uses the RV64 64-bit MMIO data path")
  private val busBytes = dataBits / 8

  val io = IO(new Bundle {
    val request = Input(Bool())
    val write = Input(Bool())
    val selectMtimecmp = Input(Bool())
    val wdata = Input(UInt(dataBits.W))
    val wmask = Input(UInt(busBytes.W))
    val complete = Input(Bool())
    // One pulse advances MTIME by one architectural timebase tick.
    // FPGA clock generation owns the relationship between SoC clock and tick.
    val timebaseTick = Input(Bool())

    val ready = Output(Bool())
    val rdata = Output(UInt(dataBits.W))
    val fault = Output(Bool())

    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
    val interrupt = Output(Bool())
  })

  private def mergeBytes(oldValue: UInt, newValue: UInt, mask: UInt): UInt =
    Cat((0 until busBytes).reverse.map { byte =>
      val high = byte * 8 + 7
      val low = byte * 8
      Mux(mask(byte), newValue(high, low), oldValue(high, low))
    })

  private val mtime = RegInit(0.U(64.W))
  private val mtimecmp = RegInit("hffffffffffffffff".U(64.W))
  private val nextMtime = WireDefault(Mux(io.timebaseTick, mtime + 1.U, mtime))
  private val nextMtimecmp = WireDefault(mtimecmp)

  io.ready := true.B
  io.fault := false.B
  io.rdata := Mux(io.selectMtimecmp, mtimecmp, mtime)

  private val terminalFire = io.request && io.complete && io.ready
  when(terminalFire && io.write) {
    when(io.selectMtimecmp) {
      nextMtimecmp := mergeBytes(mtimecmp, io.wdata, io.wmask)
    }.otherwise {
      nextMtime := mergeBytes(mtime, io.wdata, io.wmask)
    }
  }

  mtime := nextMtime
  mtimecmp := nextMtimecmp

  io.mtime := mtime
  io.mtimecmp := mtimecmp
  io.interrupt := mtime >= mtimecmp
}
