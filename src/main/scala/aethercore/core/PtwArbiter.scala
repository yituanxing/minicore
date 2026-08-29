package aethercore.core

import aethercore.config.PageTableGeometry
import chisel3._

/**
  * Geometry-driven read-only PTW arbiter shared by Sv32/Sv39/Sv48.
  *
  * Data translation has deterministic priority because it services an older
  * architectural MEM operation. Instruction translation is speculative and may
  * be cancelled by the frontend. PTE width follows the selected geometry, so
  * RV64 walks move full 64-bit PTEs without an Sv32-specific side channel.
  *
  * 通用 PTW 仲裁器：数据侧优先，取指侧可取消；PTE 宽度与 PageTableGeometry
  * 一致，因此 Sv32/Sv39/Sv48 共用同一仲裁逻辑。
  */
class PtwArbiter(
    val geometry: PageTableGeometry,
    val paddrBits: Int = -1
) extends Module {
  private val PhysicalBits =
    if (paddrBits > 0) paddrBits else geometry.architecturalPhysicalAddressBits
  require(
    PhysicalBits >= geometry.architecturalPhysicalAddressBits,
    s"${geometry.name} PTW arbitration requires PA>=${geometry.architecturalPhysicalAddressBits}, got $PhysicalBits"
  )

  val io = IO(new Bundle {
    val dataValid = Input(Bool())
    val dataAddress = Input(UInt(PhysicalBits.W))
    val dataReady = Output(Bool())
    val dataRdata = Output(UInt(geometry.pteBits.W))
    val dataFault = Output(Bool())

    val fetchValid = Input(Bool())
    val fetchAddress = Input(UInt(PhysicalBits.W))
    val fetchReady = Output(Bool())
    val fetchRdata = Output(UInt(geometry.pteBits.W))
    val fetchFault = Output(Bool())

    val memoryValid = Output(Bool())
    val memoryAddress = Output(UInt(PhysicalBits.W))
    val memoryIsFetch = Output(Bool())
    val memoryReady = Input(Bool())
    val memoryRdata = Input(UInt(geometry.pteBits.W))
    val memoryFault = Input(Bool())
  })

  val chooseData = io.dataValid
  val chooseFetch = !chooseData && io.fetchValid

  io.memoryValid := chooseData || chooseFetch
  io.memoryAddress := Mux(chooseData, io.dataAddress, io.fetchAddress)
  io.memoryIsFetch := chooseFetch

  io.dataReady := chooseData && io.memoryReady
  io.dataRdata := io.memoryRdata
  io.dataFault := chooseData && io.memoryFault

  io.fetchReady := chooseFetch && io.memoryReady
  io.fetchRdata := io.memoryRdata
  io.fetchFault := chooseFetch && io.memoryFault
}
