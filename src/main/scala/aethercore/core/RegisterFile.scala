package aethercore.core

import chisel3._
import chisel3.util._

class RegisterFile(val xlen: Int = 64) extends Module {
  require(xlen == 32 || xlen == 64, s"register-file XLEN must be 32 or 64, got $xlen")

  val io = IO(new Bundle {
    val rs1Addr = Input(UInt(5.W))
    val rs2Addr = Input(UInt(5.W))
    val rs1Data = Output(UInt(xlen.W))
    val rs2Data = Output(UInt(xlen.W))

    val writeEnable = Input(Bool())
    val rdAddr = Input(UInt(5.W))
    val rdData = Input(UInt(xlen.W))
  })

  // Two mirrored asynchronous 1R/1W memories preserve the architectural
  // 2R1W interface while giving FPGA synthesis an explicit LUTRAM-friendly
  // storage shape. The payload memories themselves do not need reset: the
  // resettable valid state makes unwritten entries architecturally read as 0.
  //
  // Both copies receive every architectural write. Each copy serves one read
  // port, avoiding the 32x64 Reg(Vec) + two wide dynamic-index mux trees that
  // otherwise dominate FPGA LUT area.
  private val rs1Mem = Mem(32, UInt(xlen.W))
  private val rs2Mem = Mem(32, UInt(xlen.W))
  private val valid = RegInit(VecInit(Seq.fill(32)(false.B)))
  private val doWrite = io.writeEnable && io.rdAddr =/= 0.U

  when(doWrite) {
    rs1Mem.write(io.rdAddr, io.rdData)
    rs2Mem.write(io.rdAddr, io.rdData)
    valid(io.rdAddr) := true.B
  }

  private val rs1Stored = rs1Mem(io.rs1Addr)
  private val rs2Stored = rs2Mem(io.rs2Addr)

  io.rs1Data := Mux(
    io.rs1Addr === 0.U,
    0.U(xlen.W),
    Mux(
      doWrite && io.rdAddr === io.rs1Addr,
      io.rdData,
      Mux(valid(io.rs1Addr), rs1Stored, 0.U(xlen.W))
    )
  )
  io.rs2Data := Mux(
    io.rs2Addr === 0.U,
    0.U(xlen.W),
    Mux(
      doWrite && io.rdAddr === io.rs2Addr,
      io.rdData,
      Mux(valid(io.rs2Addr), rs2Stored, 0.U(xlen.W))
    )
  )
}
