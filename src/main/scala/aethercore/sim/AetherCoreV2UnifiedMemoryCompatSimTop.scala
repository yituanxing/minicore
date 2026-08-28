package aethercore.sim

import chisel3._
import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.soc.AetherCoreV2UnifiedMemorySoC

/**
  * Exact host-ABI compatibility shell for running the existing qualified
  * OpenSBI/Linux runner through the new unified-memory SoC.
  *
  * This class intentionally exposes the historical imem/PTW/data host ports.
  * They terminate here, outside the production SoC. Internally the CPU now
  * reaches them only through:
  *
  *   I/PTW/Data adapters -> tagged MemoryHub -> one AetherMem master.
  */
class AetherCoreV2UnifiedMemoryCompatSimTop extends Module {
  private val xlen = 64
  private val paddrBits = 56
  private val busDataBits = 64
  private val busBytes = busDataBits / 8

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

    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))

    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  val soc = Module(new AetherCoreV2UnifiedMemorySoC)

  // Simulation-only compatibility alias for the existing performance,
  // Top-Down and attribution wrappers. Those layers observe TinyPagedCore
  // internals through BoringUtils; keeping this alias here avoids widening the
  // production SoC interface or bypassing the unified-memory datapath.
  val core = soc.platform.core

  val hostMemory = Module(new AetherSoCUnifiedHostMemoryAdapter(
    addrBits = paddrBits,
    dataBits = busDataBits,
    localTxnIdBits = 2,
    sourceBits = 2
  ))

  hostMemory.io.request <> soc.io.memoryRequest
  soc.io.memoryResponse <> hostMemory.io.response

  io.imemValid := hostMemory.io.imemValid
  io.imemAddr := hostMemory.io.imemAddr
  io.imemBytes := hostMemory.io.imemBytes
  hostMemory.io.imemInst := io.imemInst
  hostMemory.io.imemFault := io.imemFault

  io.ptwValid := hostMemory.io.ptwValid
  io.ptwAddr := hostMemory.io.ptwAddr
  hostMemory.io.ptwReady := io.ptwReady
  hostMemory.io.ptwRdata := io.ptwRdata
  hostMemory.io.ptwFault := io.ptwFault

  io.memValid := hostMemory.io.memValid
  io.memWrite := hostMemory.io.memWrite
  io.memAtomic := hostMemory.io.memAtomic
  io.memOp := hostMemory.io.memOp
  io.memAtomicOp := hostMemory.io.memAtomicOp
  io.memAddr := hostMemory.io.memAddr
  io.memWdata := hostMemory.io.memWdata
  io.memWmask := hostMemory.io.memWmask
  io.memSize := hostMemory.io.memSize
  hostMemory.io.memReady := io.memReady
  hostMemory.io.memRdata := io.memRdata
  hostMemory.io.memFault := io.memFault

  soc.io.rxValid := io.rxValid
  soc.io.rxByte := io.rxByte
  io.rxReady := soc.io.rxReady
  io.uartValid := soc.io.uartValid
  io.uartByte := soc.io.uartByte

  io.supervisorExternalInterrupt := soc.io.supervisorExternalInterrupt
  io.uartInterrupt := soc.io.uartInterrupt
  io.uartRxInterrupt := soc.io.uartRxInterrupt
  io.timerInterrupt := soc.io.timerInterrupt

  io.exitValid := soc.io.exitValid
  io.exitCode := soc.io.exitCode
  io.mtime := soc.io.mtime
  io.mtimecmp := soc.io.mtimecmp

  io.dcacheHitCount := soc.io.dcacheHitCount
  io.dcacheMissCount := soc.io.dcacheMissCount
  io.dcacheBypassCount := soc.io.dcacheBypassCount
  io.commit := soc.io.commit
  io.halted := soc.io.halted
}
