package aethercore.sim

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}

/**
  * Simulation-only adapter from the unified AetherMem master back to the
  * historical three host-memory seams used by the qualified Linux runner.
  *
  * The source tag layout is frozen by AetherSoCMemoryHub:
  *   0 = data/D-cache, 1 = PTW, 2 = instruction.
  *
  * Each source owns one independent compatibility slot, so instruction, PTW
  * and data traffic can remain concurrently outstanding while preserving the
  * old runner ABI.
  */
class AetherSoCUnifiedHostMemoryAdapter(
    val addrBits: Int = 56,
    val dataBits: Int = 64,
    val localTxnIdBits: Int = 2,
    val sourceBits: Int = 2
) extends Module {
  private val txnIdBits = localTxnIdBits + sourceBits

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(
      new AetherMemRequest(addrBits, dataBits, txnIdBits)
    ))
    val response = Decoupled(new AetherMemResponse(dataBits, txnIdBits))

    val imemValid = Output(Bool())
    val imemAddr = Output(UInt(addrBits.W))
    val imemBytes = Output(UInt(3.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

    val ptwValid = Output(Bool())
    val ptwAddr = Output(UInt(addrBits.W))
    val ptwReady = Input(Bool())
    val ptwRdata = Input(UInt(64.W))
    val ptwFault = Input(Bool())

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAtomic = Output(Bool())
    val memOp = Output(AetherMemOp())
    val memAtomicOp = Output(AtomicOp())
    val memAddr = Output(UInt(addrBits.W))
    val memWdata = Output(UInt(dataBits.W))
    val memWmask = Output(UInt((dataBits / 8).W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(dataBits.W))
    val memFault = Input(Bool())
  })

  private val DataSource = 0
  private val PtwSource = 1
  private val InstructionSource = 2

  private val dataActive = RegInit(false.B)
  private val ptwActive = RegInit(false.B)
  private val instructionActive = RegInit(false.B)

  private val dataRequest = Reg(new AetherMemRequest(addrBits, dataBits, txnIdBits))
  private val ptwRequest = Reg(new AetherMemRequest(addrBits, dataBits, txnIdBits))
  private val instructionRequest = Reg(new AetherMemRequest(addrBits, dataBits, txnIdBits))

  private val incomingSource =
    io.request.bits.txnId(txnIdBits - 1, localTxnIdBits)

  io.request.ready := MuxLookup(
    incomingSource,
    false.B
  )(
    Seq(
      DataSource.U -> !dataActive,
      PtwSource.U -> !ptwActive,
      InstructionSource.U -> !instructionActive
    )
  )

  when(io.request.fire) {
    switch(incomingSource) {
      is(DataSource.U) {
        dataRequest := io.request.bits
        dataActive := true.B
      }
      is(PtwSource.U) {
        ptwRequest := io.request.bits
        ptwActive := true.B
      }
      is(InstructionSource.U) {
        instructionRequest := io.request.bits
        instructionActive := true.B
      }
    }
  }

  when(io.request.valid) {
    assert(incomingSource <= InstructionSource.U,
      "unified host adapter received an invalid AetherMem source tag")
  }

  io.imemValid := instructionActive
  io.imemAddr := instructionRequest.paddr
  io.imemBytes := Mux(
    instructionRequest.size === MemSize.Half,
    2.U,
    Mux(instructionRequest.size === MemSize.Word, 4.U, 0.U)
  )

  io.ptwValid := ptwActive
  io.ptwAddr := ptwRequest.paddr

  io.memValid := dataActive
  io.memWrite := dataRequest.op === AetherMemOp.Write
  io.memAtomic := dataRequest.op === AetherMemOp.Atomic
  io.memOp := dataRequest.op
  io.memAtomicOp := dataRequest.atomicOp
  io.memAddr := dataRequest.paddr
  io.memWdata := dataRequest.wdata
  io.memWmask := dataRequest.wmask
  io.memSize := dataRequest.size

  private val responses = Module(
    new RRArbiter(new AetherMemResponse(dataBits, txnIdBits), 3)
  )

  responses.io.in(DataSource).valid := dataActive && io.memReady
  responses.io.in(DataSource).bits.txnId := dataRequest.txnId
  responses.io.in(DataSource).bits.rdata := io.memRdata
  responses.io.in(DataSource).bits.fault := io.memFault
  responses.io.in(DataSource).bits.last := true.B

  responses.io.in(PtwSource).valid := ptwActive && io.ptwReady
  responses.io.in(PtwSource).bits.txnId := ptwRequest.txnId
  responses.io.in(PtwSource).bits.rdata := io.ptwRdata
  responses.io.in(PtwSource).bits.fault := io.ptwFault
  responses.io.in(PtwSource).bits.last := true.B

  // Historical instruction memory is combinational/zero-wait. Once an
  // instruction request has been captured, its host response is available for
  // the response arbiter without a separate ready input.
  responses.io.in(InstructionSource).valid := instructionActive
  responses.io.in(InstructionSource).bits.txnId := instructionRequest.txnId
  responses.io.in(InstructionSource).bits.rdata := io.imemInst.pad(dataBits)
  responses.io.in(InstructionSource).bits.fault := io.imemFault
  responses.io.in(InstructionSource).bits.last := true.B

  io.response <> responses.io.out

  when(responses.io.in(DataSource).fire) {
    dataActive := false.B
  }
  when(responses.io.in(PtwSource).fire) {
    ptwActive := false.B
  }
  when(responses.io.in(InstructionSource).fire) {
    instructionActive := false.B
  }

  when(instructionActive) {
    assert(
      instructionRequest.size === MemSize.Half ||
        instructionRequest.size === MemSize.Word,
      "unified host instruction request must be 2 or 4 bytes"
    )
  }
}
