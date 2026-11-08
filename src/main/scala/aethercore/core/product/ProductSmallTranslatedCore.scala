package aethercore.core.product

import aethercore.common.PageTableReadBusIO
import aethercore.config.PageTableGeometry
import aethercore.core.{PmpConstants, PmpGeometry}
import aethercore.memory.{AetherMemRequest, AetherMemResponse, MemoryAttributes}
import chisel3._
import chisel3.util._

/** Real SmallOoO integer/M/LSQ plus the single shared data Sv39 translator.
  *
  * Decode/fetch and the privileged CSR owner are deliberately external at this
  * stage. The resolver consumes a read-only CSR context and the platform's PMA
  * lookup, while the LSQ alone owns physical AetherMem transaction IDs.
  * This is an executable data path, not yet a bootable product Linux SoC.
  */
class ProductSmallTranslatedCore(val config: ProductCoreConfig, val tlbEntries: Int = 8,
                                 val enableWarmSeed: Boolean = false)
    extends Module {
  private val g = config.geometry
  require(config.rtlElaboratable,
    "Product translated core requires an ISA, platform and port geometry supported by its RTL")
  private val enableFloating = config.software.isa.extensions.contains('F')
  private val geom = PageTableGeometry.Sv39
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val G = 16
  private val PA = config.platform.paddrBits
  private val T = g.loadQueueEntries + g.storeQueueEntries
  private val Tx = log2Ceil(T)
  private val pmpAddressBits = PmpGeometry(64, PA).encodedAddressBits

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new ProductDispatch(64)))
    val warmGpr = if (enableWarmSeed)
      Some(Input(Valid(new ProductWarmGprWrite))) else None
    val context = Input(new ProductDataTranslationContext(geom))
    val pmpConfig = Input(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Input(Vec(PmpConstants.MaxEntries, UInt(pmpAddressBits.W)))
    val translationFence = Input(Bool())
    val pte = new PageTableReadBusIO(PA, geom.pteBits)
    val pmaValid = Output(Bool())
    val pmaAddress = Output(UInt(PA.W))
    val pmaAttributes = Input(new MemoryAttributes)
    val memoryRequest = Decoupled(new AetherMemRequest(PA, 64, Tx))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(64, Tx)))
    // The eventual ProductPagedCore connects these to its sole MachineCsrFile.
    // This data/execute composition never creates a second CSR authority.
    val systemCsrReadAddr = Output(UInt(12.W))
    val systemCsrReadData = Input(UInt(64.W))
    val systemCsrReadImplemented = Input(Bool())
    val systemCsrReadWritable = Input(Bool())
    val systemCurrentPrivilege = Input(UInt(2.W))
    val fpDynamicRoundingMode = if (enableFloating) Some(Input(UInt(3.W))) else None
    val retire = Decoupled(new ProductRetirement(64, I, P, G, PA))
    val redirect = Valid(new ProductRedirect(64, I, G))
    val trap = Valid(new ProductRetirement(64, I, P, G, PA))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
    val recovering = Output(Bool())
    val loadCount = Output(UInt(log2Ceil(g.loadQueueEntries + 1).W))
    val storeCount = Output(UInt(log2Ceil(g.storeQueueEntries + 1).W))
    val outstandingTransactions = Output(UInt(log2Ceil(T + 1).W))
    val resolverPending = Output(UInt(log2Ceil(T + 1).W))
    val resolverWalking = Output(Bool())
    val outOfOrderCompletion = Output(Bool())
    val staleCompletion = Output(Bool())
  })

  private val core = Module(new ProductSmallMemoryCore(config, enableWarmSeed))
  if (enableWarmSeed) core.io.warmGpr.get := io.warmGpr.get
  private val resolver = Module(new ProductDataResolver(config, tlbEntries))

  core.io.dispatch.valid := io.dispatch.valid
  core.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := core.io.dispatch.ready
  resolver.io.memoryIssue <> core.io.memoryExecute
  core.io.memoryResourceAvailable := resolver.io.resourceAvailable
  resolver.io.robHead := core.io.memoryHead
  resolver.io.kill := core.io.resolverKill
  resolver.io.translationFence := io.translationFence
  resolver.io.context := io.context
  resolver.io.pmpConfig := io.pmpConfig
  resolver.io.pmpAddress := io.pmpAddress
  core.io.memoryResolved <> resolver.io.resolved

  io.pte.valid := resolver.io.pte.valid
  io.pte.addr := resolver.io.pte.addr
  resolver.io.pte.ready := io.pte.ready
  resolver.io.pte.rdata := io.pte.rdata
  resolver.io.pte.fault := io.pte.fault
  io.pmaValid := resolver.io.pmaValid
  io.pmaAddress := resolver.io.pmaAddress
  resolver.io.pmaAttributes := io.pmaAttributes

  io.memoryRequest.valid := core.io.memoryRequest.valid
  io.memoryRequest.bits := core.io.memoryRequest.bits
  core.io.memoryRequest.ready := io.memoryRequest.ready
  core.io.memoryResponse.valid := io.memoryResponse.valid
  core.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := core.io.memoryResponse.ready

  io.systemCsrReadAddr := core.io.systemCsrReadAddr
  core.io.systemCsrReadData := io.systemCsrReadData
  core.io.systemCsrReadImplemented := io.systemCsrReadImplemented
  core.io.systemCsrReadWritable := io.systemCsrReadWritable
  core.io.systemCurrentPrivilege := io.systemCurrentPrivilege
  if (enableFloating) core.io.fpDynamicRoundingMode.get := io.fpDynamicRoundingMode.get

  io.retire.valid := core.io.retire.valid
  io.retire.bits := core.io.retire.bits
  core.io.retire.ready := io.retire.ready
  io.redirect := core.io.redirect
  io.trap := core.io.trap
  io.occupancy := core.io.occupancy
  io.recovering := core.io.recovering
  io.loadCount := core.io.loadCount
  io.storeCount := core.io.storeCount
  io.outstandingTransactions := core.io.outstandingTransactions
  io.resolverPending := resolver.io.pending
  io.resolverWalking := resolver.io.walking
  io.outOfOrderCompletion := core.io.outOfOrderCompletion
  io.staleCompletion := core.io.staleCompletion
}
