package aethercore.core.product

import aethercore.core.semantic.SystemSemanticEvaluator
import chisel3._
import chisel3.util._

/** One exact-head SYSTEM completion lane, with no mutable privileged state.
  *
  * The backend owns ROB lifetime and source PRF values. The shared semantic
  * evaluator owns CSR legality and architectural pending-effect calculation.
  * The single-entry queue snapshots the result at head.fire so changing time
  * CSR inputs cannot mutate a stalled completion. Neither this module nor the
  * backend performs any CSR write; effects commit only at retire.fire above.
  */
class ProductSystemUnit(val config: ProductCoreConfig) extends Module {
  private val g = config.geometry
  private val Xlen = 64
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val G = 16

  val io = IO(new Bundle {
    val head = Flipped(Decoupled(new ProductSystemHead(Xlen, I, P, G)))
    val csrReadAddr = Output(UInt(12.W))
    val csrReadData = Input(UInt(Xlen.W))
    val csrReadImplemented = Input(Bool())
    val csrReadWritable = Input(Bool())
    val currentPrivilege = Input(UInt(2.W))
    val completion = Decoupled(new ProductCompletion(Xlen, I, P, G))
  })

  private val evaluator = Module(new SystemSemanticEvaluator(
    config.software.isa, allowSfenceVma = true, allowWfi = true))
  evaluator.io.decoded := io.head.bits.decoded
  evaluator.io.producesValue := io.head.bits.hasDestination
  evaluator.io.rs1Value := io.head.bits.rs1Value
  evaluator.io.csrReadData := io.csrReadData
  evaluator.io.csrReadImplemented := io.csrReadImplemented
  evaluator.io.csrReadWritable := io.csrReadWritable
  evaluator.io.currentPrivilege := io.currentPrivilege
  io.csrReadAddr := evaluator.io.csrReadAddr

  private val snapshot = Module(new Queue(
    new ProductCompletion(Xlen, I, P, G), entries = 1, pipe = false, flow = false))
  snapshot.io.enq.valid := io.head.valid
  io.head.ready := snapshot.io.enq.ready
  snapshot.io.enq.bits := 0.U.asTypeOf(snapshot.io.enq.bits)
  snapshot.io.enq.bits.token := io.head.bits.token
  snapshot.io.enq.bits.destination := io.head.bits.destination
  // ProductCompletion.hasValue is the allocated destination identity,
  // including for illegal CSR faults; exception suppresses the actual PRF
  // write at retirement. Never drop a fault because the evaluator denied it.
  snapshot.io.enq.bits.hasValue := io.head.bits.hasDestination
  snapshot.io.enq.bits.value := evaluator.io.result.value
  snapshot.io.enq.bits.exception := evaluator.io.result.exception
  snapshot.io.enq.bits.privileged := evaluator.io.result.privileged
  io.completion <> snapshot.io.deq
}
