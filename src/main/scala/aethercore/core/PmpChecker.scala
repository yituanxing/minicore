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
class PmpDecodedEntry(val paddrBits: Int) extends Bundle {
  val active = Bool()
  val lower = UInt((paddrBits + 1).W)
  val upper = UInt((paddrBits + 1).W)
  val read = Bool()
  val write = Bool()
  val execute = Bool()
  val lock = Bool()
}

/** Decode the CSR-owned PMP entry geometry once so multiple request lanes can share it. */
class PmpRangeDecoder(
    val xlen: Int,
    val entries: Int = PmpConstants.MaxEntries,
    val paddrBits: Int
) extends Module {
  private val geometry = PmpGeometry(xlen, paddrBits)
  require(entries > 0 && entries <= PmpConstants.MaxEntries)

  private val pmpAddressBits = geometry.encodedAddressBits

  val io = IO(new Bundle {
    val config = Input(Vec(entries, UInt(8.W)))
    val pmpAddress = Input(Vec(entries, UInt(pmpAddressBits.W)))
    val ranges = Output(Vec(entries, new PmpDecodedEntry(paddrBits)))
  })

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
        val incremented = encodedAddress + 1.U
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

    io.ranges(entry).active := mode =/= PmpAddressMode.Off.U && upper > lower
    io.ranges(entry).lower := lower
    io.ranges(entry).upper := upper
    io.ranges(entry).read := config(PmpConstants.ConfigRead)
    io.ranges(entry).write := config(PmpConstants.ConfigWrite)
    io.ranges(entry).execute := config(PmpConstants.ConfigExecute)
    io.ranges(entry).lock := config(PmpConstants.ConfigLock)
  }
}

/** Per-request PMP compare/priority lane over already-decoded CSR entry geometry. */
class PmpAccessChecker(
    val entries: Int = PmpConstants.MaxEntries,
    val paddrBits: Int
) extends Module {
  require(entries > 0 && entries <= PmpConstants.MaxEntries)
  private val entryIndexBits = math.max(1, log2Ceil(entries))

  val io = IO(new Bundle {
    val privilege = Input(UInt(2.W))
    val address = Input(UInt(paddrBits.W))
    val bytes = Input(UInt(4.W))
    val write = Input(Bool())
    val execute = Input(Bool())
    val ranges = Input(Vec(entries, new PmpDecodedEntry(paddrBits)))

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
    val range = io.ranges(entry)
    overlaps(entry) :=
      range.active && !invalidRange && start < range.upper && end >= range.lower
    val fullyContained = start >= range.lower && end < range.upper
    val permission = Mux(
      io.execute,
      range.execute,
      Mux(io.write, range.write, range.read)
    )
    val machineBypass =
      io.privilege === PrivilegeMode.Machine.U && !range.lock
    entryAllows(entry) := fullyContained && (machineBypass || permission)
  }

  val matched = WireDefault(false.B)
  val matchedEntry = WireDefault(0.U(entryIndexBits.W))
  val allowed = WireDefault(io.privilege === PrivilegeMode.Machine.U)

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

/**
  * Compatibility wrapper retaining the historical one-module PMP interface.
  * Integrations with multiple request lanes may instead share one PmpRangeDecoder
  * across multiple PmpAccessChecker instances.
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

  private val decoder = Module(new PmpRangeDecoder(xlen, entries, paddrBits))
  private val access = Module(new PmpAccessChecker(entries, paddrBits))

  decoder.io.config := io.config
  decoder.io.pmpAddress := io.pmpAddress

  access.io.privilege := io.privilege
  access.io.address := io.address
  access.io.bytes := io.bytes
  access.io.write := io.write
  access.io.execute := io.execute
  access.io.ranges := decoder.io.ranges

  io.allow := access.io.allow
  io.matched := access.io.matched
  io.matchedEntry := access.io.matchedEntry
}
