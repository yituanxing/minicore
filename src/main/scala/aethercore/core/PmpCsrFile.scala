package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.config.IsaConfig

object PmpCsrAddress {
  val Pmpcfg0: Int = 0x3a0
  val Pmpaddr0: Int = 0x3b0

  def pmpaddr(entry: Int): Int = {
    require(entry >= 0 && entry < PmpConstants.MaxEntries)
    Pmpaddr0 + entry
  }
}

object PmpCsrWarl {
  def canonicalizeConfigByte(data: UInt): UInt = {
    val read = data(PmpConstants.ConfigRead)
    val write = data(PmpConstants.ConfigWrite) && read
    val execute = data(PmpConstants.ConfigExecute)
    val addressMode = data(PmpConstants.ConfigAddressHigh, PmpConstants.ConfigAddressLow)
    val lock = data(PmpConstants.ConfigLock)
    Cat(lock, 0.U(2.W), addressMode, execute, write, read)
  }

  def canonicalizePackedConfig(isa: IsaConfig, data: UInt): UInt = {
    require(isa.xlen == 32, "the current pmpcfg0 implementation is RV32-only")
    Cat((0 until PmpConstants.MaxEntries).reverse.map { entry =>
      canonicalizeConfigByte(data(entry * 8 + 7, entry * 8))
    })
  }

  def canonicalizeAddress(isa: IsaConfig, data: UInt): UInt =
    canonicalizeAddress(isa, isa.xlen, data)

  def canonicalizeAddress(isa: IsaConfig, paddrBits: Int, data: UInt): UInt = {
    val geometry = PmpGeometry(isa.xlen, paddrBits)
    data & geometry.encodedAddressMask.U(isa.xlen.W)
  }

  def canonicalize(isa: IsaConfig, address: UInt, data: UInt): UInt =
    canonicalize(isa, isa.xlen, address, data)

  def canonicalize(isa: IsaConfig, paddrBits: Int, address: UInt, data: UInt): UInt = {
    val result = WireDefault(data)
    if (isa.hasPmp) {
      when(address === PmpCsrAddress.Pmpcfg0.U) {
        result := canonicalizePackedConfig(isa, data)
      }
      for (entry <- 0 until isa.pmpEntries) {
        when(address === PmpCsrAddress.pmpaddr(entry).U) {
          result := canonicalizeAddress(isa, paddrBits, data)
        }
      }
    }
    result
  }
}

class PmpCsrFile(val isa: IsaConfig, val paddrBits: Int) extends Module {
  def this(isa: IsaConfig) = this(isa, isa.xlen)

  private val xlen = isa.xlen
  private val geometry = PmpGeometry(xlen, paddrBits)
  private val pmpAddressBits = geometry.encodedAddressBits

  val io = IO(new Bundle {
    val readAddr = Input(UInt(12.W))
    val readData = Output(UInt(xlen.W))
    val readImplemented = Output(Bool())
    val readWritable = Output(Bool())

    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(12.W))
    val writeData = Input(UInt(xlen.W))

    val config = Output(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Output(Vec(PmpConstants.MaxEntries, UInt(pmpAddressBits.W)))
  })

  val config = RegInit(VecInit(Seq.fill(PmpConstants.MaxEntries)(0.U(8.W))))
  val pmpAddress = RegInit(
    VecInit(Seq.fill(PmpConstants.MaxEntries)(0.U(pmpAddressBits.W)))
  )

  io.readData := 0.U
  io.readImplemented := false.B
  io.readWritable := false.B
  io.config := config
  io.pmpAddress := pmpAddress

  if (isa.hasPmp) {
    when(io.readAddr === PmpCsrAddress.Pmpcfg0.U) {
      io.readData := Cat(config.reverse)
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    for (entry <- 0 until isa.pmpEntries) {
      when(io.readAddr === PmpCsrAddress.pmpaddr(entry).U) {
        io.readData := pmpAddress(entry).pad(xlen)
        io.readImplemented := true.B
        io.readWritable := true.B
      }
    }
  }

  val canonicalWriteData = PmpCsrWarl.canonicalize(isa, paddrBits, io.writeAddr, io.writeData)

  if (isa.hasPmp) {
    when(io.writeEnable && io.writeAddr === PmpCsrAddress.Pmpcfg0.U) {
      for (entry <- 0 until isa.pmpEntries) {
        when(!config(entry)(PmpConstants.ConfigLock)) {
          config(entry) := canonicalWriteData(entry * 8 + 7, entry * 8)
        }
      }
    }

    for (entry <- 0 until isa.pmpEntries) {
      val ownEntryLocked = config(entry)(PmpConstants.ConfigLock)
      val lockedByNextTor = if (entry + 1 < isa.pmpEntries) {
        config(entry + 1)(PmpConstants.ConfigLock) &&
          config(entry + 1)(
            PmpConstants.ConfigAddressHigh,
            PmpConstants.ConfigAddressLow
          ) === PmpAddressMode.Tor.U
      } else {
        false.B
      }

      when(
        io.writeEnable &&
          io.writeAddr === PmpCsrAddress.pmpaddr(entry).U &&
          !ownEntryLocked &&
          !lockedByNextTor
      ) {
        pmpAddress(entry) := canonicalWriteData(pmpAddressBits - 1, 0)
      }
    }
  }
}
