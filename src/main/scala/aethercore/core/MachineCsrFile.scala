package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.PrivilegeMode
import aethercore.config.IsaConfig

object MachineCsrAddress {
  val Mstatus: Int = 0x300
  val Misa: Int = 0x301
  val Mie: Int = 0x304
  val Mtvec: Int = 0x305
  val Mscratch: Int = 0x340
  val Mepc: Int = 0x341
  val Mcause: Int = 0x342
  val Mtval: Int = 0x343
  val Mip: Int = 0x344
}

object MachineCsrBit {
  val MstatusMie: Int = 3
  val MstatusMpie: Int = 7
  val MstatusMppLow: Int = 11
  val MachineTimerInterrupt: Int = 7
}

object MachineCsrWarl {
  def canonicalize(isa: IsaConfig, address: UInt, data: UInt): UInt = {
    val xlen = isa.xlen
    val allBits = (BigInt(1) << xlen) - 1
    val mstatusNonMppMask =
      (BigInt(1) << MachineCsrBit.MstatusMie) |
        (BigInt(1) << MachineCsrBit.MstatusMpie)
    val machineTimerMask = BigInt(1) << MachineCsrBit.MachineTimerInterrupt
    val mtvecMask = allBits & ~BigInt(3)
    val mepcMask = allBits & ~(if (isa.hasC) BigInt(1) else BigInt(3))

    val result = WireDefault(data)
    switch(address) {
      is(MachineCsrAddress.Mstatus.U) {
        val requestedMpp = data(12, 11)
        val legalMpp = if (isa.hasS && isa.hasU) {
          Mux(
            requestedMpp === PrivilegeMode.User.U ||
              requestedMpp === PrivilegeMode.Supervisor.U ||
              requestedMpp === PrivilegeMode.Machine.U,
            requestedMpp,
            PrivilegeMode.Machine.U
          )
        } else if (isa.hasS) {
          Mux(
            requestedMpp === PrivilegeMode.Supervisor.U ||
              requestedMpp === PrivilegeMode.Machine.U,
            requestedMpp,
            PrivilegeMode.Machine.U
          )
        } else if (isa.hasU) {
          Mux(
            requestedMpp === PrivilegeMode.User.U ||
              requestedMpp === PrivilegeMode.Machine.U,
            requestedMpp,
            PrivilegeMode.Machine.U
          )
        } else {
          PrivilegeMode.Machine.U(2.W)
        }
        result := (data & mstatusNonMppMask.U(xlen.W)) | (legalMpp << 11)
      }
      is(MachineCsrAddress.Mie.U) { result := data & machineTimerMask.U(xlen.W) }
      is(MachineCsrAddress.Mtvec.U) { result := data & mtvecMask.U(xlen.W) }
      is(MachineCsrAddress.Mepc.U) { result := data & mepcMask.U(xlen.W) }
    }
    result
  }
}

class MachineCsrFile(val isa: IsaConfig) extends Module {
  private val xlen = isa.xlen
  private val allBits = (BigInt(1) << xlen) - 1
  private val mstatusMie = BigInt(1) << MachineCsrBit.MstatusMie
  private val mstatusMpie = BigInt(1) << MachineCsrBit.MstatusMpie
  private val mstatusMpp = BigInt(3) << MachineCsrBit.MstatusMppLow
  private val machineTimerMask = BigInt(1) << MachineCsrBit.MachineTimerInterrupt
  private val mstatusTransitionMask = mstatusMie | mstatusMpie | mstatusMpp
  private val mstatusTransitionPreserveMask = allBits & ~mstatusTransitionMask
  private val leastPrivilege =
    if (isa.hasU) BigInt(PrivilegeMode.User)
    else if (isa.hasS) BigInt(PrivilegeMode.Supervisor)
    else BigInt(PrivilegeMode.Machine)

  private val misaValue = {
    val mxl = if (xlen == 32) BigInt(1) else BigInt(2)
    val extensionBits = isa.extensions.foldLeft(BigInt(0)) { (bits, extension) =>
      val index = extension.toUpper - 'A'
      require(index >= 0 && index < 26, s"unsupported misa extension name: $extension")
      bits | (BigInt(1) << index)
    }
    (mxl << (xlen - 2)) | extensionBits
  }

