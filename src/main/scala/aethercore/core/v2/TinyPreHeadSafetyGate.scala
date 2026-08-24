package aethercore.core.v2

import chisel3._
import aethercore.memory.{AetherMemOp, MemoryAttributes}

/**
  * Pure combinational externalization policy for a conservative pre-head Load.
  *
  * The gate owns no transaction state and carries no uOp. It answers only two
  * questions for the already-selected blocking-LSU lifetime:
  * - may page-table memory traffic leave the backend now?
  * - may the resolved physical AetherMem request leave the backend now?
  *
  * Exact-head lifetimes pass unchanged. A speculative lifetime suppresses PTW
  * traffic and may emit only an idempotent, non-side-effecting, non-ordered Read.
  */
class TinyPreHeadSafetyGate extends Module {
  val io = IO(new Bundle {
    val speculative = Input(Bool())
    val ptePermit = Output(Bool())

    val memoryValid = Input(Bool())
    val memoryOp = Input(AetherMemOp())
    val attributes = Input(new MemoryAttributes)
    val memoryPermit = Output(Bool())
  })

  val replaySafeRead =
    io.memoryOp === AetherMemOp.Read &&
      io.attributes.idempotent &&
      !io.attributes.sideEffecting &&
      !io.attributes.ordered

  io.ptePermit := !io.speculative
  io.memoryPermit := !io.speculative || (io.memoryValid && replaySafeRead)
}
