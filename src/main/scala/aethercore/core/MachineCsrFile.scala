package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.config.IsaConfig

object MachineCsrAddress {
  val Mstatus: Int = 0x300
  val Misa: Int = 0x301
  val Mtvec: Int = 0x305
  val Mscratch: Int = 0x340
  val Mepc: Int = 0x341
  val Mcause: Int = 0x342
  val Mtval: Int = 0x343
}

object MachineCsrWarl {
  def canonicalize(isa: IsaConfig, address: UInt, data: UInt): UInt = {
    val xlen = isa.xlen
    val allBits = (BigInt(1) << xlen) - 1
    val mstatusNonMppMask = (BigInt(1) << 3) | (BigInt(1) << 7)
    val mtvecMask = allBits & ~BigInt(3)
    val mepcMask = allBits & ~(if (isa.hasC) BigInt(1) else BigInt(3))

    val result = WireDefault(data)
    switch(address) {
      is(MachineCsrAddress.Mstatus.U) {
        val requestedMpp = data(12, 11)
        val legalMpp = if (isa.hasS && isa.hasU) {
          Mux(
            requestedMpp === 0.U || requestedMpp === 1.U || requestedMpp === 3.U,
            requestedMpp,
            3.U
          )
        } else if (isa.hasS) {
          Mux(requestedMpp === 1.U || requestedMpp === 3.U, requestedMpp, 3.U)
        } else if (isa.hasU) {
          Mux(requestedMpp === 0.U || requestedMpp === 3.U, requestedMpp, 3.U)
        } else {
          3.U(2.W)
        }
        result := (data & mstatusNonMppMask.U(xlen.W)) | (legalMpp << 11)
      }
      is(MachineCsrAddress.Mtvec.U) { result := data & mtvecMask.U(xlen.W) }
      is(MachineCsrAddress.Mepc.U) { result := data & mepcMask.U(xlen.W) }
    }
    result
  }
}

class MachineCsrFile(val isa: IsaConfig) extends Module {
  private val xlen = isa.xlen
  private val allBits = (BigInt(1) << xlen) - 1
  private val mstatusMie = BigInt(1) << 3
  private val mstatusMpie = BigInt(1) << 7
  private val mstatusMpp = BigInt(3) << 11
  private val mstatusTransitionMask = mstatusMie | mstatusMpie | mstatusMpp
  private val mstatusTransitionPreserveMask = allBits & ~mstatusTransitionMask
  private val leastPrivilege = if (isa.hasU) BigInt(0) else if (isa.hasS) BigInt(1) else BigInt(3)

  private val misaValue = {
    val mxl = if (xlen == 32) BigInt(1) else BigInt(2)
    val extensionBits = isa.extensions.foldLeft(BigInt(0)) { (bits, extension) =>
      val index = extension.toUpper - 'A'
      require(index >= 0 && index < 26, s"unsupported misa extension name: $extension")
      bits | (BigInt(1) << index)
    }
    (mxl << (xlen - 2)) | extensionBits
  }

  val io = IO(new Bundle {
    val readAddr = Input(UInt(12.W))
    val readData = Output(UInt(xlen.W))
    val readImplemented = Output(Bool())
    val readWritable = Output(Bool())

    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(12.W))
    val writeData = Input(UInt(xlen.W))

    val trapEnter = Input(Bool())
    val trapPc = Input(UInt(xlen.W))
    val trapCause = Input(UInt(xlen.W))
    val trapValue = Input(UInt(xlen.W))
    val trapVector = Output(UInt(xlen.W))

    val trapReturn = Input(Bool())
    val returnPc = Output(UInt(xlen.W))
  })

  val mstatus = RegInit(0.U(xlen.W))
  val mtvec = RegInit(0.U(xlen.W))
  val mscratch = RegInit(0.U(xlen.W))
  val mepc = RegInit(0.U(xlen.W))
  val mcause = RegInit(0.U(xlen.W))
  val mtval = RegInit(0.U(xlen.W))

  io.trapVector := mtvec
  io.returnPc := mepc
  io.readData := 0.U
  io.readImplemented := false.B
  io.readWritable := false.B

  switch(io.readAddr) {
    is(MachineCsrAddress.Mstatus.U) {
      io.readData := mstatus
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Misa.U) {
      io.readData := misaValue.U(xlen.W)
      io.readImplemented := true.B
    }
    is(MachineCsrAddress.Mtvec.U) {
      io.readData := mtvec
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mscratch.U) {
      io.readData := mscratch
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mepc.U) {
      io.readData := mepc
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mcause.U) {
      io.readData := mcause
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mtval.U) {
      io.readData := mtval
      io.readImplemented := true.B
      io.readWritable := true.B
    }
  }

  val canonicalWriteData = MachineCsrWarl.canonicalize(isa, io.writeAddr, io.writeData)
  val canonicalTrapPc = MachineCsrWarl.canonicalize(isa, MachineCsrAddress.Mepc.U, io.trapPc)
  val trapMstatus =
    (mstatus & mstatusTransitionPreserveMask.U(xlen.W)) |
      (mstatus(3).asUInt << 7) |
      mstatusMpp.U(xlen.W)
  val returnMstatus =
    (mstatus & mstatusTransitionPreserveMask.U(xlen.W)) |
      (mstatus(7).asUInt << 3) |
      mstatusMpie.U(xlen.W) |
      (leastPrivilege << 11).U(xlen.W)

  when(io.trapEnter) {
    mstatus := trapMstatus
    mepc := canonicalTrapPc
    mcause := io.trapCause
    mtval := io.trapValue
  }.elsewhen(io.trapReturn) {
    mstatus := returnMstatus
  }.elsewhen(io.writeEnable) {
    switch(io.writeAddr) {
      is(MachineCsrAddress.Mstatus.U) { mstatus := canonicalWriteData }
      is(MachineCsrAddress.Mtvec.U) { mtvec := canonicalWriteData }
      is(MachineCsrAddress.Mscratch.U) { mscratch := canonicalWriteData }
      is(MachineCsrAddress.Mepc.U) { mepc := canonicalWriteData }
      is(MachineCsrAddress.Mcause.U) { mcause := canonicalWriteData }
      is(MachineCsrAddress.Mtval.U) { mtval := canonicalWriteData }
    }
  }
}
