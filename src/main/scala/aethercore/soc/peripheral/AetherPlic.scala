package aethercore.soc.peripheral

import chisel3._
import chisel3.util._
import aethercore.core.MachinePlic

object AetherPlicMap {
  val PriorityBase: Int = 0x000000
  val Pending: Int = 0x001000
  val Enable: Int = 0x002000
  val Threshold: Int = 0x200000
  val ClaimComplete: Int = 0x200004

  // QEMU-virt hart0 Supervisor context retained by the qualified Linux board.
  val SupervisorEnable: Int = 0x002080
  val SupervisorThreshold: Int = 0x201000
  val SupervisorClaimComplete: Int = 0x201004

  def priority(sourceId: Int): Int = PriorityBase + sourceId * 4
  def pendingWord(word: Int): Int = Pending + word * 4
  def enableWord(base: Int, word: Int): Int = base + word * 4
}

/**
  * AetherSoC Platform-Level Interrupt Controller MMIO peripheral.
  *
  * Source/arbitration state is delegated to the existing MachinePlic primitive;
  * this module owns the SoC-visible register map and transaction lifetime.
  *
  * Reads are combinational for a selected request. Architectural side effects
  * (priority/enable/threshold writes, claim, completion) occur only when
  * complete is asserted for that exact request, matching the terminal
  * response-acceptance contract used by the other AetherSoC peripherals.
  */
