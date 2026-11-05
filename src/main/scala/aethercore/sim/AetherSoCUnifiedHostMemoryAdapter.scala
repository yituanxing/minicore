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
  * PTW and instruction each retain one compatibility lifetime. Data ordinary
  * reads retain one slot per local transaction ID so the lightweight host model
  * does not collapse the production LoadQ/D-cache concurrency. Serialized Data
  * traffic drains all normal reads first. Source and transaction identity remain
  * intact while preserving the old runner ABI.
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
  private val DataTxnCount = 1 << localTxnIdBits

  // Product AXI permits ordinary RAM reads with distinct transaction IDs to
  // overlap. Mirror that semantic capability in the lightweight unified host
  // adapter instead of re-serializing the LoadQ/D-cache path to one DataSource
  // lifetime. Non-normal data traffic (write/atomic/ordered/side-effecting read)
  // remains globally serialized exactly like AetherMemToAxi4Bridge.
  private val dataReadActive =
    RegInit(VecInit(Seq.fill(DataTxnCount)(false.B)))
  private val dataReadRequest =
    Reg(Vec(DataTxnCount, new AetherMemRequest(addrBits, dataBits, txnIdBits)))
  private val dataSerialActive = RegInit(false.B)
  private val dataSerialRequest =
    Reg(new AetherMemRequest(addrBits, dataBits, txnIdBits))

  private val ptwActive = RegInit(false.B)
  private val instructionActive = RegInit(false.B)

  private val ptwRequest = Reg(new AetherMemRequest(addrBits, dataBits, txnIdBits))
  private val instructionRequest = Reg(new AetherMemRequest(addrBits, dataBits, txnIdBits))

  private val incomingSource =
    io.request.bits.txnId(txnIdBits - 1, localTxnIdBits)
  private val incomingLocalTxn =
    io.request.bits.txnId(localTxnIdBits - 1, 0)
  private val incomingNormalRead =
    io.request.bits.op === AetherMemOp.Read &&
      !io.request.bits.attributes.sideEffecting &&
      !io.request.bits.attributes.ordered

  private val anyDataReadActive = dataReadActive.asUInt.orR
  private val allNormalReadsDrained =
    !anyDataReadActive && !ptwActive && !instructionActive

  io.request.ready := MuxLookup(
    incomingSource,
    false.B
  )(
    Seq(
      DataSource.U -> Mux(
        incomingNormalRead,
        !dataSerialActive && !dataReadActive(incomingLocalTxn),
        !dataSerialActive && allNormalReadsDrained
      ),
      PtwSource.U -> (!dataSerialActive && !ptwActive),
      InstructionSource.U -> (!dataSerialActive && !instructionActive)
    )
  )

  when(io.request.fire) {
    switch(incomingSource) {
      is(DataSource.U) {
        when(incomingNormalRead) {
          dataReadRequest(incomingLocalTxn) := io.request.bits
          dataReadActive(incomingLocalTxn) := true.B
        }.otherwise {
          dataSerialRequest := io.request.bits
          dataSerialActive := true.B
        }
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

  private val dataReadSelectValid = dataReadActive.asUInt.orR
  // A new lower-numbered LoadQ slot may become active while a higher-numbered
  // read is waiting for host RAM. Re-prioritizing the queue every cycle would
  // change memAddr/memSize underneath an asserted memValid while memReady=0.
  // Pin the chosen slot until its physical memory handshake. This keeps the
  // same stable valid/payload contract as the synthesizable AXI path even when
  // the C++ runner injects arbitrary memory wait cycles.
  private val dataReadPinned = RegInit(false.B)
  private val dataReadPinnedSlot = Reg(UInt(localTxnIdBits.W))
  private val dataReadSelect = Mux(
    dataReadPinned, dataReadPinnedSlot, PriorityEncoder(dataReadActive))
  private val selectedDataReadRequest = dataReadRequest(dataReadSelect)
  private val selectedDataValid = dataSerialActive || dataReadSelectValid
  private val selectedDataRequest =
    Mux(dataSerialActive, dataSerialRequest, selectedDataReadRequest)

  // The historical C++ host commits a store/AMO on memValid && memReady,
  // independently of whether the three-source response arbiter selects Data
  // this cycle. Without a skid entry, a competing instruction/PTW response
  // leaves memValid asserted for another cycle and can commit the SAME store
  // or atomic operation twice. Capture its one terminal response exactly once
  // on the host handshake, then wait for the output arbiter to consume it.
  private val bufferedDataResponse = RegInit(false.B)
  private val bufferedTxnId = Reg(UInt(txnIdBits.W))
  private val bufferedRdata = Reg(UInt(dataBits.W))
  private val bufferedFault = Reg(Bool())
  private val bufferedWasSerial = Reg(Bool())
  private val bufferedReadSlot = Reg(UInt(localTxnIdBits.W))

  io.memValid := selectedDataValid && !bufferedDataResponse
  io.memWrite := selectedDataRequest.op === AetherMemOp.Write
  io.memAtomic := selectedDataRequest.op === AetherMemOp.Atomic
  io.memOp := selectedDataRequest.op
  io.memAtomicOp := selectedDataRequest.atomicOp
  io.memAddr := selectedDataRequest.paddr
  io.memWdata := selectedDataRequest.wdata
  io.memWmask := selectedDataRequest.wmask
  // The frozen C++ host decodes memSize unconditionally, including idle
  // cycles. Do not expose the undefined contents of an uninitialized request
  // register as an illegal enum while memValid is low.
  io.memSize := Mux(selectedDataValid, selectedDataRequest.size, MemSize.Byte)

  private val responses = Module(
    new RRArbiter(new AetherMemResponse(dataBits, txnIdBits), 3)
  )

  private val hostDataHandshake = io.memValid && io.memReady
  when(io.memValid && !io.memReady && !dataSerialActive) {
    dataReadPinned := true.B
    dataReadPinnedSlot := dataReadSelect
  }
  when(hostDataHandshake) {
    dataReadPinned := false.B
  }
  responses.io.in(DataSource).valid := bufferedDataResponse || hostDataHandshake
  responses.io.in(DataSource).bits.txnId :=
    Mux(bufferedDataResponse, bufferedTxnId, selectedDataRequest.txnId)
  responses.io.in(DataSource).bits.rdata :=
    Mux(bufferedDataResponse, bufferedRdata, io.memRdata)
  responses.io.in(DataSource).bits.fault :=
    Mux(bufferedDataResponse, bufferedFault, io.memFault)
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

  when(hostDataHandshake && !responses.io.in(DataSource).fire) {
    bufferedDataResponse := true.B
    bufferedTxnId := selectedDataRequest.txnId
    bufferedRdata := io.memRdata
    bufferedFault := io.memFault
    bufferedWasSerial := dataSerialActive
    bufferedReadSlot := dataReadSelect
  }

  when(responses.io.in(DataSource).fire) {
    when(bufferedDataResponse) {
      bufferedDataResponse := false.B
    }
    when(Mux(bufferedDataResponse, bufferedWasSerial, dataSerialActive)) {
      dataSerialActive := false.B
    }.otherwise {
      dataReadActive(Mux(bufferedDataResponse, bufferedReadSlot,
        dataReadSelect)) := false.B
    }
  }
  when(responses.io.in(PtwSource).fire) {
    ptwActive := false.B
  }
  when(responses.io.in(InstructionSource).fire) {
    instructionActive := false.B
  }

  when(dataSerialActive) {
    assert(!dataReadSelectValid && !ptwActive && !instructionActive,
      "serialized Data request must own the host-memory boundary exclusively")
  }

  when(dataReadSelectValid) {
    assert(!dataSerialActive,
      "ordinary Data reads must not overlap a serialized Data request")
  }

  when(dataReadPinned) {
    assert(dataReadActive(dataReadPinnedSlot) && !dataSerialActive,
      "a stalled host read must retain its original live transaction slot")
  }

  when(instructionActive) {
    assert(
      instructionRequest.size === MemSize.Half ||
        instructionRequest.size === MemSize.Word,
      "unified host instruction request must be 2 or 4 bytes"
    )
  }
}
