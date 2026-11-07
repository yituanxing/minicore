package aethercore.core.product

import aethercore.core.semantic.MemoryOperationKind
import chisel3._
import chisel3.util._

/** Historical integer+M+branch integration slice kept as a test fixture.
  *
  * Production Product CPU composition is ProductSmallMemoryCore, which owns
  * the physical LSQ, system lane and floating execution path. This fixture
  * still verifies that independent integer/M units can complete out of order
  * and that the shared ROB preserves retirement and redirect semantics.
  */
class ProductSmallIntegerCore(val config: ProductCoreConfig) extends Module {
  private val g = config.geometry
  private val PA = config.platform.paddrBits
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val G = 16

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new ProductDispatch(64)))
    val retire = Decoupled(new ProductRetirement(64, I, P, G, PA))
    val redirect = Valid(new ProductRedirect(64, I, G))
    val trap = Valid(new ProductRetirement(64, I, P, G, PA))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
    val unsupported = Output(Bool())
    val mUnitNeeded = Output(Bool())
    val mBusy = Output(Bool())
    val outOfOrderCompletion = Output(Bool())
    val staleCompletion = Output(Bool())
  })

  private val backend = Module(new ProductSmallOoOBackend(g, paddrBits = PA))
  private val integer = Module(new ProductIntegerBranchUnit(g, config.software.isa.hasC, PA))
  private val m = Module(new ProductMUnit(g, PA))
  private val completion = Module(new RRArbiter(new ProductCompletion(64, I, P, G, PA), 2))
  private val completionHold = Module(new Queue(
    new ProductCompletion(64, I, P, G, PA), entries = 1, pipe = false, flow = false))

  backend.io.dispatch.valid := io.dispatch.valid
  backend.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := backend.io.dispatch.ready

  backend.io.integerAvailable := integer.io.available
  backend.io.mAvailable := m.io.available
  // No LSQ/resolver or privileged lane exists in this historical test slice.
  backend.io.memoryAvailable := false.B
  backend.io.memoryReserve.ready := false.B
  backend.io.systemHead.ready := false.B
  private val issueM = ProductMOperation.isM(backend.io.issue.bits.aluOp)
  integer.io.request.valid := backend.io.issue.valid && !issueM
  integer.io.request.bits := backend.io.issue.bits
  m.io.request.valid := backend.io.issue.valid && issueM
  m.io.request.bits := backend.io.issue.bits
  backend.io.issue.ready := Mux(issueM, m.io.request.ready, integer.io.request.ready)

  completion.io.in(0) <> integer.io.response
  completion.io.in(1) <> m.io.response
  completionHold.io.enq <> completion.io.out
  backend.io.completion <> completionHold.io.deq

  io.retire.valid := backend.io.retire.valid
  io.retire.bits := backend.io.retire.bits
  backend.io.retire.ready := io.retire.ready
  io.redirect := backend.io.redirect
  io.trap := backend.io.trap
  io.occupancy := backend.io.occupancy
  io.unsupported := backend.io.unsupported ||
    (io.dispatch.valid && io.dispatch.bits.decoded.memory.kind =/= MemoryOperationKind.None)
  io.mUnitNeeded := false.B
  io.mBusy := !m.io.available
  io.outOfOrderCompletion := backend.io.outOfOrderCompletion
  io.staleCompletion := backend.io.staleCompletion
}
