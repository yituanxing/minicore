package aethercore.core

import aethercore.common.PrivilegeMode
import aethercore.config.PageTableGeometry
import chisel3._
import chisel3.util._

/**
  * Pure combinational architectural policy for one page-table entry.
  *
  * This module owns PTE-format legality, leaf classification, access/privilege
  * permission checks, Svade A/D checks and superpage-alignment legality. It does
  * not own page-table traversal, VPN selection, canonical-address validation,
  * inherited-global state, physical-address composition, TLB policy or PMP/PMA.
  *
  * Keeping this policy geometry-driven preserves one implementation across
  * Sv32/Sv39/Sv48 while leaving PageTableWalker responsible only for traversal
  * and response lifetime.
  */
class PageTableEntryChecker(val geometry: PageTableGeometry) extends Module {
  private val PpnBits = geometry.ppnBits
  private val LevelBits = math.max(1, log2Ceil(geometry.levels))

  val io = IO(new Bundle {
    val pte = Input(UInt(geometry.pteBits.W))
    val level = Input(UInt(LevelBits.W))
    val privilege = Input(UInt(2.W))
    val write = Input(Bool())
    val execute = Input(Bool())
    val sum = Input(Bool())
    val mxr = Input(Bool())

    val ppn = Output(UInt(PpnBits.W))
    val global = Output(Bool())
    val leaf = Output(Bool())
    val invalidEncoding = Output(Bool())
    val leafAccessFault = Output(Bool())
  })

  val valid = io.pte(0)
  val readable = io.pte(1)
  val writable = io.pte(2)
  val executable = io.pte(3)
  val user = io.pte(4)
  val global = io.pte(5)
  val accessed = io.pte(6)
  val dirty = io.pte(7)
  val ptePpn = io.pte(9 + PpnBits, 10)
  val leaf = readable || executable

  val reservedHigh = if (10 + PpnBits < geometry.pteBits) {
    io.pte(geometry.pteBits - 1, 10 + PpnBits).orR
  } else {
    false.B
  }
  val nonLeafReserved = !leaf && (user || accessed || dirty)
  val invalidEncoding = !valid || (!readable && writable) || reservedHigh || nonLeafReserved

  val readAllowed = readable || (io.mxr && executable)
  val accessAllowed = Mux(io.execute, executable, Mux(io.write, writable, readAllowed))
  val privilegeAllowed = Mux(
    io.privilege === PrivilegeMode.User.U,
    user,
    Mux(
      io.privilege === PrivilegeMode.Supervisor.U,
      Mux(io.execute, !user, !user || io.sum),
      false.B
    )
  )
  val adAllowed = accessed && (!io.write || dirty)

  val misalignedSuperpage = WireDefault(false.B)
  for (i <- 1 until geometry.levels) {
    val lowerPpnBits = i * geometry.vpnBitsPerLevel
    when(io.level === i.U) {
      misalignedSuperpage := ptePpn(lowerPpnBits - 1, 0).orR
    }
  }

  io.ppn := ptePpn
  io.global := global
  io.leaf := leaf
  io.invalidEncoding := invalidEncoding
  io.leafAccessFault := !accessAllowed || !privilegeAllowed || !adAllowed || misalignedSuperpage
}
