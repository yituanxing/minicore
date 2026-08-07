package aethercore.core

import chisel3._

/**
  * Combinational read-only PTW arbiter. Data translation has deterministic
  * priority because a data walk stalls an architecturally older MEM operation,
  * while instruction translation is speculative and cancellable.
  */
class Sv32PtwArbiter(val paddrBits: Int = 34) extends Module {
  require(paddrBits >= 34, s"Sv32 PTW arbitration requires PA>=34, got $paddrBits")

  val io = IO(new Bundle {
    val dataValid = Input(Bool())
    val dataAddress = Input(UInt(paddrBits.W))
    val dataReady = Output(Bool())
    val dataRdata = Output(UInt(32.W))
    val dataFault = Output(Bool())

    val fetchValid = Input(Bool())
    val fetchAddress = Input(UInt(paddrBits.W))
    val fetchReady = Output(Bool())
    val fetchRdata = Output(UInt(32.W))
    val fetchFault = Output(Bool())

    val memoryValid = Output(Bool())
    val memoryAddress = Output(UInt(paddrBits.W))
    val memoryReady = Input(Bool())
    val memoryRdata = Input(UInt(32.W))
    val memoryFault = Input(Bool())
  })

  val chooseData = io.dataValid
  val chooseFetch = !chooseData && io.fetchValid

  io.memoryValid := chooseData || chooseFetch
  io.memoryAddress := Mux(chooseData, io.dataAddress, io.fetchAddress)

  io.dataReady := chooseData && io.memoryReady
  io.dataRdata := io.memoryRdata
  io.dataFault := chooseData && io.memoryFault

  io.fetchReady := chooseFetch && io.memoryReady
  io.fetchRdata := io.memoryRdata
  io.fetchFault := chooseFetch && io.memoryFault
}
