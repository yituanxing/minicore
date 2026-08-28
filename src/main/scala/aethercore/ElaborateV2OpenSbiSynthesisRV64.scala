package aethercore

import _root_.circt.stage.ChiselStage
import chisel3._
import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.config.CoreProfiles
import aethercore.memory.AetherMemOp
import aethercore.sim.AetherCoreV2OpenSbiRV64SimTop

/**
  * Measurement-only synthesis wrapper for the v2 OpenSBI core-complex shell.
  *
  * It preserves the production-visible memory/PTW/UART/commit boundary while
  * intentionally omitting host-only D-cache observation counters from the top
  * interface, allowing synthesis to remove measurement-only counter logic.
  */
class AetherCoreV2OpenSbiSynthesisTop extends Module {
  private val config = CoreProfiles.rv64imasuSv39PmpSoftware
  private val xlen = config.isa.xlen
  private val paddrBits = config.platform.paddrBits
  private val busDataBits = config.platform.busDataBits
  private val busBytes = config.platform.busBytes

  val io = IO(new Bundle {
    val imemValid = Output(Bool())
    val imemAddr = Output(UInt(paddrBits.W))
    val imemBytes = Output(UInt(3.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAtomic = Output(Bool())
    val memOp = Output(AetherMemOp())
    val memAtomicOp = Output(AtomicOp())
    val memAddr = Output(UInt(paddrBits.W))
    val memWdata = Output(UInt(busDataBits.W))
    val memWmask = Output(UInt(busBytes.W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(busDataBits.W))
    val memFault = Input(Bool())

    val ptwValid = Output(Bool())
    val ptwAddr = Output(UInt(paddrBits.W))
    val ptwReady = Input(Bool())
    val ptwRdata = Input(UInt(64.W))
    val ptwFault = Input(Bool())

    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(xlen.W))

    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
    val timerInterrupt = Output(Bool())
    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  val dut = Module(new AetherCoreV2OpenSbiRV64SimTop)

  dut.io.imemInst := io.imemInst
  dut.io.imemFault := io.imemFault
  dut.io.memReady := io.memReady
  dut.io.memRdata := io.memRdata
  dut.io.memFault := io.memFault
  dut.io.ptwReady := io.ptwReady
  dut.io.ptwRdata := io.ptwRdata
  dut.io.ptwFault := io.ptwFault
  dut.io.rxValid := io.rxValid
  dut.io.rxByte := io.rxByte

  io.imemValid := dut.io.imemValid
  io.imemAddr := dut.io.imemAddr
  io.imemBytes := dut.io.imemBytes
  io.memValid := dut.io.memValid
  io.memWrite := dut.io.memWrite
  io.memAtomic := dut.io.memAtomic
  io.memOp := dut.io.memOp
  io.memAtomicOp := dut.io.memAtomicOp
  io.memAddr := dut.io.memAddr
  io.memWdata := dut.io.memWdata
  io.memWmask := dut.io.memWmask
  io.memSize := dut.io.memSize
  io.ptwValid := dut.io.ptwValid
  io.ptwAddr := dut.io.ptwAddr
  io.uartValid := dut.io.uartValid
  io.uartByte := dut.io.uartByte
  io.rxReady := dut.io.rxReady
  io.supervisorExternalInterrupt := dut.io.supervisorExternalInterrupt
  io.uartInterrupt := dut.io.uartInterrupt
  io.uartRxInterrupt := dut.io.uartRxInterrupt
  io.exitValid := dut.io.exitValid
  io.exitCode := dut.io.exitCode
  io.mtime := dut.io.mtime
  io.mtimecmp := dut.io.mtimecmp
  io.timerInterrupt := dut.io.timerInterrupt
  io.commit := dut.io.commit
  io.halted := dut.io.halted
}

object ElaborateV2OpenSbiSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2OpenSbiSynthesisTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
