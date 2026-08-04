package aethercore.core

import chisel3._
import chisel3.util._

object MachinePlicMmioMap {
  val PriorityBase: Int = 0x000000
  val Pending: Int = 0x001000
  val Enable: Int = 0x002000
  val Threshold: Int = 0x200000
  val ClaimComplete: Int = 0x200004

  def priority(sourceId: Int): Int = PriorityBase + sourceId * 4
}

/** Bus-facing single-context PLIC register map.
  *
  * The offsets follow the conventional SiFive/QEMU PLIC layout used by
  * upstream RISC-V software: source priority registers start at 0x4, pending
  * is at 0x1000, the context enable word is at 0x2000, and context threshold
  * plus claim/complete are at 0x200000/0x200004.
  *
  * This first platform profile intentionally supports at most 32 interrupt
  * sources so pending and enable state fit in one 32-bit register. Every access
  * completes in one cycle. Unknown or misaligned addresses raise a bus fault
  * and have no side effects.
  */
class MachinePlicMmio(
    val sourceCount: Int = 8,
    val priorityBits: Int = 3,
    val addressBits: Int = 24
) extends Module {
  require(sourceCount > 0 && sourceCount <= 32,
    s"single-word PLIC profile supports 1..32 sources, got $sourceCount")
  require(priorityBits > 0 && priorityBits <= 32,
    s"PLIC priority width must be 1..32, got $priorityBits")
  require(addressBits >= 22,
    s"PLIC MMIO offsets require at least 22 address bits, got $addressBits")

  private val sourceIdBits = log2Ceil(sourceCount + 1)

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
  plic.io.enableWriteData := 0.U
  plic.io.thresholdWrite := false.B
  plic.io.thresholdWriteData := 0.U
  plic.io.claimRead := false.B
  plic.io.completeWrite := false.B
  plic.io.completeId := 0.U

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

  val pendingHit = io.address === MachinePlicMmioMap.Pending.U(addressBits.W)
  val enableHit = io.address === MachinePlicMmioMap.Enable.U(addressBits.W)
  val thresholdHit = io.address === MachinePlicMmioMap.Threshold.U(addressBits.W)
  val claimCompleteHit = io.address === MachinePlicMmioMap.ClaimComplete.U(addressBits.W)
  val implemented = priorityHit || pendingHit || enableHit || thresholdHit || claimCompleteHit
  val aligned = io.address(1, 0) === 0.U
  val accepted = io.request && aligned && implemented

  io.ready := io.request
  io.fault := io.request && (!aligned || !implemented)

  val enabledReadData = extendTo32(plic.io.enabled, sourceCount)
  val thresholdReadData = extendTo32(plic.io.threshold, priorityBits)
  val claimReadData = extendTo32(plic.io.claim, sourceIdBits)

  val readData = WireDefault(0.U(32.W))
  when(priorityHit) { readData := priorityReadData }
  when(pendingHit) { readData := extendTo32(plic.io.pending, sourceCount) }
  when(enableHit) { readData := enabledReadData }
  when(thresholdHit) { readData := thresholdReadData }
  when(claimCompleteHit) { readData := claimReadData }
  io.rdata := Mux(accepted && !io.write, readData, 0.U)

  val priorityMerged = mergeBytes(priorityReadData, io.wdata, io.wmask)
  val enableMerged = mergeBytes(enabledReadData, io.wdata, io.wmask)
  val thresholdMerged = mergeBytes(thresholdReadData, io.wdata, io.wmask)
  val completeMerged = mergeBytes(0.U(32.W), io.wdata, io.wmask)

  when(accepted && io.write && priorityHit) {
    plic.io.priorityWriteEnable := true.B
    plic.io.priorityWriteId := priorityId
    plic.io.priorityWriteData := priorityMerged(priorityBits - 1, 0)
  }

  when(accepted && io.write && enableHit) {
    plic.io.enableWrite := true.B
    plic.io.enableWriteData := enableMerged(sourceCount - 1, 0)
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
