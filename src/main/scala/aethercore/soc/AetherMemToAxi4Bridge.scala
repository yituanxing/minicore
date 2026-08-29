package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse}

/**
  * Correctness-first AetherMem -> AXI4 bridge for the first FPGA-capable SoC.
  *
  * Normal idempotent RAM reads may remain concurrently outstanding and are
  * identified end-to-end by the existing AetherMem/AXI transaction ID. Writes,
  * ordered/side-effecting reads, LR/SC and AMOs retain the original strict
  * single-transaction FSM and enter only after all concurrent reads drain.
  *
  * Atomic contract:
  *   - LR/SC reservation is owned here as the final AetherMem memory authority.
  *   - AMOs execute as an indivisible read/modify/write sequence relative to
  *     this bridge.
  *   - v0 assumes this bridge is the only coherent writer to the attached RAM.
  *     A later multi-master/coherent FPGA fabric must replace this assumption
  *     with AXI-exclusive/retry or a shared atomic owner.
  *
  * AXI transactions are single-beat only (len = 0). Narrow AetherMem accesses
  * retain their byte address; write data/strobes are shifted onto the matching
  * AXI byte lanes and read data is shifted back to low-aligned AetherMem form.
  */
class AetherMemToAxi4Bridge(
    val addrBits: Int = 56,
    val dataBits: Int = 64,
    val txnIdBits: Int = 4
) extends Module {
  require(dataBits == 64, "AetherSoC v0 AXI bridge currently targets a 64-bit data bus")
  require(txnIdBits > 0)

  private val BusBytes = dataBits / 8
  private val ByteOffsetBits = log2Ceil(BusBytes)
  private val ReadSlotCount = 1 << txnIdBits
  private val ReadCountBits = log2Ceil(ReadSlotCount + 1)

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(
      new AetherMemRequest(addrBits, dataBits, txnIdBits)
    ))
    val response = Decoupled(new AetherMemResponse(dataBits, txnIdBits))
    val axi = new Axi4MasterIO(addrBits, dataBits, txnIdBits)
  })

  private val states = Enum(7)
  private val sIdle = states(0)
  private val sDispatch = states(1)
  private val sReadAddress = states(2)
  private val sReadData = states(3)
  private val sWriteIssue = states(4)
  private val sWriteResponse = states(5)
  private val sRespond = states(6)
  private val state = RegInit(sIdle)

  private val ReadNormal = 0.U(2.W)
  private val ReadLr = 1.U(2.W)
  private val ReadAmo = 2.U(2.W)
  private val WriteNormal = 0.U(2.W)
  private val WriteSc = 1.U(2.W)
  private val WriteAmo = 2.U(2.W)

  private val requestReg =
    Reg(new AetherMemRequest(addrBits, dataBits, txnIdBits))

  // Concurrent normal-read metadata. The AXI ID is the authoritative lookup
  // key, so responses may return out of request order without losing the
  // original AetherMem byte-lane alignment.
  private val normalReadValid =
    RegInit(VecInit(Seq.fill(ReadSlotCount)(false.B)))
  private val normalReadByteOffset =
    Reg(Vec(ReadSlotCount, UInt(ByteOffsetBits.W)))
  private val normalReadCount = RegInit(0.U(ReadCountBits.W))
  private val readPurpose = RegInit(ReadNormal)
  private val writePurpose = RegInit(WriteNormal)

  private val responseData = RegInit(0.U(dataBits.W))
  private val responseFault = RegInit(false.B)

  // Low-aligned semantic write payload. AXI lane placement happens only at the
  // external channel boundary below.
  private val writeData = Reg(UInt(dataBits.W))
  private val writeMask = Reg(UInt(BusBytes.W))
  private val awAccepted = RegInit(false.B)
  private val wAccepted = RegInit(false.B)

  // Single-hart/single-coherent-writer reservation owner for v0 FPGA systems.
  private val reservationValid = RegInit(false.B)
  private val reservationAddress = Reg(UInt(addrBits.W))
  private val reservationSize = Reg(MemSize())

  private val incomingNormalRead =
    io.request.bits.op === AetherMemOp.Read &&
      !io.request.bits.attributes.sideEffecting &&
      !io.request.bits.attributes.ordered

  private val incomingReadId = io.request.bits.txnId
  private val incomingReadSlotFree = !normalReadValid(incomingReadId)

  private val incomingAxiSize = WireDefault(3.U(3.W))
  switch(io.request.bits.size) {
    is(MemSize.Byte) { incomingAxiSize := 0.U }
    is(MemSize.Half) { incomingAxiSize := 1.U }
    is(MemSize.Word) { incomingAxiSize := 2.U }
    is(MemSize.DWord) { incomingAxiSize := 3.U }
  }
  private val incomingAxiCache =
    Mux(io.request.bits.attributes.cacheable, "b1111".U(4.W), 0.U(4.W))

  private val fastReadCanIssue =
    state === sIdle && incomingNormalRead && incomingReadSlotFree

  io.request.ready :=
    Mux(
      incomingNormalRead,
      fastReadCanIssue && io.axi.ar.ready,
      state === sIdle && normalReadCount === 0.U
    )

  private val fastReadIssue = io.request.fire && incomingNormalRead

  when(io.request.fire && !incomingNormalRead) {
    requestReg := io.request.bits
    state := sDispatch
  }

  when(fastReadIssue) {
    normalReadValid(incomingReadId) := true.B
    normalReadByteOffset(incomingReadId) :=
      io.request.bits.paddr(ByteOffsetBits - 1, 0)
  }

  private val axiSize = WireDefault(3.U(3.W))
  switch(requestReg.size) {
    is(MemSize.Byte) { axiSize := 0.U }
    is(MemSize.Half) { axiSize := 1.U }
    is(MemSize.Word) { axiSize := 2.U }
    is(MemSize.DWord) { axiSize := 3.U }
  }

  private val byteOffset =
    requestReg.paddr(ByteOffsetBits - 1, 0)
  private val bitShift = byteOffset << 3

  private val shiftedWriteDataWide = writeData << bitShift
  private val shiftedWriteMaskWide = writeMask << byteOffset

  private val axiCache =
    Mux(requestReg.attributes.cacheable, "b1111".U(4.W), 0.U(4.W))

  private def driveAddress(channel: DecoupledIO[Axi4Address]): Unit = {
    channel.bits.id := requestReg.txnId
    channel.bits.addr := requestReg.paddr
    channel.bits.len := 0.U
    channel.bits.size := axiSize
    channel.bits.burst := Axi4Burst.Incr
    channel.bits.lock := false.B
    channel.bits.cache := axiCache
    channel.bits.prot := 0.U
    channel.bits.qos := 0.U
  }

  driveAddress(io.axi.aw)
  driveAddress(io.axi.ar)

  io.axi.aw.valid := state === sWriteIssue && !awAccepted
  io.axi.w.valid := state === sWriteIssue && !wAccepted
  io.axi.w.bits.data := shiftedWriteDataWide(dataBits - 1, 0)
  io.axi.w.bits.strb := shiftedWriteMaskWide(BusBytes - 1, 0)
  io.axi.w.bits.last := true.B
  io.axi.b.ready := state === sWriteResponse

  private val fastReadArValid =
    state === sIdle && io.request.valid &&
      incomingNormalRead && incomingReadSlotFree

  io.axi.ar.valid := state === sReadAddress || fastReadArValid
  when(fastReadArValid) {
    io.axi.ar.bits.id := io.request.bits.txnId
    io.axi.ar.bits.addr := io.request.bits.paddr
    io.axi.ar.bits.len := 0.U
    io.axi.ar.bits.size := incomingAxiSize
    io.axi.ar.bits.burst := Axi4Burst.Incr
    io.axi.ar.bits.lock := false.B
    io.axi.ar.bits.cache := incomingAxiCache
    io.axi.ar.bits.prot := 0.U
    io.axi.ar.bits.qos := 0.U
  }

  private val fastReadResponseActive =
    state === sIdle && normalReadCount =/= 0.U
  io.axi.r.ready :=
    Mux(state === sReadData, true.B,
      fastReadResponseActive && io.response.ready)

  private val returningReadId = io.axi.r.bits.id
  private val returningReadKnown = normalReadValid(returningReadId)
  private val returningBitShift =
    normalReadByteOffset(returningReadId) << 3
  private val returningReadData =
    io.axi.r.bits.data >> returningBitShift
  private val returningReadOkay =
    io.axi.r.bits.resp === Axi4Resp.Okay ||
      io.axi.r.bits.resp === Axi4Resp.ExOkay
  private val returningReadFault =
    !returningReadOkay || !io.axi.r.bits.last || !returningReadKnown

  io.response.valid :=
    Mux(fastReadResponseActive, io.axi.r.valid, state === sRespond)
  io.response.bits.txnId :=
    Mux(fastReadResponseActive, io.axi.r.bits.id, requestReg.txnId)
  io.response.bits.rdata :=
    Mux(fastReadResponseActive, returningReadData, responseData)
  io.response.bits.fault :=
    Mux(fastReadResponseActive, returningReadFault, responseFault)
  io.response.bits.last := true.B

  private val fastReadRetire =
    fastReadResponseActive && io.axi.r.fire

  when(fastReadRetire) {
    assert(returningReadKnown,
      "AXI returned an ID with no concurrent AetherMem read lifetime")
    normalReadValid(returningReadId) := false.B
  }

  switch(Cat(fastReadIssue, fastReadRetire)) {
    is("b10".U) { normalReadCount := normalReadCount + 1.U }
    is("b01".U) { normalReadCount := normalReadCount - 1.U }
  }

  private val shiftedReadData = io.axi.r.bits.data >> bitShift

  private val oldWord = shiftedReadData(31, 0)
  private val rhsWord = requestReg.wdata(31, 0)
  private val oldDword = shiftedReadData
  private val rhsDword = requestReg.wdata

  private val amoWordResult = WireDefault(rhsWord)
  switch(requestReg.atomicOp) {
    is(AtomicOp.Swap) { amoWordResult := rhsWord }
    is(AtomicOp.Add) { amoWordResult := oldWord + rhsWord }
    is(AtomicOp.Xor) { amoWordResult := oldWord ^ rhsWord }
    is(AtomicOp.And) { amoWordResult := oldWord & rhsWord }
    is(AtomicOp.Or) { amoWordResult := oldWord | rhsWord }
    is(AtomicOp.Min) {
      amoWordResult := Mux(oldWord.asSInt < rhsWord.asSInt, oldWord, rhsWord)
    }
    is(AtomicOp.Max) {
      amoWordResult := Mux(oldWord.asSInt > rhsWord.asSInt, oldWord, rhsWord)
    }
    is(AtomicOp.Minu) { amoWordResult := Mux(oldWord < rhsWord, oldWord, rhsWord) }
    is(AtomicOp.Maxu) { amoWordResult := Mux(oldWord > rhsWord, oldWord, rhsWord) }
  }

  private val amoDwordResult = WireDefault(rhsDword)
  switch(requestReg.atomicOp) {
    is(AtomicOp.Swap) { amoDwordResult := rhsDword }
    is(AtomicOp.Add) { amoDwordResult := oldDword + rhsDword }
    is(AtomicOp.Xor) { amoDwordResult := oldDword ^ rhsDword }
    is(AtomicOp.And) { amoDwordResult := oldDword & rhsDword }
    is(AtomicOp.Or) { amoDwordResult := oldDword | rhsDword }
    is(AtomicOp.Min) {
      amoDwordResult := Mux(oldDword.asSInt < rhsDword.asSInt, oldDword, rhsDword)
    }
    is(AtomicOp.Max) {
      amoDwordResult := Mux(oldDword.asSInt > rhsDword.asSInt, oldDword, rhsDword)
    }
    is(AtomicOp.Minu) {
      amoDwordResult := Mux(oldDword < rhsDword, oldDword, rhsDword)
    }
    is(AtomicOp.Maxu) {
      amoDwordResult := Mux(oldDword > rhsDword, oldDword, rhsDword)
    }
  }

  private val amoWriteValue =
    Mux(requestReg.size === MemSize.Word, amoWordResult.pad(dataBits), amoDwordResult)

  switch(state) {
    is(sIdle) {
      // Request capture is owned by io.request.fire above.
    }

    is(sDispatch) {
      responseData := 0.U
      responseFault := false.B

      when(requestReg.op === AetherMemOp.Read) {
        readPurpose := ReadNormal
        state := sReadAddress
      }.elsewhen(requestReg.op === AetherMemOp.Write) {
        // A normal store conservatively destroys a local LR reservation.
        reservationValid := false.B
        writeData := requestReg.wdata
        writeMask := requestReg.wmask
        writePurpose := WriteNormal
        awAccepted := false.B
        wAccepted := false.B
        state := sWriteIssue
      }.otherwise {
        val atomicSizeValid =
          requestReg.size === MemSize.Word || requestReg.size === MemSize.DWord

        when(!requestReg.attributes.supportsAtomic || !atomicSizeValid ||
             requestReg.atomicOp === AtomicOp.None) {
          responseFault := true.B
          reservationValid := false.B
          state := sRespond
        }.elsewhen(requestReg.atomicOp === AtomicOp.Lr) {
          readPurpose := ReadLr
          state := sReadAddress
        }.elsewhen(requestReg.atomicOp === AtomicOp.Sc) {
          val reservationMatches =
            reservationValid &&
              reservationAddress === requestReg.paddr &&
              reservationSize === requestReg.size
          reservationValid := false.B
          when(reservationMatches) {
            writeData := requestReg.wdata
            writeMask := requestReg.wmask
            writePurpose := WriteSc
            awAccepted := false.B
            wAccepted := false.B
            state := sWriteIssue
          }.otherwise {
            // AetherMem SC convention: 0 = success, nonzero = reservation fail.
            responseData := 1.U
            responseFault := false.B
            state := sRespond
          }
        }.otherwise {
          // AMO is serialized as one bridge-owned read/modify/write lifetime.
          reservationValid := false.B
          readPurpose := ReadAmo
          state := sReadAddress
        }
      }
    }

    is(sReadAddress) {
      when(io.axi.ar.fire) {
        state := sReadData
      }
    }

    is(sReadData) {
      when(io.axi.r.fire) {
        val responseOkay =
          io.axi.r.bits.resp === Axi4Resp.Okay ||
            io.axi.r.bits.resp === Axi4Resp.ExOkay
        val responseMatches = io.axi.r.bits.id === requestReg.txnId
        val readFault = !responseOkay || !io.axi.r.bits.last || !responseMatches

        when(readPurpose === ReadNormal) {
          responseData := shiftedReadData
          responseFault := readFault
          state := sRespond
        }.elsewhen(readPurpose === ReadLr) {
          responseData := shiftedReadData
          responseFault := readFault
          when(!readFault) {
            reservationValid := true.B
            reservationAddress := requestReg.paddr
            reservationSize := requestReg.size
          }
          state := sRespond
        }.otherwise {
          // Return old memory value to the LSU, but only after the write phase
          // below has completed successfully.
          responseData := shiftedReadData
          when(readFault) {
            responseFault := true.B
            state := sRespond
          }.otherwise {
            writeData := amoWriteValue
            writeMask := requestReg.wmask
            writePurpose := WriteAmo
            awAccepted := false.B
            wAccepted := false.B
            state := sWriteIssue
          }
        }
      }
    }

    is(sWriteIssue) {
      when(io.axi.aw.fire) {
        awAccepted := true.B
      }
      when(io.axi.w.fire) {
        wAccepted := true.B
      }

      when((awAccepted || io.axi.aw.fire) && (wAccepted || io.axi.w.fire)) {
        state := sWriteResponse
      }
    }

    is(sWriteResponse) {
      when(io.axi.b.fire) {
        val responseOkay =
          io.axi.b.bits.resp === Axi4Resp.Okay ||
            io.axi.b.bits.resp === Axi4Resp.ExOkay
        val responseMatches = io.axi.b.bits.id === requestReg.txnId
        responseFault := !responseOkay || !responseMatches
        when(writePurpose === WriteSc) {
          responseData := Mux(responseOkay && responseMatches, 0.U, 1.U)
        }.elsewhen(writePurpose === WriteNormal) {
          responseData := 0.U
        }
        // WriteAmo deliberately preserves the old value captured above.
        state := sRespond
      }
    }

    is(sRespond) {
      when(io.response.fire) {
        state := sIdle
      }
    }
  }

  when(io.axi.r.fire) {
    assert(io.axi.r.bits.last,
      "AetherSoC AXI bridge accepts single-beat reads only")
  }

  when(state =/= sIdle) {
    assert(normalReadCount === 0.U,
      "serialized AXI operation entered before concurrent reads drained")
  }
}
