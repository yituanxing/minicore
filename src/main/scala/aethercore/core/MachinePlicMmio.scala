package aethercore.core

import chisel3._
import chisel3.util._

object MachinePlicMmioMap {
  val PriorityBase: Int = 0x000000
  val Pending: Int = 0x001000
  val Enable: Int = 0x002000
  val Threshold: Int = 0x200000
  val ClaimComplete: Int = 0x200004

  // QEMU virt hart0 Supervisor context used by the pinned N5 NuttX image.
  val SupervisorEnable: Int = 0x002080
  val SupervisorThreshold: Int = 0x201000
  val SupervisorClaimComplete: Int = 0x201004

  def priority(sourceId: Int): Int = PriorityBase + sourceId * 4
  def pendingWord(word: Int): Int = Pending + word * 4
  def enableWord(base: Int, word: Int): Int = base + word * 4
}

/** Bus-facing single-context PLIC register map.
  *
  * Source IDs remain architectural one-based IDs, while the underlying
  * MachinePlic keeps compact zero-based source state. The MMIO shell supports
  * two architectural pending/enable words (sources 1..63) so the real NuttX
  * QEMU-virt profile can initialize its 52 PLIC sources without a synthetic
  * bus fault. Context offsets are constructor parameters: existing users keep
  * the historical machine-context defaults, while N5 can select hart0's
  * Supervisor context at 0x2080 / 0x201000 / 0x201004.
  */
