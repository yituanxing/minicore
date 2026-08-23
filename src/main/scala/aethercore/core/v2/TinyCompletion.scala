package aethercore.core.v2

import chisel3._
import chisel3.util._

/**
  * Narrow A8 completion merge seam.
  *
  * Producers own their response until a Decoupled handshake. This arbiter owns
  * only which ready response may use the single ROB completion port this cycle;
  * it does not know about execution classes, ROB age, CSR semantics, memory
  * ordering, or scheduler policy.
  *
  * A round-robin policy is deliberate. Once selective issue permits several
  * variable-latency producers to finish independently, a fixed-priority merge
  * can starve an older held response behind a stream of short-latency results.
  * A8 keeps one completion accepted per cycle, but requires bounded progress
  * between simultaneously pending sources.
  */
class TinyCompletionArbiter(val xlen: Int, val sourceCount: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"completion-arbiter XLEN must be 32 or 64, got $xlen")
  require(sourceCount >= 2, s"completion arbiter needs at least two sources, got $sourceCount")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val in = Flipped(Vec(
      sourceCount,
      Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    ))
    val out = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
  })

  private val arbiter = Module(new RRArbiter(
    new ExecutionResponse(xlen, IdentityBits, GenerationBits),
    sourceCount
  ))

  for (index <- 0 until sourceCount) {
    arbiter.io.in(index) <> io.in(index)
  }
  io.out <> arbiter.io.out
}