class AetherPlic(
    val sourceCount: Int = 52,
    val priorityBits: Int = 3,
    val addressBits: Int = 24,
    val enableBase: Int = AetherPlicMap.SupervisorEnable,
    val thresholdOffset: Int = AetherPlicMap.SupervisorThreshold,
    val claimCompleteOffset: Int = AetherPlicMap.SupervisorClaimComplete
) extends Module {
  require(sourceCount > 0 && sourceCount <= 63,
    s"two-word one-based PLIC profile supports 1..63 real sources, got $sourceCount")
  require(priorityBits > 0 && priorityBits <= 32,
    s"PLIC priority width must be 1..32, got $priorityBits")
  require(addressBits >= 22,
    s"PLIC MMIO offsets require at least 22 address bits, got $addressBits")

  private val sourceIdBits = log2Ceil(sourceCount + 1)
  private val wordCount = sourceCount / 32 + 1
  private val supervisorContextProfile =
    enableBase == AetherPlicMap.SupervisorEnable &&
      thresholdOffset == AetherPlicMap.SupervisorThreshold &&
      claimCompleteOffset == AetherPlicMap.SupervisorClaimComplete

  val io = IO(new Bundle {
    val sources = Input(UInt(sourceCount.W))

    val request = Input(Bool())
    val write = Input(Bool())
    val address = Input(UInt(addressBits.W))
    val wdata = Input(UInt(32.W))
    val wmask = Input(UInt(4.W))
    val complete = Input(Bool())

    val ready = Output(Bool())
    val rdata = Output(UInt(32.W))
    val fault = Output(Bool())
    val interrupt = Output(Bool())

    val pending = Output(UInt(sourceCount.W))
    val enabled = Output(UInt(sourceCount.W))
    val threshold = Output(UInt(priorityBits.W))
    val inService = Output(UInt(sourceCount.W))
  })

  private def extendTo32(value: UInt, width: Int): UInt =
    if (width == 32) value else Cat(0.U((32 - width).W), value)

  private def architecturalWord(value: UInt, word: Int): UInt = {
    VecInit((0 until 32).map { bit =>
      val sourceId = word * 32 + bit
      if (sourceId == 0 || sourceId > sourceCount) false.B
      else value(sourceId - 1)
    }).asUInt
  }

  private def mergeBytes(oldValue: UInt, newValue: UInt, mask: UInt): UInt =
    Cat((0 until 4).reverse.map { byte =>
      val high = byte * 8 + 7
      val low = byte * 8
      Mux(mask(byte), newValue(high, low), oldValue(high, low))
    })

  private val plic = Module(new MachinePlic(sourceCount, priorityBits))
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

  private val priorityZeroHit =
    io.address === AetherPlicMap.PriorityBase.U(addressBits.W)
  private val sourceIndexBits = math.max(1, log2Ceil(sourceCount))
  private val priorityId =
    io.address(sourceIdBits + 1, 2)
  private val priorityHighZero =
    if (addressBits > sourceIdBits + 2)
      io.address(addressBits - 1, sourceIdBits + 2) === 0.U
    else true.B
  private val priorityHit =
    priorityHighZero &&
      priorityId > 0.U &&
      priorityId <= sourceCount.U
  private val priorityIndex =
    (priorityId - 1.U)(sourceIndexBits - 1, 0)
  private val priorityReadData =
    extendTo32(plic.io.priorities(priorityIndex), priorityBits)

  private val pendingHits = (0 until wordCount).map { word =>
    io.address === AetherPlicMap.pendingWord(word).U(addressBits.W)
  }
  private val enableHits = (0 until wordCount).map { word =>
    io.address === AetherPlicMap.enableWord(enableBase, word).U(addressBits.W)
  }
  private val pendingHit = pendingHits.reduce(_ || _)
  private val enableHit = enableHits.reduce(_ || _)
  private val thresholdHit = io.address === thresholdOffset.U(addressBits.W)
  private val claimCompleteHit = io.address === claimCompleteOffset.U(addressBits.W)

  // OpenSBI generic PLIC cold-init still touches the preceding Machine-context
  // aperture even when the FDT marks that context absent. Preserve it as legal
  // read-zero/write-ignore space for the Supervisor-only AetherSoC instance.
  private val absentMachineEnableHit = if (supervisorContextProfile) {
    (0 until wordCount)
      .map(word =>
        io.address ===
          AetherPlicMap.enableWord(AetherPlicMap.Enable, word).U(addressBits.W))
      .reduce(_ || _)
  } else false.B
  private val absentMachineThresholdHit =
    if (supervisorContextProfile)
      io.address === AetherPlicMap.Threshold.U(addressBits.W)
    else false.B
  private val absentMachineClaimCompleteHit =
    if (supervisorContextProfile)
      io.address === AetherPlicMap.ClaimComplete.U(addressBits.W)
    else false.B
  private val absentMachineContextHit =
    absentMachineEnableHit ||
      absentMachineThresholdHit ||
      absentMachineClaimCompleteHit

  private val implemented =
    priorityZeroHit || priorityHit || pendingHit || enableHit || thresholdHit ||
      claimCompleteHit || absentMachineContextHit
  private val aligned = io.address(1, 0) === 0.U
  private val accepted = io.request && aligned && implemented
  private val terminalAccepted = accepted && io.complete

  io.ready := io.request
  io.fault := io.request && (!aligned || !implemented)

  private val pendingReadWords =
    (0 until wordCount).map(word => architecturalWord(plic.io.pending, word))
  private val enabledReadWords =
    (0 until wordCount).map(word => architecturalWord(plic.io.enabled, word))
  private val enabledMergedWords =
    enabledReadWords.map(word => mergeBytes(word, io.wdata, io.wmask))
  private val thresholdReadData = extendTo32(plic.io.threshold, priorityBits)
  private val claimReadData = extendTo32(plic.io.claim, sourceIdBits)

  private val readData = WireDefault(0.U(32.W))
  when(priorityHit) { readData := priorityReadData }
  for (word <- 0 until wordCount) {
    when(pendingHits(word)) { readData := pendingReadWords(word) }
    when(enableHits(word)) { readData := enabledReadWords(word) }
  }
  when(thresholdHit) { readData := thresholdReadData }
  when(claimCompleteHit) { readData := claimReadData }
  io.rdata := Mux(accepted && !io.write, readData, 0.U)

  private val priorityMerged = mergeBytes(priorityReadData, io.wdata, io.wmask)
  private val thresholdMerged = mergeBytes(thresholdReadData, io.wdata, io.wmask)
  private val completeMerged = mergeBytes(0.U(32.W), io.wdata, io.wmask)

  when(terminalAccepted && io.write && priorityHit) {
    plic.io.priorityWriteEnable := true.B
    plic.io.priorityWriteId := priorityId
    plic.io.priorityWriteData := priorityMerged(priorityBits - 1, 0)
  }

  private val nextEnabledBits = Wire(Vec(sourceCount, Bool()))
  nextEnabledBits := VecInit(plic.io.enabled.asBools)
  for (index <- 0 until sourceCount) {
    val sourceId = index + 1
    val word = sourceId / 32
    val bit = sourceId % 32
    when(terminalAccepted && io.write && enableHits(word)) {
      nextEnabledBits(index) := enabledMergedWords(word)(bit)
    }
  }
  when(terminalAccepted && io.write && enableHit) {
    plic.io.enableWrite := true.B
    plic.io.enableWriteData := nextEnabledBits.asUInt
  }

  when(terminalAccepted && io.write && thresholdHit) {
    plic.io.thresholdWrite := true.B
    plic.io.thresholdWriteData := thresholdMerged(priorityBits - 1, 0)
  }

  when(terminalAccepted && !io.write && claimCompleteHit) {
    plic.io.claimRead := true.B
  }

  when(terminalAccepted && io.write && claimCompleteHit) {
    plic.io.completeWrite := true.B
    plic.io.completeId := completeMerged(sourceIdBits - 1, 0)
  }

  io.interrupt := plic.io.interrupt
  io.pending := plic.io.pending
  io.enabled := plic.io.enabled
  io.threshold := plic.io.threshold
  io.inService := plic.io.inService
}
