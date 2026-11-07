package aethercore.core.product

import chisel3._
import chisel3.util._

/** One physical-register value owner with FPGA-friendly asynchronous read
  * ports and a single synchronous completion write.
  *
  * Only the small validity mask is reset. Unwritten RAM contents never become
  * architectural values: the initial committed RAT maps x1..x31/f0..f31 to
  * unwritten entries, which therefore read as zero. A freshly allocated tag
  * is invalidated at dispatch and becomes readable only after its exact
  * lifetime completes. Dependency readiness remains with the rename/ROB
  * owner, not duplicated inside this value store.
  *
  * Async reads preserve the existing one-cycle issue/operand semantics. A
  * future synchronous BRAM implementation would need a separate operand-fetch
  * pipeline and must not be substituted here without that redesign.
  */
class ProductPhysicalValueStore(
    val entries: Int,
    val readPorts: Int,
    val immutableZero: Boolean = false
) extends Module {
  require(entries > 32 && readPorts >= 1)
  private val A = log2Ceil(entries)
  private val Xlen = 64

  val io = IO(new Bundle {
    val readAddress = Input(Vec(readPorts, UInt(A.W)))
    val readValue = Output(Vec(readPorts, UInt(Xlen.W)))
    val invalidate = Flipped(Valid(UInt(A.W)))
    val write = Flipped(Valid(new Bundle {
      val index = UInt(A.W)
      val value = UInt(Xlen.W)
    }))
  })

  private val values = Mem(entries, UInt(Xlen.W))
  private val initialized = RegInit(VecInit(Seq.fill(entries)(false.B)))

  for (r <- 0 until readPorts) {
    val address = io.readAddress(r)
    io.readValue(r) := Mux(
      initialized(address) && (if (immutableZero) address =/= 0.U else true.B),
      values(address), 0.U(Xlen.W))
  }

  // Dispatch can invalidate a different physical register while an older
  // completion writes back. A free tag cannot also be a live completion tag.
  when(io.invalidate.valid) {
    initialized(io.invalidate.bits) := false.B
  }
  when(io.write.valid) {
    values.write(io.write.bits.index, io.write.bits.value)
    initialized(io.write.bits.index) := true.B
  }
  when(io.invalidate.valid && io.write.valid) {
    assert(io.invalidate.bits =/= io.write.bits.index,
      "fresh physical allocation cannot alias a live completion")
  }
  if (immutableZero) {
    when(io.write.valid) {
      assert(io.write.bits.index =/= 0.U, "integer x0 physical value is immutable")
    }
    when(io.invalidate.valid) {
      assert(io.invalidate.bits =/= 0.U, "integer x0 physical tag cannot be allocated")
    }
  }
}
