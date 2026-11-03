package aethercore.core.product

import chisel3._
import chisel3.util._
import aethercore.core.semantic.MemoryOperationKind
import aethercore.memory.{AetherMemRequest, AetherMemResponse}

/** One SmallOoO backend plus 4 FU completion ports, physical LSQ and a
  * separately owned VA->PA/PMP/PMA resolver.
  *
  * The caller MUST resolve memoryExecute to physical paddr/attributes/fault
  * before returning memoryResolved. This module cannot fake a Sv39 walker,
  * PMP privilege check, PMA attributes or Linux boot support.
  */
class ProductSmallMemoryCore(val config: ProductCoreConfig) extends Module {
  private val g = config.geometry
  require(g.decodeWidth == 1 && g.dispatchWidth == 1 && g.issueWidth == 1 &&
    g.retireWidth == 1, "physical SmallOoO frontend is one-wide")
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val G = 16
  private val PA = config.platform.paddrBits
  private val Tx = log2Ceil(g.loadQueueEntries + g.storeQueueEntries)

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new ProductDispatch(64)))
    // To the external translation/PMP/PMA owner: VADDR = lhs+immediate,
    // STORE data = issue.storeData. The resolver may stall arbitrarily.
    val memoryExecute = Decoupled(new ProductIssue(64, I, P, G))
    // Translation capacity must NOT be inferred from Decoupled.ready;
    // otherwise issue.valid would combinationally depend on ready.
    val memoryResourceAvailable = Input(Bool())
    // Exact ROB head for a shared translator: only the head may start a
    // speculative TLB-miss page-table walk. Non-head TLB hits remain legal.
    val memoryHead = Valid(new ProductRobId(I, G))
    val resolverKill = Valid(new ProductRobId(I, G))
    // From that owner: the identical lifetime's effective VA, resolved PA,
    // attributes or precise fault. VA must survive to retirement for stval;
    // the LSQ must never substitute PA for an architectural fault address.
    val memoryResolved = Flipped(Decoupled(new ProductResolvedMem(PA, I, G)))
    val memoryRequest = Decoupled(new AetherMemRequest(PA, 64, Tx))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(64, Tx)))
    // Read-only access to the externally owned CSR bank. The system lane
    // evaluates at exact ROB head, but never writes privileged state here.
    val systemCsrReadAddr = Output(UInt(12.W))
    val systemCsrReadData = Input(UInt(64.W))
    val systemCsrReadImplemented = Input(Bool())
    val systemCsrReadWritable = Input(Bool())
    val systemCurrentPrivilege = Input(UInt(2.W))
    val retire = Decoupled(new ProductRetirement(64, I, P, G))
    val redirect = Valid(new ProductRedirect(64, I, G))
    val trap = Valid(new ProductRetirement(64, I, P, G))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
    val recovering = Output(Bool())
    val loadCount = Output(UInt(log2Ceil(g.loadQueueEntries + 1).W))
    val storeCount = Output(UInt(log2Ceil(g.storeQueueEntries + 1).W))
    val outstandingTransactions = Output(UInt(log2Ceil(g.loadQueueEntries + g.storeQueueEntries + 1).W))
    val outOfOrderCompletion = Output(Bool())
    val staleCompletion = Output(Bool())
  })

  private val backend = Module(new ProductSmallOoOBackend(g, allowSystem = true))
  private val integer = Module(new ProductIntegerBranchUnit(g, config.software.isa.hasC))
  private val m = Module(new ProductMUnit(g))
  private val lsq = Module(new ProductPhysicalLsq(g, PA))
  private val system = Module(new ProductSystemUnit(config))
  private val completion = Module(new RRArbiter(new ProductCompletion(64, I, P, G), 4))

  backend.io.dispatch.valid := io.dispatch.valid
  backend.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := backend.io.dispatch.ready

  lsq.io.reserve <> backend.io.memoryReserve
  lsq.io.robHead := backend.io.memoryHead
  lsq.io.kill := backend.io.memoryKill
  io.memoryHead := backend.io.memoryHead
  io.resolverKill := backend.io.memoryKill

  // External resolver is explicitly separate: no implicit identity mapping.
  io.memoryExecute.valid := backend.io.issue.valid &&
    backend.io.issue.bits.memoryKind =/= MemoryOperationKind.None
  io.memoryExecute.bits := backend.io.issue.bits
  backend.io.memoryAvailable := io.memoryResourceAvailable

  lsq.io.resolve.valid := io.memoryResolved.valid
  lsq.io.resolve.bits := io.memoryResolved.bits
  io.memoryResolved.ready := lsq.io.resolve.ready

  io.memoryRequest.valid := lsq.io.memoryRequest.valid
  io.memoryRequest.bits := lsq.io.memoryRequest.bits
  lsq.io.memoryRequest.ready := io.memoryRequest.ready
  lsq.io.memoryResponse.valid := io.memoryResponse.valid
  lsq.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := lsq.io.memoryResponse.ready

  system.io.head <> backend.io.systemHead
  io.systemCsrReadAddr := system.io.csrReadAddr
  system.io.csrReadData := io.systemCsrReadData
  system.io.csrReadImplemented := io.systemCsrReadImplemented
  system.io.csrReadWritable := io.systemCsrReadWritable
  system.io.currentPrivilege := io.systemCurrentPrivilege

  backend.io.integerAvailable := integer.io.available
  backend.io.mAvailable := m.io.available
  private val issueMemory = backend.io.issue.bits.memoryKind =/= MemoryOperationKind.None
  private val issueM = ProductMOperation.isM(backend.io.issue.bits.aluOp)
  integer.io.request.valid := backend.io.issue.valid && !issueMemory && !issueM
  integer.io.request.bits := backend.io.issue.bits
  m.io.request.valid := backend.io.issue.valid && !issueMemory && issueM
  m.io.request.bits := backend.io.issue.bits
  backend.io.issue.ready := Mux(issueMemory, io.memoryExecute.ready,
    Mux(issueM, m.io.request.ready, integer.io.request.ready))

  completion.io.in(0) <> integer.io.response
  completion.io.in(1) <> m.io.response
  completion.io.in(2) <> lsq.io.completion
  completion.io.in(3) <> system.io.completion
  backend.io.completion <> completion.io.out

  io.retire.valid := backend.io.retire.valid
  io.retire.bits := backend.io.retire.bits
  backend.io.retire.ready := io.retire.ready
  io.redirect := backend.io.redirect
  io.trap := backend.io.trap
  io.occupancy := backend.io.occupancy
  io.recovering := backend.io.recovering
  io.loadCount := lsq.io.loadCount
  io.storeCount := lsq.io.storeCount
  io.outstandingTransactions := lsq.io.outstanding
  io.outOfOrderCompletion := backend.io.outOfOrderCompletion
  io.staleCompletion := backend.io.staleCompletion
}
