package aethercore

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import aethercore.config.CoreProfiles
import aethercore.memory.{AetherDirectMappedReadCache, AetherMemRequest, AetherMemResponse}

class AetherDCacheSynthesisTop extends Module {
  private val config = CoreProfiles.rv64imasuSv39PmpSoftware
  private val addrBits = config.platform.paddrBits
  private val dataBits = config.platform.busDataBits
  private val txnIdBits = 2

  val io = IO(new Bundle {
    val upstreamRequest = Flipped(Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits)))
    val upstreamResponse = Decoupled(new AetherMemResponse(dataBits, txnIdBits))
    val downstreamRequest = Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits))
    val downstreamResponse = Flipped(Decoupled(new AetherMemResponse(dataBits, txnIdBits)))
  })

  val cache = Module(new AetherDirectMappedReadCache(
    addrBits = addrBits,
    dataBits = dataBits,
    txnIdBits = txnIdBits,
    entries = 64
  ))

  cache.io.upstreamRequest.valid := io.upstreamRequest.valid
  cache.io.upstreamRequest.bits := io.upstreamRequest.bits
  io.upstreamRequest.ready := cache.io.upstreamRequest.ready

  io.upstreamResponse.valid := cache.io.upstreamResponse.valid
  io.upstreamResponse.bits := cache.io.upstreamResponse.bits
  cache.io.upstreamResponse.ready := io.upstreamResponse.ready

  io.downstreamRequest.valid := cache.io.downstreamRequest.valid
  io.downstreamRequest.bits := cache.io.downstreamRequest.bits
  cache.io.downstreamRequest.ready := io.downstreamRequest.ready

  cache.io.downstreamResponse.valid := io.downstreamResponse.valid
  cache.io.downstreamResponse.bits := io.downstreamResponse.bits
  io.downstreamResponse.ready := cache.io.downstreamResponse.ready
}

object ElaborateV2DCacheSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherDCacheSynthesisTop,
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
