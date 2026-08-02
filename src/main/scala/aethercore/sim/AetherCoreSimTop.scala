package aethercore.sim

import chisel3._
import chisel3.util._
import aethercore.common._
import aethercore.config.{CoreConfig, CoreProfiles}
import aethercore.core.AetherCore

class AetherCoreSimTop(
    val config: CoreConfig = CoreProfiles.rv64imCurrent,
    val stopOnTrap: Boolean = true,
    val enableMachineTimer: Boolean = false
) extends Module {
  private val xlen = config.isa.xlen
  private val paddrBits = config.platform.paddrBits
  private val busDataBits = config.platform.busDataBits
  private val busBytes = config.platform.busBytes

  val io = IO(new Bundle {
    val imemAddr = Output(UInt(paddrBits.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAddr = Output(UInt(paddrBits.W))
    val memWdata = Output(UInt(busDataBits.W))
    val memWmask = Output(UInt(busBytes.W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(busDataBits.W))
    val memFault = Input(Bool())

    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(xlen.W))

    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  val core = Module(new AetherCore(config))
  val uartAddress = config.platform.uartAddress.U(paddrBits.W)
  val exitAddress = config.platform.exitAddress.U(paddrBits.W)

  core.io.imem.inst := io.imemInst
  core.io.imem.fault := io.imemFault
  io.imemAddr := core.io.imem.addr

  val machineTimerPending = WireDefault(false.B)
  val isTimerAccess = WireDefault(false.B)
  val timerReadData = WireDefault(0.U(busDataBits.W))

  if (enableMachineTimer) {
    val mtimeAddress = BigInt("0200bff8", 16).U(paddrBits.W)
    val mtimecmpAddress = BigInt("02004000", 16).U(paddrBits.W)
    val mtime = RegInit(0.U(64.W))
    val mtimecmp = RegInit("hffffffffffffffff".U(64.W))

    val isMtimeLow = core.io.dmem.addr === mtimeAddress
    val isMtimeHigh = core.io.dmem.addr === (mtimeAddress + 4.U)
    val isMtimecmpLow = core.io.dmem.addr === mtimecmpAddress
    val isMtimecmpHigh = core.io.dmem.addr === (mtimecmpAddress + 4.U)
    val timerAddressHit =
      isMtimeLow || isMtimeHigh || isMtimecmpLow || isMtimecmpHigh

    isTimerAccess := core.io.dmem.valid && timerAddressHit
    machineTimerPending := mtime >= mtimecmp

    if (busDataBits == 32) {
      val byteMask = Cat((0 until 4).reverse.map(byte => Fill(8, core.io.dmem.wmask(byte))))
      def mergeWord(old: UInt): UInt =
        (old & ~byteMask) | (core.io.dmem.wdata(31, 0) & byteMask)

      timerReadData := MuxCase(
        0.U(32.W),
        Seq(
          isMtimeLow -> mtime(31, 0),
          isMtimeHigh -> mtime(63, 32),
          isMtimecmpLow -> mtimecmp(31, 0),
          isMtimecmpHigh -> mtimecmp(63, 32)
        )
      )

      val nextMtime = WireDefault(mtime + 1.U)
      val nextMtimecmp = WireDefault(mtimecmp)
      when(isTimerAccess && core.io.dmem.write) {
        when(isMtimeLow) { nextMtime := Cat(mtime(63, 32), mergeWord(mtime(31, 0))) }
        when(isMtimeHigh) { nextMtime := Cat(mergeWord(mtime(63, 32)), mtime(31, 0)) }
        when(isMtimecmpLow) {
          nextMtimecmp := Cat(mtimecmp(63, 32), mergeWord(mtimecmp(31, 0)))
        }
        when(isMtimecmpHigh) {
          nextMtimecmp := Cat(mergeWord(mtimecmp(63, 32)), mtimecmp(31, 0))
        }
      }
      mtime := nextMtime
      mtimecmp := nextMtimecmp
    } else {
      val byteMask = Cat((0 until 8).reverse.map(byte => Fill(8, core.io.dmem.wmask(byte))))
      def mergeDword(old: UInt): UInt =
        (old & ~byteMask) | (core.io.dmem.wdata(63, 0) & byteMask)

      timerReadData := Mux(
        isMtimeLow,
        mtime,
        Mux(isMtimecmpLow, mtimecmp, 0.U(64.W))
      )

      val nextMtime = WireDefault(mtime + 1.U)
      val nextMtimecmp = WireDefault(mtimecmp)
      when(isTimerAccess && core.io.dmem.write) {
        when(isMtimeLow) { nextMtime := mergeDword(mtime) }
        when(isMtimecmpLow) { nextMtimecmp := mergeDword(mtimecmp) }
      }
      mtime := nextMtime
      mtimecmp := nextMtimecmp
    }
  }

  core.io.machineTimerInterrupt := machineTimerPending

  val isWrite = core.io.dmem.valid && core.io.dmem.write
  val isUart = isWrite && core.io.dmem.addr === uartAddress
  val isExit = isWrite && core.io.dmem.addr === exitAddress
  val isMmio = isUart || isExit || isTimerAccess

  io.memValid := core.io.dmem.valid && !isMmio
  io.memWrite := core.io.dmem.write
  io.memAddr := core.io.dmem.addr
  io.memWdata := core.io.dmem.wdata
  io.memWmask := core.io.dmem.wmask
  io.memSize := core.io.dmem.size

  core.io.dmem.ready := Mux(isMmio, true.B, io.memReady)
  core.io.dmem.rdata := Mux(isTimerAccess, timerReadData, io.memRdata)
  core.io.dmem.fault := Mux(isMmio, false.B, io.memFault)

  io.uartValid := isUart
  io.uartByte := core.io.dmem.wdata(7, 0)
  io.exitValid := isExit
  io.exitCode := core.io.dmem.wdata

  val observedTrap = RegInit(false.B)
  when(core.io.commit.valid && core.io.commit.exception) {
    observedTrap := true.B
  }

  io.commit := core.io.commit
  io.halted := core.io.halted || (if (stopOnTrap) observedTrap else false.B)
}
