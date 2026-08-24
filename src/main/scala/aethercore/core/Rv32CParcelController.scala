package aethercore.core

import chisel3._
import chisel3.util.Cat

/** Assemble one architectural instruction from 16-bit RISC-V instruction parcels.
  *
  * Translation, PMP and physical-memory access stay outside this module. The
  * caller presents the result of exactly one parcel request at a time. A
  * compressed instruction completes after the first parcel; a 32-bit
  * instruction latches the first parcel and requests PC+2 next. This keeps
  * second-parcel page/access faults precise without teaching the MMU about
  * instruction length. The same parcel lifetime contract is used at RV32 and
  * RV64; only compressed semantic expansion is XLEN-dependent.
  */
class RvcParcelController(val xlen: Int = 32) extends Module {
  require(Set(32, 64).contains(xlen), s"compressed parcel control requires XLEN 32 or 64, got $xlen")

  val io = IO(new Bundle {
    val instructionPc = Input(UInt(xlen.W))
    val kill = Input(Bool())
    val advance = Input(Bool())

    val parcelResponseValid = Input(Bool())
    val parcelBits = Input(UInt(16.W))
    val parcelPageFault = Input(Bool())
    val parcelAccessFault = Input(Bool())

    val parcelRequestAddress = Output(UInt(xlen.W))
    val parcelResponseReady = Output(Bool())

    val instructionValid = Output(Bool())
    val instruction = Output(UInt(32.W))
    val rawInstruction = Output(UInt(32.W))
    val instructionBytes = Output(UInt(3.W))
    val faultAddress = Output(UInt(xlen.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
  })

  val secondParcelPending = RegInit(false.B)
  val firstParcel = RegInit(0.U(16.W))

  val decompressor = Module(new RvcDecompressor(xlen))
  decompressor.io.raw := io.parcelBits

  val parcelFault = io.parcelPageFault || io.parcelAccessFault
  val firstParcelIsCompressed = io.parcelBits(1, 0) =/= "b11".U
  val completesInstruction = secondParcelPending || parcelFault || firstParcelIsCompressed
  val assembled32 = Cat(io.parcelBits, firstParcel)

  io.parcelRequestAddress := Mux(
    secondParcelPending,
    io.instructionPc + 2.U,
    io.instructionPc
  )
  io.parcelResponseReady := io.parcelResponseValid && io.advance && !io.kill

  io.instructionValid := io.parcelResponseValid && !io.kill && completesInstruction
  io.rawInstruction := Mux(
    secondParcelPending,
    assembled32,
    Cat(0.U(16.W), io.parcelBits)
  )
  io.instruction := Mux(
    secondParcelPending,
    assembled32,
    Mux(parcelFault, 0.U, Mux(decompressor.io.legal, decompressor.io.expanded, 0.U))
  )
  io.instructionBytes := Mux(secondParcelPending, 4.U, 2.U)
  io.faultAddress := Mux(
    secondParcelPending,
    io.instructionPc + 2.U,
    io.instructionPc
  )
  io.pageFault := io.parcelPageFault
  io.accessFault := io.parcelAccessFault

  when(io.kill) {
    secondParcelPending := false.B
  }.elsewhen(io.parcelResponseReady) {
    when(secondParcelPending) {
      secondParcelPending := false.B
    }.elsewhen(!parcelFault && !firstParcelIsCompressed) {
      firstParcel := io.parcelBits
      secondParcelPending := true.B
    }
  }
}

/** Compatibility wrapper retaining the historical source name while all new
  * architecture work targets the XLEN-aware RvcParcelController contract.
  */
class Rv32CParcelController(xlen: Int = 32) extends RvcParcelController(xlen)
