package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.CommitTrace
import aethercore.memory.{AetherMemRequest, AetherMemResponse}

/**
  * First synthesizable unified-memory AetherSoC boundary.
  *
  * The qualified Linux platform remains the software-visible owner of PMA,
  * D-cache, UART, PLIC, timer and MMIO. This wrapper converts its historical
  * instruction/PTW/data memory seams into three semantic AetherMem clients and
  * joins them through AetherSoCMemoryHub.
  *
  * The only external memory interface is one AetherMem request/response pair.
  * A later adapter may translate this boundary to AXI4 without teaching the CPU
  * or the internal SoC fabric about AXI channel semantics.
  */
class AetherCoreV2UnifiedMemorySoC extends Module {
  private val xlen = 64
  private val paddrBits = 56
  private val dataBits = 64
  private val clientTxnIdBits = 2
  private val clientCount = 3
  private val sourceBits = log2Ceil(clientCount)
  val externalTxnIdBits: Int = clientTxnIdBits + sourceBits

  val io = IO(new Bundle {
    val memoryRequest =
      Decoupled(new AetherMemRequest(paddrBits, dataBits, externalTxnIdBits))
    val memoryResponse =
      Flipped(Decoupled(new AetherMemResponse(dataBits, externalTxnIdBits)))

    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))

    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val timerInterrupt = Output(Bool())

    val exitValid = Output(Bool())
    val exitCode = Output(UInt(xlen.W))
    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))

    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))

    val commit = Output(new CommitTrace(xlen, paddrBits, dataBits))
    val halted = Output(Bool())
  })

  val platform = Module(new AetherCoreV2LinuxSoC(
    enableInstructionBackpressure = true,
    exposeExternalMemoryAttributes = true
  ))

  val dataAdapter = Module(new AetherSoCLegacyDataAdapter(
    addrBits = paddrBits,
    dataBits = dataBits,
    txnIdBits = clientTxnIdBits
  ))
  val ptwAdapter = Module(new AetherSoCPtwReadAdapter(
    addrBits = paddrBits,
    dataBits = dataBits,
    pteBits = 64,
    txnIdBits = clientTxnIdBits
  ))
  val instructionAdapter = Module(new AetherSoCInstructionReadAdapter(
    addrBits = paddrBits,
    dataBits = dataBits,
    txnIdBits = clientTxnIdBits
  ))
  val hub = Module(new AetherSoCMemoryHub(
    addrBits = paddrBits,
    dataBits = dataBits,
    clientTxnIdBits = clientTxnIdBits,
    clientCount = clientCount
  ))

  // Historical terminal-response data seam -> semantic AetherMem.
  dataAdapter.io.legacyValid := platform.io.memValid
  dataAdapter.io.legacyOp := platform.io.memOp
  dataAdapter.io.legacyAtomicOp := platform.io.memAtomicOp
  dataAdapter.io.legacyAddr := platform.io.memAddr
  dataAdapter.io.legacyWdata := platform.io.memWdata
  dataAdapter.io.legacyWmask := platform.io.memWmask
  dataAdapter.io.legacySize := platform.io.memSize
  dataAdapter.io.legacyAttributes := platform.io.memAttributes.get
  platform.io.memReady := dataAdapter.io.legacyReady
  platform.io.memRdata := dataAdapter.io.legacyRdata
  platform.io.memFault := dataAdapter.io.legacyFault

  // PTW read seam -> semantic AetherMem.
  ptwAdapter.io.legacyValid := platform.io.ptwValid
  ptwAdapter.io.legacyAddr := platform.io.ptwAddr
  platform.io.ptwReady := ptwAdapter.io.legacyReady
  platform.io.ptwRdata := ptwAdapter.io.legacyRdata
  platform.io.ptwFault := ptwAdapter.io.legacyFault

  // Instruction fetch uses the already-qualified optional frontend
  // backpressure seam so variable-latency unified memory cannot advance the PC
  // before the exact physical fetch response is available.
  instructionAdapter.io.legacyValid := platform.io.imemValid
  instructionAdapter.io.legacyAddr := platform.io.imemAddr
  instructionAdapter.io.legacyBytes := platform.io.imemBytes
  platform.io.imemReady.get := instructionAdapter.io.legacyReady
  platform.io.imemInst := instructionAdapter.io.legacyInst
  platform.io.imemFault := instructionAdapter.io.legacyFault

  hub.io.clients(0).request <> dataAdapter.io.request
  dataAdapter.io.response <> hub.io.clients(0).response

  hub.io.clients(1).request <> ptwAdapter.io.request
  ptwAdapter.io.response <> hub.io.clients(1).response

  hub.io.clients(2).request <> instructionAdapter.io.request
  instructionAdapter.io.response <> hub.io.clients(2).response

  // Export exactly one semantic memory master.
  io.memoryRequest.valid := hub.io.downstreamRequest.valid
  io.memoryRequest.bits := hub.io.downstreamRequest.bits
  hub.io.downstreamRequest.ready := io.memoryRequest.ready

  hub.io.downstreamResponse.valid := io.memoryResponse.valid
  hub.io.downstreamResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := hub.io.downstreamResponse.ready

  // Byte-stream UART remains at the reusable SoC boundary. A physical serial
  // PHY belongs in the later FPGA wrapper.
  platform.io.rxValid := io.rxValid
  platform.io.rxByte := io.rxByte
  io.rxReady := platform.io.rxReady
  io.uartValid := platform.io.uartValid
  io.uartByte := platform.io.uartByte

  io.supervisorExternalInterrupt := platform.io.supervisorExternalInterrupt
  io.uartInterrupt := platform.io.uartInterrupt
  io.uartRxInterrupt := platform.io.uartRxInterrupt
  io.timerInterrupt := platform.io.timerInterrupt

  io.exitValid := platform.io.exitValid
  io.exitCode := platform.io.exitCode
  io.mtime := platform.io.mtime
  io.mtimecmp := platform.io.mtimecmp

  io.dcacheHitCount := platform.io.dcacheHitCount
  io.dcacheMissCount := platform.io.dcacheMissCount
  io.dcacheBypassCount := platform.io.dcacheBypassCount
  io.commit := platform.io.commit
  io.halted := platform.io.halted
}
