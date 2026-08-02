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

class MachineCsrFile(val isa: IsaConfig) extends Module {
  private val xlen = isa.xlen
  private val allBits = (BigInt(1) << xlen) - 1
  private val mstatusMask = (BigInt(1) << 3) | (BigInt(1) << 7) | (BigInt(3) << 11)
  private val mtvecMask = allBits & ~BigInt(3)
  private val mepcMask = allBits & ~(if (isa.hasC) BigInt(1) else BigInt(3))

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
  })

  val mstatus = RegInit(0.U(xlen.W))
  val mtvec = RegInit(0.U(xlen.W))
  val mscratch = RegInit(0.U(xlen.W))
  val mepc = RegInit(0.U(xlen.W))
  val mcause = RegInit(0.U(xlen.W))
  val mtval = RegInit(0.U(xlen.W))

  io.readData := 0.U
  io.readImplemented := true.B
  io.readWritable := true.B

  switch(io.readAddr) {
    is(MachineCsrAddress.Mstatus.U) { io.readData := mstatus }
    is(MachineCsrAddress.Misa.U) {
      io.readData := misaValue.U(xlen.W)
      io.readWritable := false.B
    }
    is(MachineCsrAddress.Mtvec.U) { io.readData := mtvec }
    is(MachineCsrAddress.Mscratch.U) { io.readData := mscratch }
    is(MachineCsrAddress.Mepc.U) { io.readData := mepc }
    is(MachineCsrAddress.Mcause.U) { io.readData := mcause }
    is(MachineCsrAddress.Mtval.U) { io.readData := mtval }
    otherwise {
      io.readData := 0.U
      io.readImplemented := false.B
      io.readWritable := false.B
    }
  }

  when(io.writeEnable) {
    switch(io.writeAddr) {
      is(MachineCsrAddress.Mstatus.U) {
        val requestedMpp = io.writeData(12, 11)
        val legalMpp = if (isa.hasS) {
          requestedMpp
        } else if (isa.hasU) {
          Mux(requestedMpp === 0.U || requestedMpp === 3.U, requestedMpp, 3.U)
        } else {
          3.U(2.W)
        }
        mstatus := (io.writeData & (mstatusMask & ~(BigInt(3) << 11)).U(xlen.W)) |
          (legalMpp << 11)
      }
      is(MachineCsrAddress.Mtvec.U) { mtvec := io.writeData & mtvecMask.U(xlen.W) }
      is(MachineCsrAddress.Mscratch.U) { mscratch := io.writeData }
      is(MachineCsrAddress.Mepc.U) { mepc := io.writeData & mepcMask.U(xlen.W) }
      is(MachineCsrAddress.Mcause.U) { mcause := io.writeData }
      is(MachineCsrAddress.Mtval.U) { mtval := io.writeData }
    }
  }
}
