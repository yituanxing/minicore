package aethercore.core

import aethercore.config.PageTableGeometry
import chisel3._
import chisel3.util._

/**
  * Correctness-first page-table walker driven by one architectural geometry.
  *
  * The walker owns the common Sv32/Sv39/Sv48 traversal algorithm: VPN indexing,
  * pointer descent, canonical virtual-address validation, inherited-global state
  * and final physical-address composition. PTE format legality, leaf permission
  * checks, Svade A/D policy and superpage alignment are delegated to the shared
  * PageTableEntryChecker.
  *
  * It does not own satp activation, TLB policy, PMP/PMA checks, or hardware A/D
  * updates. Keeping traversal and permission policy separate makes the shared
  * VM ownership explicit without changing response lifetime or fault priority.
  */
class PageTableWalker(val geometry: PageTableGeometry) extends Module {
  private val Xlen = geometry.xlen
  private val PaddrBits = geometry.architecturalPhysicalAddressBits
  private val PpnBits = geometry.ppnBits
  private val LevelBits = math.max(1, log2Ceil(geometry.levels))
  private val PteShift = log2Ceil(geometry.pteBytes)

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val requestReady = Output(Bool())
    val kill = Input(Bool())
    val virtualAddress = Input(UInt(Xlen.W))
    val rootPpn = Input(UInt(PpnBits.W))
    val privilege = Input(UInt(2.W))
    val write = Input(Bool())
    val execute = Input(Bool())
    val sum = Input(Bool())
    val mxr = Input(Bool())

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PaddrBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val physicalAddress = Output(UInt(PaddrBits.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
    val leafLevel = Output(UInt(LevelBits.W))
    val global = Output(Bool())
  })

  val idle :: walking :: respond :: Nil = Enum(3)
  val state = RegInit(idle)
  val level = RegInit((geometry.levels - 1).U(LevelBits.W))

  val virtualAddress = Reg(UInt(Xlen.W))
  val tablePpn = Reg(UInt(PpnBits.W))
  val privilege = Reg(UInt(2.W))
  val requestWrite = Reg(Bool())
  val requestExecute = Reg(Bool())
  val requestSum = Reg(Bool())
  val requestMxr = Reg(Bool())
  val inheritedGlobal = RegInit(false.B)

  val resultPhysicalAddress = RegInit(0.U(PaddrBits.W))
  val resultPageFault = RegInit(false.B)
  val resultAccessFault = RegInit(false.B)
  val resultLeafLevel = RegInit(0.U(LevelBits.W))
  val resultGlobal = RegInit(false.B)

  io.requestReady := state === idle
  io.responseValid := state === respond
  io.physicalAddress := resultPhysicalAddress
  io.pageFault := resultPageFault
  io.accessFault := resultAccessFault
  io.leafLevel := resultLeafLevel
  io.global := resultGlobal

  val vpnIndex = WireDefault(0.U(geometry.vpnBitsPerLevel.W))
  for (i <- 0 until geometry.levels) {
    val low = geometry.pageOffsetBits + i * geometry.vpnBitsPerLevel
    val high = low + geometry.vpnBitsPerLevel - 1
    when(level === i.U) {
      vpnIndex := virtualAddress(high, low)
    }
  }

  val tableBase = Cat(tablePpn, 0.U(geometry.pageOffsetBits.W))
  val pteOffset = (vpnIndex << PteShift).pad(PaddrBits)
  io.pteValid := state === walking && !io.kill
  io.pteAddress := tableBase + pteOffset

  val canonicalAddress = if (geometry.vaBits == Xlen) {
    true.B
  } else {
    val sign = io.virtualAddress(geometry.vaBits - 1)
    val upper = io.virtualAddress(Xlen - 1, geometry.vaBits)
    Mux(sign, upper.andR, !upper.orR)
  }

  val entryChecker = Module(new PageTableEntryChecker(geometry))
  entryChecker.io.pte := io.pteData
  entryChecker.io.level := level
  entryChecker.io.privilege := privilege
  entryChecker.io.write := requestWrite
  entryChecker.io.execute := requestExecute
  entryChecker.io.sum := requestSum
  entryChecker.io.mxr := requestMxr

  def finish(pageFault: Bool, accessFault: Bool): Unit = {
    resultPageFault := pageFault
    resultAccessFault := accessFault
    state := respond
  }

  when(io.kill) {
    state := idle
    level := (geometry.levels - 1).U
    inheritedGlobal := false.B
    resultPhysicalAddress := 0.U
    resultPageFault := false.B
    resultAccessFault := false.B
    resultLeafLevel := 0.U
    resultGlobal := false.B
  }.elsewhen(state === idle) {
    when(io.requestValid) {
      virtualAddress := io.virtualAddress
      tablePpn := io.rootPpn
      privilege := io.privilege
      requestWrite := io.write
      requestExecute := io.execute
      requestSum := io.sum
      requestMxr := io.mxr
      inheritedGlobal := false.B
      resultPhysicalAddress := 0.U
      resultPageFault := false.B
      resultAccessFault := false.B
      resultLeafLevel := 0.U
      resultGlobal := false.B
      level := (geometry.levels - 1).U

      when(canonicalAddress) {
        state := walking
      }.otherwise {
        finish(true.B, false.B)
      }
    }
  }.elsewhen(state === walking && io.pteReady) {
    when(io.pteFault) {
      finish(false.B, true.B)
    }.otherwise {
      val ptePpn = entryChecker.io.ppn
      val leaf = entryChecker.io.leaf
      val global = entryChecker.io.global

      val translatedPpn = WireDefault(ptePpn)
      for (i <- 1 until geometry.levels) {
        val lowerPpnBits = i * geometry.vpnBitsPerLevel
        val vaVpnHigh = geometry.pageOffsetBits + lowerPpnBits - 1
        when(level === i.U) {
          translatedPpn := Cat(
            ptePpn(PpnBits - 1, lowerPpnBits),
            virtualAddress(vaVpnHigh, geometry.pageOffsetBits)
          )
        }
      }

      when(entryChecker.io.invalidEncoding) {
        finish(true.B, false.B)
      }.elsewhen(leaf) {
        when(entryChecker.io.leafAccessFault) {
          finish(true.B, false.B)
        }.otherwise {
          resultPhysicalAddress := Cat(translatedPpn, virtualAddress(geometry.pageOffsetBits - 1, 0))
          resultLeafLevel := level
          resultGlobal := inheritedGlobal || global
          finish(false.B, false.B)
        }
      }.otherwise {
        when(level === 0.U) {
          finish(true.B, false.B)
        }.otherwise {
          tablePpn := ptePpn
          inheritedGlobal := inheritedGlobal || global
          level := level - 1.U
        }
      }
    }
  }.elsewhen(state === respond && io.responseReady) {
    state := idle
  }
}
