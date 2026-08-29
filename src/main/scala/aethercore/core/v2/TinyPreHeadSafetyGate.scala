package aethercore.core.v2

import chisel3._
import aethercore.memory.{AetherMemOp, MemoryAttributes}

/** Fail-closed externalization policy for a memory lifetime started pre-head. */
class TinyPreHeadSafetyGate extends Module {
  val io = IO(new Bundle {
    val speculative = Input(Bool())
    val ptePermit = Output(Bool())
    val memoryValid = Input(Bool())
    val memoryOp = Input(AetherMemOp())
    val attributes = Input(new MemoryAttributes)
    val memoryPermit = Output(Bool())
  })

  // Replaying this physical read after a squash is architecturally harmless.
  // Device/ordered/side-effecting reads and all writes/atomics remain head-only.
  val replaySafeRead =
    io.memoryOp === AetherMemOp.Read &&
      io.attributes.idempotent &&
      !io.attributes.sideEffecting &&
      !io.attributes.ordered

  // Speculative page walks are intentionally forbidden in the first slice.
  io.ptePermit := !io.speculative
  io.memoryPermit := !io.speculative || (io.memoryValid && replaySafeRead)
}
