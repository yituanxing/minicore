package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.Immediate

class ImmediateProbe(val xlen: Int) extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val i = Output(UInt(xlen.W))
    val s = Output(UInt(xlen.W))
    val b = Output(UInt(xlen.W))
    val u = Output(UInt(xlen.W))
    val j = Output(UInt(xlen.W))
  })

  io.i := Immediate.i(io.inst, xlen)
  io.s := Immediate.s(io.inst, xlen)
  io.b := Immediate.b(io.inst, xlen)
  io.u := Immediate.u(io.inst, xlen)
  io.j := Immediate.j(io.inst, xlen)
}

class ImmediateSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Immediate"

  it should "sign extend architectural immediates to RV32" in {
    simulate(new ImmediateProbe(32)) { dut =>
      dut.io.inst.poke("hfff00093".U) // addi x1, x0, -1
      dut.io.i.expect("hffffffff".U)

      dut.io.inst.poke("h80000037".U) // lui x0, 0x80000
      dut.io.u.expect("h80000000".U)
    }
  }

  it should "preserve the existing RV64 sign extension" in {
    simulate(new ImmediateProbe(64)) { dut =>
      dut.io.inst.poke("hfff00093".U)
      dut.io.i.expect("hffffffffffffffff".U)

      dut.io.inst.poke("h80000037".U)
      dut.io.u.expect("hffffffff80000000".U)
    }
  }
}