  val io = IO(new Bundle {
    val readAddr = Input(UInt(12.W))
    val readData = Output(UInt(xlen.W))
    val readImplemented = Output(Bool())
    val readWritable = Output(Bool())

    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(12.W))
    val writeData = Input(UInt(xlen.W))

    val currentPrivilege = Output(UInt(2.W))

    val timerInterrupt = Input(Bool())
    val machineTimerInterrupt = Output(Bool())

    val trapEnter = Input(Bool())
    val trapPc = Input(UInt(xlen.W))
    val trapCause = Input(UInt(xlen.W))
    val trapValue = Input(UInt(xlen.W))
    val trapVector = Output(UInt(xlen.W))

    val trapReturn = Input(Bool())
    val returnPc = Output(UInt(xlen.W))
  })

  val privilege = RegInit(PrivilegeMode.Machine.U(2.W))
  val mstatus = RegInit(0.U(xlen.W))
  val mie = RegInit(0.U(xlen.W))
  val mtvec = RegInit(0.U(xlen.W))
  val mscratch = RegInit(0.U(xlen.W))
  val mepc = RegInit(0.U(xlen.W))
  val mcause = RegInit(0.U(xlen.W))
  val mtval = RegInit(0.U(xlen.W))

  val canonicalWriteData = MachineCsrWarl.canonicalize(isa, io.writeAddr, io.writeData)
  val ordinaryWrite = io.writeEnable

  // Interrupt entry happens after the current instruction retires. Preview an
  // ordinary CSR write so an enabling/disabling write affects the same precise
  // boundary and a retiring mtvec write selects the new handler immediately.
  val effectiveMstatus = Mux(
    ordinaryWrite && io.writeAddr === MachineCsrAddress.Mstatus.U,
    canonicalWriteData,
    mstatus
  )
  val effectiveMie = Mux(
    ordinaryWrite && io.writeAddr === MachineCsrAddress.Mie.U,
    canonicalWriteData,
    mie
  )
  val effectiveMtvec = Mux(
    ordinaryWrite && io.writeAddr === MachineCsrAddress.Mtvec.U,
    canonicalWriteData,
    mtvec
  )
  val effectiveMscratch = Mux(
    ordinaryWrite && io.writeAddr === MachineCsrAddress.Mscratch.U,
    canonicalWriteData,
    mscratch
  )

  val mipValue = Mux(io.timerInterrupt, machineTimerMask.U(xlen.W), 0.U(xlen.W))
  val machineInterruptGloballyEnabled =
    privilege < PrivilegeMode.Machine.U || effectiveMstatus(MachineCsrBit.MstatusMie)

  io.currentPrivilege := privilege
  io.machineTimerInterrupt :=
    io.timerInterrupt && effectiveMie(MachineCsrBit.MachineTimerInterrupt) &&
      machineInterruptGloballyEnabled
  io.trapVector := effectiveMtvec
  io.returnPc := mepc
  io.readData := 0.U
  io.readImplemented := false.B
  io.readWritable := false.B

  switch(io.readAddr) {
    is(MachineCsrAddress.Mstatus.U) {
      io.readData := mstatus
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Misa.U) {
      io.readData := misaValue.U(xlen.W)
      io.readImplemented := true.B
    }
    is(MachineCsrAddress.Mie.U) {
      io.readData := mie
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mtvec.U) {
      io.readData := mtvec
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mscratch.U) {
      io.readData := mscratch
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mepc.U) {
      io.readData := mepc
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mcause.U) {
      io.readData := mcause
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mtval.U) {
      io.readData := mtval
      io.readImplemented := true.B
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mip.U) {
      io.readData := mipValue
      io.readImplemented := true.B
    }
  }

  val canonicalTrapPc = MachineCsrWarl.canonicalize(isa, MachineCsrAddress.Mepc.U, io.trapPc)
  val trapMstatus =
    (effectiveMstatus & mstatusTransitionPreserveMask.U(xlen.W)) |
      (effectiveMstatus(MachineCsrBit.MstatusMie).asUInt << MachineCsrBit.MstatusMpie) |
      (privilege << MachineCsrBit.MstatusMppLow)
  val returnPrivilege = mstatus(12, 11)
  val returnMstatus =
    (mstatus & mstatusTransitionPreserveMask.U(xlen.W)) |
      (mstatus(MachineCsrBit.MstatusMpie).asUInt << MachineCsrBit.MstatusMie) |
      mstatusMpie.U(xlen.W) |
      (leastPrivilege << MachineCsrBit.MstatusMppLow).U(xlen.W)

  when(io.trapEnter) {
    // Preserve any retiring ordinary CSR write that is not overwritten by the
    // trap CSRs themselves. This models "retire instruction, then take IRQ".
    privilege := PrivilegeMode.Machine.U
    mstatus := trapMstatus
    mie := effectiveMie
    mtvec := effectiveMtvec
    mscratch := effectiveMscratch
    mepc := canonicalTrapPc
    mcause := io.trapCause
    mtval := io.trapValue
  }.elsewhen(io.trapReturn) {
    privilege := returnPrivilege
    mstatus := returnMstatus
  }.elsewhen(ordinaryWrite) {
    switch(io.writeAddr) {
      is(MachineCsrAddress.Mstatus.U) { mstatus := canonicalWriteData }
      is(MachineCsrAddress.Mie.U) { mie := canonicalWriteData }
      is(MachineCsrAddress.Mtvec.U) { mtvec := canonicalWriteData }
      is(MachineCsrAddress.Mscratch.U) { mscratch := canonicalWriteData }
      is(MachineCsrAddress.Mepc.U) { mepc := canonicalWriteData }
      is(MachineCsrAddress.Mcause.U) { mcause := canonicalWriteData }
      is(MachineCsrAddress.Mtval.U) { mtval := canonicalWriteData }
    }
  }
}
