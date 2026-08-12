package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.PrivilegeMode

object PmpConstants {
  val MaxEntries: Int = 16
  val ConfigEntriesPerCsr: Int = 4
  val ConfigCsrCount: Int = MaxEntries / ConfigEntriesPerCsr
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
        when(encodedAddress.andR) {
          lower := 0.U
          upper := geometry.allOnesNapotUpper.U((paddrBits + 1).W)
        }.otherwise {
          // NAPOT encodes size/8-1 in the low-order one bits. Exactly one
          // condition below is true: `ones` trailing ones followed by zero.
          for (ones <- 0 until pmpAddressBits) {
            val lowMask = (BigInt(1) << ones) - 1
            val discriminatorMask = (BigInt(1) << (ones + 1)) - 1
            val condition =
              (encodedAddress & discriminatorMask.U(pmpAddressBits.W)) ===
                lowMask.U(pmpAddressBits.W)
            when(condition) {
              val clearMask = addressMask & ~lowMask
              val base = Cat(
                0.U(1.W),
                encodedAddress & clearMask.U(pmpAddressBits.W),
                0.U(2.W)
              ).pad(paddrBits + 1)
              lower := base
              upper := base + (BigInt(1) << (ones + 3)).U((paddrBits + 1).W)
            }
          }
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