class MachinePlicMmio(
    val sourceCount: Int = 8,
    val priorityBits: Int = 3,
    val addressBits: Int = 24,
    val enableBase: Int = MachinePlicMmioMap.Enable,
    val thresholdOffset: Int = MachinePlicMmioMap.Threshold,
    val claimCompleteOffset: Int = MachinePlicMmioMap.ClaimComplete
) extends Module {
  require(sourceCount > 0 && sourceCount <= 63,
    s"two-word one-based PLIC profile supports 1..63 real sources, got $sourceCount")
  require(priorityBits > 0 && priorityBits <= 32,
    s"PLIC priority width must be 1..32, got $priorityBits")
  require(addressBits >= 22,
    s"PLIC MMIO offsets require at least 22 address bits, got $addressBits")

  private val sourceIdBits = log2Ceil(sourceCount + 1)
  private val wordCount = sourceCount / 32 + 1

  val io = IO(new Bundle {
    val sources = Input(UInt(sourceCount.W))

    val request = Input(Bool())
    val write = Input(Bool())
    val address = Input(UInt(addressBits.W))
    val wdata = Input(UInt(32.W))
    val wmask = Input(UInt(4.W))

    val ready = Output(Bool())
    val rdata = Output(UInt(32.W))
    val fault = Output(Bool())
    val interrupt = Output(Bool())

    val pending = Output(UInt(sourceCount.W))
    val enabled = Output(UInt(sourceCount.W))
    val threshold = Output(UInt(priorityBits.W))
    val inService = Output(UInt(sourceCount.W))
  })

  private def extendTo32(value: UInt, width: Int): UInt = {
    if (width == 32) value else Cat(0.U((32 - width).W), value)
  }

  private def architecturalWord(value: UInt, word: Int): UInt = {
    VecInit((0 until 32).map { bit =>
      val sourceId = word * 32 + bit
      if (sourceId == 0 || sourceId > sourceCount) false.B
      else value(sourceId - 1)
    }).asUInt
  }

  private def mergeBytes(oldValue: UInt, newValue: UInt, mask: UInt): UInt = {
    Cat((0 until 4).reverse.map { byte =>
      val high = byte * 8 + 7
      val low = byte * 8
      Mux(mask(byte), newValue(high, low), oldValue(high, low))
    })
  }

  val plic = Module(new MachinePlic(sourceCount, priorityBits))
  plic.io.sources := io.sources

  plic.io.priorityWriteEnable := false.B
  plic.io.priorityWriteId := 0.U
  plic.io.priorityWriteData := 0.U
  plic.io.enableWrite := false.B
  plic.io.enableWriteData := plic.io.enabled
  plic.io.thresholdWrite := false.B
  plic.io.thresholdWriteData := 0.U
  plic.io.claimRead := false.B
  plic.io.completeWrite := false.B
  plic.io.completeId := 0.U

  val priorityZeroHit = io.address === MachinePlicMmioMap.PriorityBase.U(addressBits.W)
  val priorityHit = WireDefault(false.B)
  val priorityId = WireDefault(0.U(sourceIdBits.W))
  val priorityReadData = WireDefault(0.U(32.W))
  for (index <- 0 until sourceCount) {
    val sourceId = index + 1
    when(io.address === MachinePlicMmioMap.priority(sourceId).U(addressBits.W)) {
      priorityHit := true.B
      priorityId := sourceId.U
      priorityReadData := extendTo32(plic.io.priorities(index), priorityBits)
    }
  }

  val pendingHits = (0 until wordCount).map { word =>
    io.address === MachinePlicMmioMap.pendingWord(word).U(addressBits.W)
  }
  val enableHits = (0 until wordCount).map { word =>
    io.address === MachinePlicMmioMap.enableWord(enableBase, word).U(addressBits.W)
  }
  val pendingHit = pendingHits.reduce(_ || _)
  val enableHit = enableHits.reduce(_ || _)
  val thresholdHit = io.address === thresholdOffset.U(addressBits.W)
  val claimCompleteHit = io.address === claimCompleteOffset.U(addressBits.W)
  val implemented = priorityZeroHit || priorityHit || pendingHit || enableHit || thresholdHit || claimCompleteHit
  val aligned = io.address(1, 0) === 0.U
  val accepted = io.request && aligned && implemented

  io.ready := io.request
  io.fault := io.request && (!aligned || !implemented)

  val pendingReadWords = (0 until wordCount).map(word => architecturalWord(plic.io.pending, word))
  val enabledReadWords = (0 until wordCount).map(word => architecturalWord(plic.io.enabled, word))
  val enabledMergedWords = enabledReadWords.map(word => mergeBytes(word, io.wdata, io.wmask))
  val thresholdReadData = extendTo32(plic.io.threshold, priorityBits)
  val claimReadData = extendTo32(plic.io.claim, sourceIdBits)

  val readData = WireDefault(0.U(32.W))
  when(priorityHit) { readData := priorityReadData }
  for (word <- 0 until wordCount) {
    when(pendingHits(word)) { readData := pendingReadWords(word) }
    when(enableHits(word)) { readData := enabledReadWords(word) }
  }
  when(thresholdHit) { readData := thresholdReadData }
  when(claimCompleteHit) { readData := claimReadData }
  io.rdata := Mux(accepted && !io.write, readData, 0.U)

  val priorityMerged = mergeBytes(priorityReadData, io.wdata, io.wmask)
  val thresholdMerged = mergeBytes(thresholdReadData, io.wdata, io.wmask)
  val completeMerged = mergeBytes(0.U(32.W), io.wdata, io.wmask)

  when(accepted && io.write && priorityHit) {
    plic.io.priorityWriteEnable := true.B
    plic.io.priorityWriteId := priorityId
    plic.io.priorityWriteData := priorityMerged(priorityBits - 1, 0)
  }

  val nextEnabled = WireDefault(plic.io.enabled)
  for (index <- 0 until sourceCount) {
    val sourceId = index + 1
    val word = sourceId / 32
    val bit = sourceId % 32
    when(accepted && io.write && enableHits(word)) {
      nextEnabled(index) := enabledMergedWords(word)(bit)
    }
  }
  when(accepted && io.write && enableHit) {
    plic.io.enableWrite := true.B
    plic.io.enableWriteData := nextEnabled
  }

  when(accepted && io.write && thresholdHit) {
    plic.io.thresholdWrite := true.B
    plic.io.thresholdWriteData := thresholdMerged(priorityBits - 1, 0)
  }

  when(accepted && !io.write && claimCompleteHit) {
    plic.io.claimRead := true.B
  }

  when(accepted && io.write && claimCompleteHit) {
    plic.io.completeWrite := true.B
    plic.io.completeId := completeMerged(sourceIdBits - 1, 0)
  }

  io.interrupt := plic.io.interrupt
  io.pending := plic.io.pending
  io.enabled := plic.io.enabled
  io.threshold := plic.io.threshold
  io.inService := plic.io.inService
}
