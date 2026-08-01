package aethercore.sim

import chisel3._
import aethercore.common._
import aethercore.core.AetherCore

class AetherCoreSimTop extends Module {
  val io = IO(new Bundle {
    val imemAddr = Output(UInt(64.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAddr = Output(UInt(64.W))
    val memWdata = Output(UInt(64.W))
    val memWmask = Output(UInt(8.W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(64.W))
    val memFault = Input(Bool())

    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(64.W))

    val commit = Output(new CommitTrace)
    val halted = Output(Bool())
  })

  val core = Module(new AetherCore())
  val uartAddress = "h0000000010000000".U(64.W)
  val exitAddress = "h0000000010000008".U(64.W)

  core.io.imem.inst := io.imemInst
  core.io.imem.fault := io.imemFault
  io.imemAddr := core.io.imem.addr

  val isWrite = core.io.dmem.valid && core.io.dmem.write
  val isUart = isWrite && core.io.dmem.addr === uartAddress
  val isExit = isWrite && core.io.dmem.addr === exitAddress
  val isMmio = isUart || isExit

  io.memValid := core.io.dmem.valid && !isMmio
  io.memWrite := core.io.dmem.write
  io.memAddr := core.io.dmem.addr
  io.memWdata := core.io.dmem.wdata
  io.memWmask := core.io.dmem.wmask
  io.memSize := core.io.dmem.size

  core.io.dmem.ready := Mux(isMmio, true.B, io.memReady)
  core.io.dmem.rdata := io.memRdata
  core.io.dmem.fault := Mux(isMmio, false.B, io.memFault)

  io.uartValid := isUart
  io.uartByte := core.io.dmem.wdata(7, 0)
  io.exitValid := isExit
  io.exitCode := core.io.dmem.wdata

  io.commit := core.io.commit
  io.halted := core.io.halted
}
