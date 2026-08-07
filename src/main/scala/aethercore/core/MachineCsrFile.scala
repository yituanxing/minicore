package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.PrivilegeMode
import aethercore.config.IsaConfig

object MachineCsrAddress {
  val Mstatus: Int = 0x300
  val Misa: Int = 0x301
  val Medeleg: Int = 0x302
  val Mideleg: Int = 0x303
  val Mie: Int = 0x304
  val Mtvec: Int = 0x305
  val Mscratch: Int = 0x340
  val Mepc: Int = 0x341
  val Mcause: Int = 0x342
  val Mtval: Int = 0x343
  val Mip: Int = 0x344
  val Mhartid: Int = 0xf14
}

object SupervisorCsrAddress {
  val Sstatus: Int = 0x100
  val Sie: Int = 0x104
  val Stvec: Int = 0x105
  val Sscratch: Int = 0x140
  val Sepc: Int = 0x141
  val Scause: Int = 0x142
  val Stval: Int = 0x143
  val Sip: Int = 0x144
  val Satp: Int = 0x180
}

object MachineCsrBit {
  val SstatusSie: Int = 1
  val MstatusMie: Int = 3
  val SstatusSpie: Int = 5
  val MstatusMpie: Int = 7
  val SstatusSpp: Int = 8
  val MstatusMppLow: Int = 11
  val SstatusSum: Int = 18
  val SstatusMxr: Int = 19
  val MachineTimerInterrupt: Int = 7
  val MachineExternalInterrupt: Int = 11
}

object MachineCsrWarl {
  private def supervisorStatusMask(isa: IsaConfig): BigInt = {
    if (!isa.hasS) BigInt(0)
    else {
      val v1Mask =
        (BigInt(1) << MachineCsrBit.SstatusSie) |
          (BigInt(1) << MachineCsrBit.SstatusSpie) |
          (BigInt(1) << MachineCsrBit.SstatusSpp)
      if (isa.hasSv32)
        v1Mask |
          (BigInt(1) << MachineCsrBit.SstatusSum) |
          (BigInt(1) << MachineCsrBit.SstatusMxr)
      else v1Mask
    }
  }

  private def machineStatusMask(isa: IsaConfig): BigInt =
    (BigInt(1) << MachineCsrBit.MstatusMie) |
      (BigInt(1) << MachineCsrBit.MstatusMpie) |
      (BigInt(3) << MachineCsrBit.MstatusMppLow) |
      supervisorStatusMask(isa)

  private def delegableExceptionMask(isa: IsaConfig): BigInt = {
    if (!isa.hasS) BigInt(0)
    else {
      // V1 only delegates synchronous exception classes the core already
      // implements. ECALL-from-M (11) is intentionally not delegable. Page
      // faults are added only when the translation path itself is integrated.
      Seq(1, 2, 3, 5, 7, 8, 9).foldLeft(BigInt(0))((mask, bit) => mask | (BigInt(1) << bit))
    }
  }

