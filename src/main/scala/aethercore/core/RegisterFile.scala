package aethercore.core

import chisel3._
import chisel3.util._

class RegisterFile extends Module {
  val io = IO(new Bundle {
    val rs1Addr = Input(UInt(5.W))
    val rs2Addr = Input(UInt(5.W))
    val rs1Data = Output(UInt(64.W))
    val rs2Data = Output(UInt(64.W))

    val writeEnable = Input(Bool())
    val rdAddr = Input(UInt(5.W))
    val rdData = Input(UInt(64.W))
  })

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(64.W))))
  val doWrite = io.writeEnable && io.rdAddr =/= 0.U

  when(doWrite) {
    regs(io.rdAddr) := io.rdData
  }

  io.rs1Data := Mux(
    io.rs1Addr === 0.U,
    0.U,
    Mux(doWrite && io.rdAddr === io.rs1Addr, io.rdData, regs(io.rs1Addr))
  )
  io.rs2Data := Mux(
    io.rs2Addr === 0.U,
    0.U,
    Mux(doWrite && io.rdAddr === io.rs2Addr, io.rdData, regs(io.rs2Addr))
  )
}
