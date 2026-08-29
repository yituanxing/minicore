package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.PrivilegeMode

object PmpConstants {
  val MaxEntries: Int = 16

  // PMP configuration CSRs pack one 8-bit pmpcfg byte per entry. RV32 fits
  // four entries in one CSR; RV64 fits eight and therefore uses only the even
  // pmpcfg CSR numbers. Keep the historical RV32 aliases until every external
  // helper has migrated to the XLEN-aware geometry.
  // PMP 配置 CSR 每个条目占 8 位；RV32 每个 CSR 放 4 项，RV64 放 8 项并只使用偶数 pmpcfg。
  val ConfigEntriesPerCsr: Int = 4
  val ConfigCsrCount: Int = MaxEntries / ConfigEntriesPerCsr

  def configEntriesPerCsr(xlen: Int): Int = xlen match {
    case 32 => 4
    case 64 => 8
    case _  => throw new IllegalArgumentException(s"PMP config packing requires XLEN 32 or 64, got $xlen")
  }

  def configCsrCount(xlen: Int): Int = {
    val entriesPerCsr = configEntriesPerCsr(xlen)
    (MaxEntries + entriesPerCsr - 1) / entriesPerCsr
  }

  val ConfigRead: Int = 0
  val ConfigWrite: Int = 1
  val ConfigExecute: Int = 2
  val ConfigAddressLow: Int = 3
  val ConfigAddressHigh: Int = 4
  val ConfigLock: Int = 7
}

object PmpAddressMode {
  val Off: Int = 0
  val Tor: Int = 1
  val Na4: Int = 2
  val Napot: Int = 3
}

/**
  * Combinational physical-memory-protection check.
  *
  * Entries are statically prioritized from zero upward. The first entry that
  * overlaps any byte of an access owns the decision; an access that straddles
  * that entry is rejected rather than falling through to a later entry.
  * Unlocked entries are bypassed in M-mode, while locked entries apply to every
  * privilege. An unmatched S/U access is denied and an unmatched M access is
  * allowed, matching the privileged architecture PMP rules.
  */
class PmpChecker(
    val xlen: Int,
    val entries: Int = PmpConstants.MaxEntries,
    val paddrBits: Int
) extends Module {
  def this(xlen: Int) = this(xlen, PmpConstants.MaxEntries, xlen)
  def this(xlen: Int, entries: Int) = this(xlen, entries, xlen)

  private val geometry = PmpGeometry(xlen, paddrBits)
  require(entries > 0 && entries <= PmpConstants.MaxEntries)

  private val pmpAddressBits = geometry.encodedAddressBits
  private val entryIndexBits = math.max(1, log2Ceil(entries))
  private val addressMask = geometry.encodedAddressMask

  val io = IO(new Bundle {
    val privilege = Input(UInt(2.W))
    val address = Input(UInt(paddrBits.W))
    val bytes = Input(UInt(4.W))
    val write = Input(Bool())
    val execute = Input(Bool())
    val config = Input(Vec(entries, UInt(8.W)))
    val pmpAddress = Input(Vec(entries, UInt(pmpAddressBits.W)))

    val allow = Output(Bool())
    val matched = Output(Bool())
    val matchedEntry = Output(UInt(entryIndexBits.W))
  })

  val start = Cat(0.U(1.W), io.address)
  val end = Wire(UInt((paddrBits + 1).W))
  end := start + io.bytes - 1.U
  val invalidRange = io.bytes === 0.U || end(paddrBits)

  val overlaps = Wire(Vec(entries, Bool()))
  val entryAllows = Wire(Vec(entries, Bool()))

  for (entry <- 0 until entries) {
    val config = io.config(entry)
    val mode = config(PmpConstants.ConfigAddressHigh, PmpConstants.ConfigAddressLow)
    val lower = WireDefault(0.U((paddrBits + 1).W))
    val upper = WireDefault(0.U((paddrBits + 1).W))
    val encodedAddress = io.pmpAddress(entry)
    val byteAddress = Cat(0.U(1.W), encodedAddress, 0.U(2.W)).pad(paddrBits + 1)

    switch(mode) {
      is(PmpAddressMode.Tor.U) {
        lower :=
          (if (entry == 0) 0.U
           else Cat(0.U(1.W), io.pmpAddress(entry - 1), 0.U(2.W)).pad(paddrBits + 1))
        upper := byteAddress
      }
      is(PmpAddressMode.Na4.U) {
        lower := byteAddress
        upper := byteAddress + 4.U
      }
      is(PmpAddressMode.Napot.U) {
        // NAPOT size is encoded by the number of trailing one bits in pmpaddr.
        //
        // The previous implementation enumerated every possible trailing-one
        // count and built one wide compare/mux candidate per size. PA56 therefore
        // expanded 54 candidates for each PMP entry and every checker instance.
        //
        // For any non-all-ones x:
        //   (x ^ (x + 1)) >> 1  = mask of the trailing one bits
        //   (~x) & (x + 1)      = one-hot bit for the first zero above them
        //
        // These identities recover exactly the same NAPOT base and range size
        // with one incrementer plus bitwise logic instead of a PA-width-sized
        // bank of wide comparators. Keep the architectural all-ones encoding as
        // the explicit whole-domain special case.
        val incremented = encodedAddress + 1.U
        // Static right shift narrows a Chisel UInt. Pad back to the
        // architectural pmpaddr width before complementing the mask; otherwise
        // the zero-extension of ~mask would incorrectly clear the high encoded
        // address bit for regions in the upper half of the implemented PA space.
        val trailingOnesMask =
          ((encodedAddress ^ incremented) >> 1).asUInt.pad(pmpAddressBits)
        val firstZeroOneHot = (~encodedAddress).asUInt & incremented
        val compactBase = Cat(
          0.U(1.W),
          encodedAddress & ~trailingOnesMask,
          0.U(2.W)
        ).pad(paddrBits + 1)
        val compactSize = Cat(firstZeroOneHot, 0.U(3.W))

        when(encodedAddress.andR) {
          lower := 0.U
          upper := geometry.allOnesNapotUpper.U((paddrBits + 1).W)
        }.otherwise {
          lower := compactBase
          upper := compactBase + compactSize
        }
      }
    }

    val active = mode =/= PmpAddressMode.Off.U && upper > lower
    overlaps(entry) := active && !invalidRange && start < upper && end >= lower
    val fullyContained = start >= lower && end < upper
    val permission = Mux(
      io.execute,
      config(PmpConstants.ConfigExecute),
      Mux(io.write, config(PmpConstants.ConfigWrite), config(PmpConstants.ConfigRead))
    )
    val machineBypass =
      io.privilege === PrivilegeMode.Machine.U && !config(PmpConstants.ConfigLock)
    entryAllows(entry) := fullyContained && (machineBypass || permission)
  }

  val matched = WireDefault(false.B)
  val matchedEntry = WireDefault(0.U(entryIndexBits.W))
  val allowed = WireDefault(io.privilege === PrivilegeMode.Machine.U)

  // Generate high entries first so lower-numbered entries have final priority.
  for (entry <- (0 until entries).reverse) {
    when(overlaps(entry)) {
      matched := true.B
      matchedEntry := entry.U
      allowed := entryAllows(entry)
    }
  }

  io.matched := matched
  io.matchedEntry := matchedEntry
  io.allow := allowed && !invalidRange
}