  def canonicalize(isa: IsaConfig, address: UInt, data: UInt): UInt = {
    val xlen = isa.xlen
    val allBits = (BigInt(1) << xlen) - 1
    val machineInterruptMask =
      (BigInt(1) << MachineCsrBit.MachineTimerInterrupt) |
        (BigInt(1) << MachineCsrBit.MachineExternalInterrupt)
    val mtvecMask = allBits & ~BigInt(3)
    val epcMask = allBits & ~(if (isa.hasC) BigInt(1) else BigInt(3))
    val sstatusMask = supervisorStatusMask(isa)

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
        result :=
          (data & (machineStatusMask(isa) & ~(BigInt(3) << MachineCsrBit.MstatusMppLow)).U(xlen.W)) |
            (legalMpp << MachineCsrBit.MstatusMppLow)
      }
      is(MachineCsrAddress.Medeleg.U) {
        result := data & delegableExceptionMask(isa).U(xlen.W)
      }
      is(MachineCsrAddress.Mideleg.U) {
        // No supervisor-level interrupt source is wired yet. Keep mideleg
        // architecturally present but WARL-zero rather than pretending that
        // MTIP/MEIP are supervisor interrupts.
        result := 0.U
      }
      is(MachineCsrAddress.Mie.U) { result := data & machineInterruptMask.U(xlen.W) }
      is(MachineCsrAddress.Mtvec.U) { result := data & mtvecMask.U(xlen.W) }
      is(MachineCsrAddress.Mepc.U) { result := data & epcMask.U(xlen.W) }
      is(SupervisorCsrAddress.Sstatus.U) { result := data & sstatusMask.U(xlen.W) }
      is(SupervisorCsrAddress.Sie.U) { result := 0.U }
      is(SupervisorCsrAddress.Stvec.U) { result := data & mtvecMask.U(xlen.W) }
      is(SupervisorCsrAddress.Sepc.U) { result := data & epcMask.U(xlen.W) }
      is(SupervisorCsrAddress.Sip.U) { result := 0.U }
    }

    if (isa.hasPmp) {
      when(address === PmpCsrAddress.Pmpcfg0.U) {
        result := PmpCsrWarl.canonicalize(isa, address, data)
      }
      for (entry <- 0 until isa.pmpEntries) {
        when(address === PmpCsrAddress.pmpaddr(entry).U) {
          result := PmpCsrWarl.canonicalize(isa, address, data)
        }
      }
    }
    result
  }
}

