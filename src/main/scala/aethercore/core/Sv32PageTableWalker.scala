package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.PrivilegeMode

/**
  * Minimal two-level Sv32 page-table walker.
  *
  * This block deliberately owns only architectural translation semantics. It
  * does not contain a TLB and it does not update PTE A/D bits in hardware. A
  * clear A bit, or a clear D bit on a write, therefore fails closed with a page
  * fault (the Svade-style behavior). The caller is responsible for deciding
  * when satp is active and for applying PMP/PMA checks to implicit PTE reads
  * and the final translated physical access.
  *
  * Sv32 can produce 34-bit physical addresses on RV32, so the walker exposes
  * the full architectural width even though an individual platform may
  * implement fewer physical-address bits.
  */
class Sv32PageTableWalker extends Module {
  private val PaddrBits = 34
  private val PpnBits = 22

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val requestReady = Output(Bool())
    val virtualAddress = Input(UInt(32.W))
    val rootPpn = Input(UInt(PpnBits.W))
    val privilege = Input(UInt(2.W))
    val write = Input(Bool())
    val execute = Input(Bool())
    val sum = Input(Bool())
    val mxr = Input(Bool())

    // Read-only implicit memory port for 32-bit page-table entries.
    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PaddrBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(32.W))
    val pteFault = Input(Bool())

    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val physicalAddress = Output(UInt(PaddrBits.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
    val leafLevel = Output(UInt(1.W))
    val global = Output(Bool())
  })

  val idle :: level1 :: level0 :: respond :: Nil = Enum(4)
  val state = RegInit(idle)

  val virtualAddress = Reg(UInt(32.W))
  val tablePpn = Reg(UInt(PpnBits.W))
  val privilege = Reg(UInt(2.W))
  val requestWrite = Reg(Bool())
  val requestExecute = Reg(Bool())
  val requestSum = Reg(Bool())
  val requestMxr = Reg(Bool())

  val resultPhysicalAddress = RegInit(0.U(PaddrBits.W))
  val resultPageFault = RegInit(false.B)
  val resultAccessFault = RegInit(false.B)
  val resultLeafLevel = RegInit(0.U(1.W))
  val resultGlobal = RegInit(false.B)

  io.requestReady := state === idle
  io.responseValid := state === respond
  io.physicalAddress := resultPhysicalAddress
  io.pageFault := resultPageFault
  io.accessFault := resultAccessFault
  io.leafLevel := resultLeafLevel
  io.global := resultGlobal

  val walking = state === level1 || state === level0
  val vpnIndex = Mux(state === level1, virtualAddress(31, 22), virtualAddress(21, 12))
  val tableBase = Cat(tablePpn, 0.U(12.W))
  val pteOffset = Cat(vpnIndex, 0.U(2.W))

  io.pteValid := walking
  io.pteAddress := tableBase + pteOffset

  def finish(pageFault: Bool, accessFault: Bool): Unit = {
    resultPageFault := pageFault
    resultAccessFault := accessFault
    state := respond
  }

  when(state === idle) {
    when(io.requestValid) {
      virtualAddress := io.virtualAddress
      tablePpn := io.rootPpn
      privilege := io.privilege
      requestWrite := io.write
      requestExecute := io.execute
      requestSum := io.sum
      requestMxr := io.mxr
      resultPhysicalAddress := 0.U
      resultPageFault := false.B
      resultAccessFault := false.B
      resultLeafLevel := 0.U
      resultGlobal := false.B
      state := level1
    }
  }.elsewhen(walking && io.pteReady) {
    when(io.pteFault) {
      // A failed implicit PTE read becomes an access fault of the original
      // explicit access type, not a page fault.
      finish(false.B, true.B)
    }.otherwise {
      val pte = io.pteData
      val valid = pte(0)
      val readable = pte(1)
      val writable = pte(2)
      val executable = pte(3)
      val user = pte(4)
      val global = pte(5)
      val accessed = pte(6)
      val dirty = pte(7)
      val ppn0 = pte(19, 10)
      val ppn1 = pte(31, 20)
      val nextPpn = pte(31, 10)

      val invalidEncoding = !valid || (!readable && writable)
      val leaf = readable || executable
      val readAllowed = readable || (requestMxr && executable)
      val accessAllowed = Mux(
        requestExecute,
        executable,
        Mux(requestWrite, writable, readAllowed)
      )
      val privilegeAllowed = Mux(
        privilege === PrivilegeMode.User.U,
        user,
        Mux(
          privilege === PrivilegeMode.Supervisor.U,
          Mux(requestExecute, !user, !user || requestSum),
          false.B
        )
      )
      val adAllowed = accessed && (!requestWrite || dirty)
      val misalignedMegapage = state === level1 && ppn0 =/= 0.U

      when(invalidEncoding) {
        finish(true.B, false.B)
      }.elsewhen(leaf) {
        when(!accessAllowed || !privilegeAllowed || !adAllowed || misalignedMegapage) {
          finish(true.B, false.B)
        }.otherwise {
          resultPhysicalAddress := Mux(
            state === level1,
            Cat(ppn1, virtualAddress(21, 12), virtualAddress(11, 0)),
            Cat(ppn1, ppn0, virtualAddress(11, 0))
          )
          resultLeafLevel := Mux(state === level1, 1.U, 0.U)
          resultGlobal := global
          finish(false.B, false.B)
        }
      }.otherwise {
        when(state === level1) {
          tablePpn := nextPpn
          state := level0
        }.otherwise {
          // A valid level-0 pointer cannot descend any further in Sv32.
          finish(true.B, false.B)
        }
      }
    }
  }.elsewhen(state === respond && io.responseReady) {
    state := idle
  }
}
