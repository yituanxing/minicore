package aethercore.sim

import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.soc.AetherCoreProductUnifiedMemorySoC
import _root_.circt.stage.ChiselStage
import chisel3._

/** Simulation-only, historical OpenSBI host-memory ABI for the Product CPU.
  *
  * No CPU instruction/PTW/data shortcuts are attached here: all three memory
  * classes traverse their synthesizable Product SoC clients, source-tagged
  * MemoryHub and the existing unified host adapter. This lets the existing
  * Verilator C++ test harness attempt a Product boot without replacing or
  * weakening the independently qualified V2 Linux path.
  *
  * The Product ISA still lacks F/D: this is a bring-up seam, NOT evidence that
  * OpenSBI or a full Debian userspace has booted.
  */
class AetherCoreProductUnifiedMemoryCompatSimTop extends Module {
  private val Xlen = 64
  private val PA = 56
  private val DataBits = 64

  val io = IO(new Bundle {
    val imemValid = Output(Bool())
    val imemAddr = Output(UInt(PA.W))
    val imemBytes = Output(UInt(3.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAtomic = Output(Bool())
    val memOp = Output(AetherMemOp())
    val memAtomicOp = Output(AtomicOp())
    val memAddr = Output(UInt(PA.W))
    val memWdata = Output(UInt(DataBits.W))
    val memWmask = Output(UInt((DataBits / 8).W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(DataBits.W))
    val memFault = Input(Bool())

    val ptwValid = Output(Bool())
    val ptwAddr = Output(UInt(PA.W))
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
    val exitCode = Output(UInt(Xlen.W))

    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
    val timerInterrupt = Output(Bool())
    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    val commit = Output(new CommitTrace(Xlen, PA, DataBits))
    val halted = Output(Bool())
  })

  val soc = Module(new AetherCoreProductUnifiedMemorySoC)
  val core = soc.core
  val hostMemory = Module(new AetherSoCUnifiedHostMemoryAdapter(
    addrBits = PA,
    dataBits = DataBits,
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

/** Distinct module identity for the existing OpenSBI host program. */
class AetherCoreProductOpenSbiRV64SimTop
    extends AetherCoreProductUnifiedMemoryCompatSimTop

object ElaborateProductOpenSbiRV64SimTop extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductOpenSbiRV64SimTop,
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