class MachineCsrFile(
    val isa: IsaConfig,
    val withMachineExternalInterrupt: Boolean = false
) extends Module {
  private val xlen = isa.xlen
  private val allBits = (BigInt(1) << xlen) - 1
  private val sstatusSie = BigInt(1) << MachineCsrBit.SstatusSie
  private val mstatusMie = BigInt(1) << MachineCsrBit.MstatusMie
  private val sstatusSpie = BigInt(1) << MachineCsrBit.SstatusSpie
  private val mstatusMpie = BigInt(1) << MachineCsrBit.MstatusMpie
  private val sstatusSpp = BigInt(1) << MachineCsrBit.SstatusSpp
  private val sstatusSum = BigInt(1) << MachineCsrBit.SstatusSum
  private val sstatusMxr = BigInt(1) << MachineCsrBit.SstatusMxr
  private val mstatusMpp = BigInt(3) << MachineCsrBit.MstatusMppLow
  private val supervisorStatusMask =
    if (isa.hasS) {
      val v1Mask = sstatusSie | sstatusSpie | sstatusSpp
      if (isa.hasSv32) v1Mask | sstatusSum | sstatusMxr else v1Mask
    } else BigInt(0)
  private val machineTimerMask = BigInt(1) << MachineCsrBit.MachineTimerInterrupt
  private val machineExternalMask = BigInt(1) << MachineCsrBit.MachineExternalInterrupt
  private val mstatusTransitionMask = mstatusMie | mstatusMpie | mstatusMpp
  private val mstatusTransitionPreserveMask = allBits & ~mstatusTransitionMask
  private val sstatusTransitionMask = sstatusSie | sstatusSpie | sstatusSpp
  private val sstatusTransitionPreserveMask = allBits & ~sstatusTransitionMask
  private val leastPrivilege =
    if (isa.hasU) BigInt(PrivilegeMode.User)
    else if (isa.hasS) BigInt(PrivilegeMode.Supervisor)
    else BigInt(PrivilegeMode.Machine)

  private val misaValue = {
    val mxl = if (xlen == 32) BigInt(1) else BigInt(2)
    val instructionExtensionBits = isa.extensions.foldLeft(BigInt(0)) { (bits, extension) =>
      val index = extension.toUpper - 'A'
      require(index >= 0 && index < 26, s"unsupported misa extension name: $extension")
      bits | (BigInt(1) << index)
    }
    val privilegeExtensionBits =
      (if (isa.hasS) BigInt(1) << ('S' - 'A') else BigInt(0)) |
        (if (isa.hasU) BigInt(1) << ('U' - 'A') else BigInt(0))
    (mxl << (xlen - 2)) | instructionExtensionBits | privilegeExtensionBits
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
    val supervisorSum = Output(Bool())
    val supervisorMxr = Output(Bool())
    val satpTranslationEnabled = Output(Bool())
    val satpRootPpn = Output(UInt(Sv32Satp.PpnBits.W))
    val satpAsid = Output(UInt(Sv32Satp.AsidBits.W))
    val pmpConfig = Output(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Output(Vec(PmpConstants.MaxEntries, UInt((xlen - 2).W)))

    val timerInterrupt = Input(Bool())
    val machineTimerInterrupt = Output(Bool())
    val externalInterrupt = if (withMachineExternalInterrupt) Some(Input(Bool())) else None
    val machineExternalInterrupt =
      if (withMachineExternalInterrupt) Some(Output(Bool())) else None

    val trapEnter = Input(Bool())
    val trapPc = Input(UInt(xlen.W))
    val trapCause = Input(UInt(xlen.W))
    val trapValue = Input(UInt(xlen.W))
    val trapVector = Output(UInt(xlen.W))
    val trapDelegatedToSupervisor = Output(Bool())

    // trapReturn is the retirement pulse; trapReturnSupervisor identifies
    // SRET explicitly. This matters because SRET is legal in M-mode as well
    // as S-mode, so current privilege alone cannot identify the xRET kind.
    val trapReturn = Input(Bool())
    val trapReturnSupervisor = Input(Bool())
    val returnPc = Output(UInt(xlen.W))
  })

  val privilege = RegInit(PrivilegeMode.Machine.U(2.W))
  val mstatus = RegInit(0.U(xlen.W))
  val medeleg = RegInit(0.U(xlen.W))
  val mideleg = RegInit(0.U(xlen.W))
  val mie = RegInit(0.U(xlen.W))
  val mtvec = RegInit(0.U(xlen.W))
  val mscratch = RegInit(0.U(xlen.W))
  val mepc = RegInit(0.U(xlen.W))
  val mcause = RegInit(0.U(xlen.W))
  val mtval = RegInit(0.U(xlen.W))
  val stvec = RegInit(0.U(xlen.W))
  val sscratch = RegInit(0.U(xlen.W))
  val sepc = RegInit(0.U(xlen.W))
  val scause = RegInit(0.U(xlen.W))
  val stval = RegInit(0.U(xlen.W))

  val pmp = Module(new PmpCsrFile(isa))
  pmp.io.readAddr := io.readAddr
  pmp.io.writeEnable := io.writeEnable
  pmp.io.writeAddr := io.writeAddr
  pmp.io.writeData := io.writeData
  io.pmpConfig := pmp.io.config
  io.pmpAddress := pmp.io.pmpAddress

  val canonicalWriteData = MachineCsrWarl.canonicalize(isa, io.writeAddr, io.writeData)
  val ordinaryWrite = io.writeEnable

  val satp = if (isa.hasSv32) Some(Module(new Sv32SatpRegister)) else None
  if (isa.hasSv32) {
    satp.get.io.writeEnable := ordinaryWrite && io.writeAddr === SupervisorCsrAddress.Satp.U
    satp.get.io.writeData := io.writeData(31, 0)
    io.satpTranslationEnabled := satp.get.io.translationEnabled
    io.satpRootPpn := satp.get.io.rootPpn
    io.satpAsid := satp.get.io.asid
  } else {
    io.satpTranslationEnabled := false.B
    io.satpRootPpn := 0.U
    io.satpAsid := 0.U
  }
  io.supervisorSum := if (isa.hasSv32) mstatus(MachineCsrBit.SstatusSum) else false.B
  io.supervisorMxr := if (isa.hasSv32) mstatus(MachineCsrBit.SstatusMxr) else false.B

  val sstatusWriteValue =
    (mstatus & (~supervisorStatusMask & allBits).U(xlen.W)) |
      (canonicalWriteData & supervisorStatusMask.U(xlen.W))
  val effectiveMstatus = Mux(
    ordinaryWrite && io.writeAddr === MachineCsrAddress.Mstatus.U,
    canonicalWriteData,
    Mux(
      ordinaryWrite && io.writeAddr === SupervisorCsrAddress.Sstatus.U,
      sstatusWriteValue,
      mstatus
    )
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
  val effectiveStvec = Mux(
    ordinaryWrite && io.writeAddr === SupervisorCsrAddress.Stvec.U,
    canonicalWriteData,
    stvec
  )
  val effectiveMscratch = Mux(
    ordinaryWrite && io.writeAddr === MachineCsrAddress.Mscratch.U,
    canonicalWriteData,
    mscratch
  )

  val rawExternalInterrupt =
    if (withMachineExternalInterrupt) io.externalInterrupt.get else false.B
  val mipValue =
    Mux(io.timerInterrupt, machineTimerMask.U(xlen.W), 0.U(xlen.W)) |
      Mux(rawExternalInterrupt, machineExternalMask.U(xlen.W), 0.U(xlen.W))
  val machineInterruptGloballyEnabled =
    privilege < PrivilegeMode.Machine.U || effectiveMstatus(MachineCsrBit.MstatusMie)

  val trapIsInterrupt = io.trapCause(xlen - 1)
  val trapCauseIndex = io.trapCause(log2Ceil(xlen) - 1, 0)
  val delegatedException = medeleg(trapCauseIndex)
  val delegatedInterrupt = mideleg(trapCauseIndex)
  val trapDelegatedToSupervisor =
    isa.hasS.B && privilege =/= PrivilegeMode.Machine.U &&
      Mux(trapIsInterrupt, delegatedInterrupt, delegatedException)

  io.currentPrivilege := privilege
  io.machineTimerInterrupt :=
    io.timerInterrupt && effectiveMie(MachineCsrBit.MachineTimerInterrupt) &&
      machineInterruptGloballyEnabled
  if (withMachineExternalInterrupt) {
    io.machineExternalInterrupt.get :=
      rawExternalInterrupt && effectiveMie(MachineCsrBit.MachineExternalInterrupt) &&
        machineInterruptGloballyEnabled
  }
  io.trapDelegatedToSupervisor := trapDelegatedToSupervisor
  io.trapVector := Mux(trapDelegatedToSupervisor, effectiveStvec, effectiveMtvec)
  val returningViaSupervisor =
    isa.hasS.B && (io.trapReturnSupervisor || privilege === PrivilegeMode.Supervisor.U)
  io.returnPc := Mux(returningViaSupervisor, sepc, mepc)
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
      // mip is a writable CSR address even when all implemented pending bits are
      // driven by hardware. Software writes are accepted and ignored.
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mhartid.U) {
      io.readData := 0.U
      io.readImplemented := true.B
    }
  }

  if (isa.hasS) {
    switch(io.readAddr) {
      is(MachineCsrAddress.Medeleg.U) {
        io.readData := medeleg
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(MachineCsrAddress.Mideleg.U) {
        io.readData := mideleg
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Sstatus.U) {
        io.readData := mstatus & supervisorStatusMask.U(xlen.W)
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Sie.U) {
        io.readData := mie & mideleg
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Stvec.U) {
        io.readData := stvec
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Sscratch.U) {
        io.readData := sscratch
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Sepc.U) {
        io.readData := sepc
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Scause.U) {
        io.readData := scause
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Stval.U) {
        io.readData := stval
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Sip.U) {
        io.readData := mipValue & mideleg
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      if (isa.hasSv32) {
        is(SupervisorCsrAddress.Satp.U) {
          io.readData := satp.get.io.readData
          io.readImplemented := true.B
          io.readWritable := true.B
        }
      }
    }
  }

  when(pmp.io.readImplemented) {
    io.readData := pmp.io.readData
    io.readImplemented := true.B
    io.readWritable := pmp.io.readWritable
  }

  val canonicalMachineTrapPc = MachineCsrWarl.canonicalize(isa, MachineCsrAddress.Mepc.U, io.trapPc)
  val canonicalSupervisorTrapPc = MachineCsrWarl.canonicalize(isa, SupervisorCsrAddress.Sepc.U, io.trapPc)
  val machineTrapMstatus =
    (effectiveMstatus & mstatusTransitionPreserveMask.U(xlen.W)) |
      (effectiveMstatus(MachineCsrBit.MstatusMie).asUInt << MachineCsrBit.MstatusMpie) |
      (privilege << MachineCsrBit.MstatusMppLow)
  val supervisorTrapMstatus =
    (effectiveMstatus & sstatusTransitionPreserveMask.U(xlen.W)) |
      (effectiveMstatus(MachineCsrBit.SstatusSie).asUInt << MachineCsrBit.SstatusSpie) |
      ((privilege === PrivilegeMode.Supervisor.U).asUInt << MachineCsrBit.SstatusSpp)

  val machineReturnPrivilege = mstatus(12, 11)
  val machineReturnMstatus =
    (mstatus & mstatusTransitionPreserveMask.U(xlen.W)) |
      (mstatus(MachineCsrBit.MstatusMpie).asUInt << MachineCsrBit.MstatusMie) |
      mstatusMpie.U(xlen.W) |
      (leastPrivilege << MachineCsrBit.MstatusMppLow).U(xlen.W)

  val supervisorReturnPrivilege =
    if (isa.hasU) Mux(mstatus(MachineCsrBit.SstatusSpp), PrivilegeMode.Supervisor.U, PrivilegeMode.User.U)
    else PrivilegeMode.Supervisor.U(2.W)
  val supervisorReturnMstatus =
    (mstatus & sstatusTransitionPreserveMask.U(xlen.W)) |
      (mstatus(MachineCsrBit.SstatusSpie).asUInt << MachineCsrBit.SstatusSie) |
      sstatusSpie.U(xlen.W)

  when(io.trapEnter) {
    when(trapDelegatedToSupervisor) {
      privilege := PrivilegeMode.Supervisor.U
      mstatus := supervisorTrapMstatus
      sepc := canonicalSupervisorTrapPc
      scause := io.trapCause
      stval := io.trapValue
    }.otherwise {
      privilege := PrivilegeMode.Machine.U
      mstatus := machineTrapMstatus
      mepc := canonicalMachineTrapPc
      mcause := io.trapCause
      mtval := io.trapValue
    }
    mie := effectiveMie
    mtvec := effectiveMtvec
    stvec := effectiveStvec
    mscratch := effectiveMscratch
  }.elsewhen(io.trapReturn) {
    when(isa.hasS.B && io.trapReturnSupervisor) {
      privilege := supervisorReturnPrivilege
      mstatus := supervisorReturnMstatus
    }.otherwise {
      privilege := machineReturnPrivilege
      mstatus := machineReturnMstatus
    }
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

    if (isa.hasS) {
      switch(io.writeAddr) {
        is(MachineCsrAddress.Medeleg.U) { medeleg := canonicalWriteData }
        is(MachineCsrAddress.Mideleg.U) { mideleg := canonicalWriteData }
        is(SupervisorCsrAddress.Sstatus.U) { mstatus := sstatusWriteValue }
        is(SupervisorCsrAddress.Sie.U) {
          // There are still no delegated supervisor interrupt bits, so this is
          // a writable WARL-zero alias and deliberately leaves mie unchanged.
        }
        is(SupervisorCsrAddress.Stvec.U) { stvec := canonicalWriteData }
        is(SupervisorCsrAddress.Sscratch.U) { sscratch := canonicalWriteData }
        is(SupervisorCsrAddress.Sepc.U) { sepc := canonicalWriteData }
        is(SupervisorCsrAddress.Scause.U) { scause := canonicalWriteData }
        is(SupervisorCsrAddress.Stval.U) { stval := canonicalWriteData }
        is(SupervisorCsrAddress.Sip.U) {
          // All currently implemented pending bits are hardware-driven.
        }
      }
    }
  }
}
