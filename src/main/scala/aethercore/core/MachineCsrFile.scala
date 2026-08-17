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
  val Mcounteren: Int = Rv32SstcCsrAddress.Mcounteren
  val Mstatush: Int = 0x310
  val Menvcfg: Int = Rv32SstcCsrAddress.Menvcfg
  val Menvcfgh: Int = Rv32SstcCsrAddress.Menvcfgh
  val Mcountinhibit: Int = 0x320
  val Mscratch: Int = 0x340
  val Mepc: Int = 0x341
  val Mcause: Int = 0x342
  val Mtval: Int = 0x343
  val Mip: Int = 0x344
  val Mvendorid: Int = 0xf11
  val Marchid: Int = 0xf12
  val Mimpid: Int = 0xf13
  val Mhartid: Int = 0xf14
}

object SupervisorCsrAddress {
  val Sstatus: Int = 0x100
  val Sie: Int = 0x104
  val Stvec: Int = 0x105
  val Scounteren: Int = 0x106
  val Sscratch: Int = 0x140
  val Sepc: Int = 0x141
  val Scause: Int = 0x142
  val Stval: Int = 0x143
  val Sip: Int = 0x144
  val Stimecmp: Int = Rv32SstcCsrAddress.Stimecmp
  val Stimecmph: Int = Rv32SstcCsrAddress.Stimecmph
  val Satp: Int = 0x180
}

object MachineCsrBit {
  val SstatusSie: Int = 1
  val MstatusMie: Int = 3
  val SstatusSpie: Int = 5
  val MstatusMpie: Int = 7
  val SstatusSpp: Int = 8
  val MstatusMppLow: Int = 11
  val MstatusMprv: Int = 17
  val SstatusSum: Int = 18
  val SstatusMxr: Int = 19
  val MstatusUxlLow: Int = 32
  val MstatusSxlLow: Int = 34
  val SupervisorTimerInterrupt: Int = Rv32SstcBit.SupervisorTimerInterrupt
  val MachineTimerInterrupt: Int = 7
  val SupervisorExternalInterrupt: Int = 9
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
      (if (isa.hasU) BigInt(1) << MachineCsrBit.MstatusMprv else BigInt(0)) |
      supervisorStatusMask(isa)

  /**
    * AetherCore currently implements one XLEN for each enabled RV64 lower
    * privilege mode. Model SXL/UXL as read-only WARL value 2 (64 bits).
    *
    * 当前 RV64 下层特权态只实现 64 位 XLEN，因此 SXL/UXL 固定为 WARL=2。
    */
  def mstatusXlenValue(isa: IsaConfig): BigInt = {
    if (isa.xlen != 64) BigInt(0)
    else
      (if (isa.hasU) BigInt(2) << MachineCsrBit.MstatusUxlLow else BigInt(0)) |
        (if (isa.hasS) BigInt(2) << MachineCsrBit.MstatusSxlLow else BigInt(0))
  }

  /** sstatus exposes UXL when RV64 S-mode can host U-mode. */
  def sstatusXlenValue(isa: IsaConfig): BigInt = {
    if (isa.xlen == 64 && isa.hasS && isa.hasU)
      BigInt(2) << MachineCsrBit.MstatusUxlLow
    else BigInt(0)
  }

  private def delegableExceptionMask(isa: IsaConfig): BigInt = {
    if (!isa.hasS) BigInt(0)
    else {
      val v1Causes = Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
      val implementedCauses =
        if (isa.hasSv32) v1Causes ++ Seq(12, 13, 15)
        else v1Causes
      implementedCauses.foldLeft(BigInt(0))((mask, bit) => mask | (BigInt(1) << bit))
    }
  }

  def canonicalize(
      isa: IsaConfig,
      address: UInt,
      data: UInt,
      withSupervisorExternalInterrupt: Boolean = false
  ): UInt =
    canonicalize(isa, isa.xlen, address, data, withSupervisorExternalInterrupt)

  def canonicalize(
      isa: IsaConfig,
      paddrBits: Int,
      address: UInt,
      data: UInt,
      withSupervisorExternalInterrupt: Boolean
  ): UInt = {
    val xlen = isa.xlen
    val allBits = (BigInt(1) << xlen) - 1
    val supervisorTimerMask =
      if (isa.hasSstc) BigInt(1) << MachineCsrBit.SupervisorTimerInterrupt else BigInt(0)
    val supervisorExternalMask =
      if (isa.hasS && withSupervisorExternalInterrupt)
        BigInt(1) << MachineCsrBit.SupervisorExternalInterrupt
      else BigInt(0)
    val supervisorInterruptMask = supervisorTimerMask | supervisorExternalMask
    val timeCounterMask =
      if (isa.hasSstc) BigInt(1) << Rv32SstcBit.McounterenTime else BigInt(0)
    val machineInterruptMask =
      supervisorInterruptMask |
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
            (legalMpp << MachineCsrBit.MstatusMppLow) |
            mstatusXlenValue(isa).U(xlen.W)
      }
      is(MachineCsrAddress.Mstatush.U) { result := 0.U }
      is(MachineCsrAddress.Medeleg.U) {
        result := data & delegableExceptionMask(isa).U(xlen.W)
      }
      is(MachineCsrAddress.Mideleg.U) {
        result := data & supervisorInterruptMask.U(xlen.W)
      }
      is(MachineCsrAddress.Mie.U) { result := data & machineInterruptMask.U(xlen.W) }
      is(MachineCsrAddress.Mcounteren.U) {
        result := data & timeCounterMask.U(xlen.W)
      }
      is(MachineCsrAddress.Mcountinhibit.U) { result := 0.U }
      is(MachineCsrAddress.Menvcfg.U) { result := 0.U }
      is(MachineCsrAddress.Menvcfgh.U) {
        val mask = if (isa.hasSstc) BigInt(1) << Rv32SstcBit.MenvcfghStce else BigInt(0)
        result := data & mask.U(xlen.W)
      }
      is(MachineCsrAddress.Mtvec.U) { result := data & mtvecMask.U(xlen.W) }
      is(MachineCsrAddress.Mepc.U) { result := data & epcMask.U(xlen.W) }
      is(SupervisorCsrAddress.Sstatus.U) {
        result := (data & sstatusMask.U(xlen.W)) | sstatusXlenValue(isa).U(xlen.W)
      }
      is(SupervisorCsrAddress.Sie.U) { result := data & supervisorInterruptMask.U(xlen.W) }
      is(SupervisorCsrAddress.Scounteren.U) { result := data & timeCounterMask.U(xlen.W) }
      is(SupervisorCsrAddress.Stvec.U) { result := data & mtvecMask.U(xlen.W) }
      is(SupervisorCsrAddress.Sepc.U) { result := data & epcMask.U(xlen.W) }
      is(SupervisorCsrAddress.Sip.U) { result := 0.U }
    }

    if (isa.hasPmp) {
      for (bank <- 0 until PmpConstants.ConfigCsrCount) {
        val firstEntry = bank * PmpConstants.ConfigEntriesPerCsr
        if (firstEntry < isa.pmpEntries) {
          when(address === PmpCsrAddress.pmpcfg(bank).U) {
            result := PmpCsrWarl.canonicalize(isa, paddrBits, address, data)
          }
        }
      }
      for (entry <- 0 until isa.pmpEntries) {
        when(address === PmpCsrAddress.pmpaddr(entry).U) {
          result := PmpCsrWarl.canonicalize(isa, paddrBits, address, data)
        }
      }
    }
    result
  }
}

class MachineCsrFile(
    val isa: IsaConfig,
    val paddrBits: Int,
    val withMachineExternalInterrupt: Boolean,
    val withSupervisorExternalInterrupt: Boolean
) extends Module {
  def this(isa: IsaConfig) = this(isa, isa.xlen, false, false)
  def this(isa: IsaConfig, withMachineExternalInterrupt: Boolean) =
    this(isa, isa.xlen, withMachineExternalInterrupt, false)
  def this(
      isa: IsaConfig,
      withMachineExternalInterrupt: Boolean,
      withSupervisorExternalInterrupt: Boolean
  ) = this(isa, isa.xlen, withMachineExternalInterrupt, withSupervisorExternalInterrupt)

  require(!withSupervisorExternalInterrupt || isa.hasS, "supervisor external interrupt requires S-mode")

  private val xlen = isa.xlen
  private val pmpGeometry = PmpGeometry(xlen, paddrBits)
  private val pmpAddressBits = pmpGeometry.encodedAddressBits
  private val allBits = (BigInt(1) << xlen) - 1
  private val sstatusSie = BigInt(1) << MachineCsrBit.SstatusSie
  private val mstatusMie = BigInt(1) << MachineCsrBit.MstatusMie
  private val sstatusSpie = BigInt(1) << MachineCsrBit.SstatusSpie
  private val mstatusMpie = BigInt(1) << MachineCsrBit.MstatusMpie
  private val sstatusSpp = BigInt(1) << MachineCsrBit.SstatusSpp
  private val mstatusMprv = BigInt(1) << MachineCsrBit.MstatusMprv
  private val sstatusSum = BigInt(1) << MachineCsrBit.SstatusSum
  private val sstatusMxr = BigInt(1) << MachineCsrBit.SstatusMxr
  private val mstatusMpp = BigInt(3) << MachineCsrBit.MstatusMppLow
  private val supervisorStatusMask =
    if (isa.hasS) {
      val v1Mask = sstatusSie | sstatusSpie | sstatusSpp
      if (isa.hasSv32) v1Mask | sstatusSum | sstatusMxr else v1Mask
    } else BigInt(0)
  private val mstatusXlenValue = MachineCsrWarl.mstatusXlenValue(isa)
  private val sstatusXlenValue = MachineCsrWarl.sstatusXlenValue(isa)
  private val supervisorTimerMask =
    if (isa.hasSstc) BigInt(1) << MachineCsrBit.SupervisorTimerInterrupt else BigInt(0)
  private val supervisorExternalMask =
    if (withSupervisorExternalInterrupt)
      BigInt(1) << MachineCsrBit.SupervisorExternalInterrupt
    else BigInt(0)
  private val supervisorInterruptMask = supervisorTimerMask | supervisorExternalMask
  private val machineTimerMask = BigInt(1) << MachineCsrBit.MachineTimerInterrupt
  private val machineExternalMask = BigInt(1) << MachineCsrBit.MachineExternalInterrupt
  private val mstatusTransitionMask = mstatusMie | mstatusMpie | mstatusMpp
  private val mstatusTransitionPreserveMask = allBits & ~mstatusTransitionMask
  private val sstatusTransitionMask = sstatusSie | sstatusSpie | sstatusSpp
  private val sstatusTransitionPreserveMask = allBits & ~sstatusTransitionMask
  private val mprvClearMask = allBits & ~mstatusMprv
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
    val effectiveDataPrivilege = Output(UInt(2.W))
    val supervisorSum = Output(Bool())
    val supervisorMxr = Output(Bool())
    val satpTranslationEnabled = Output(Bool())
    val satpRootPpn = Output(UInt(Sv32Satp.PpnBits.W))
    val satpAsid = Output(UInt(Sv32Satp.AsidBits.W))
    val pmpConfig = Output(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Output(Vec(PmpConstants.MaxEntries, UInt(pmpAddressBits.W)))

    val timerInterrupt = Input(Bool())
    val machineTimerInterrupt = Output(Bool())
    val time = if (isa.hasSstc) Some(Input(UInt(64.W))) else None
    val supervisorTimerPending = if (isa.hasSstc) Some(Output(Bool())) else None
    val supervisorTimerInterrupt = if (isa.hasSstc) Some(Output(Bool())) else None
    val externalInterrupt = if (withMachineExternalInterrupt) Some(Input(Bool())) else None
    val machineExternalInterrupt =
      if (withMachineExternalInterrupt) Some(Output(Bool())) else None
    val supervisorExternalInterruptPending =
      if (withSupervisorExternalInterrupt) Some(Input(Bool())) else None
    val supervisorExternalInterrupt =
      if (withSupervisorExternalInterrupt) Some(Output(Bool())) else None

    val trapEnter = Input(Bool())
    val trapPc = Input(UInt(xlen.W))
    val trapCause = Input(UInt(xlen.W))
    val trapValue = Input(UInt(xlen.W))
    val trapVector = Output(UInt(xlen.W))
    val trapDelegatedToSupervisor = Output(Bool())

    val trapReturn = Input(Bool())
    val trapReturnSupervisor = Input(Bool())
    val returnPc = Output(UInt(xlen.W))
  })

  val privilege = RegInit(PrivilegeMode.Machine.U(2.W))
  val mstatus = RegInit(mstatusXlenValue.U(xlen.W))
  val medeleg = RegInit(0.U(xlen.W))
  val mideleg = RegInit(0.U(xlen.W))
  val mie = RegInit(0.U(xlen.W))
  val mcounteren = RegInit(0.U(xlen.W))
  val scounteren = RegInit(0.U(xlen.W))
  val menvcfgh = RegInit(0.U(xlen.W))
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

  val pmp = Module(new PmpCsrFile(isa, paddrBits))
  pmp.io.readAddr := io.readAddr
  pmp.io.writeEnable := io.writeEnable
  pmp.io.writeAddr := io.writeAddr
  pmp.io.writeData := io.writeData
  io.pmpConfig := pmp.io.config
  io.pmpAddress := pmp.io.pmpAddress

  val canonicalWriteData = MachineCsrWarl.canonicalize(
    isa,
    paddrBits,
    io.writeAddr,
    io.writeData,
    withSupervisorExternalInterrupt
  )
  val ordinaryWrite = io.writeEnable

  val sstc = if (isa.hasSstc) Some(Module(new Rv32SstcTimer)) else None
  val timeAccessAllowed = if (isa.hasSstc) {
    privilege === PrivilegeMode.Machine.U ||
      (privilege === PrivilegeMode.Supervisor.U && mcounteren(Rv32SstcBit.McounterenTime)) ||
      (privilege === PrivilegeMode.User.U &&
        mcounteren(Rv32SstcBit.McounterenTime) && scounteren(Rv32SstcBit.McounterenTime))
  } else false.B
  val stimecmpAccessAllowed = if (isa.hasSstc) {
    privilege === PrivilegeMode.Machine.U ||
      (privilege === PrivilegeMode.Supervisor.U && menvcfgh(Rv32SstcBit.MenvcfghStce))
  } else false.B

  if (isa.hasSstc) {
    sstc.get.io.time := io.time.get
    sstc.get.io.writeLow := ordinaryWrite && stimecmpAccessAllowed &&
      io.writeAddr === SupervisorCsrAddress.Stimecmp.U
    sstc.get.io.writeHigh := ordinaryWrite && stimecmpAccessAllowed &&
      io.writeAddr === SupervisorCsrAddress.Stimecmph.U
    sstc.get.io.writeData := io.writeData(31, 0)
  }

  val satp = if (isa.hasSv32) Some(Module(new Sv32SatpRegister)) else None
  if (isa.hasSv32) {
    satp.get.io.writeEnable := ordinaryWrite && io.writeAddr === SupervisorCsrAddress.Satp.U
    satp.get.io.writeData := io.writeData(31, 0)
    io.satpTranslationEnabled := satp.get.io.translationEnabled
    io.satpRootPpn := satp.get.io.rootPpn
    io.satpAsid := satp.get.io.asid
    io.supervisorSum := mstatus(MachineCsrBit.SstatusSum)
    io.supervisorMxr := mstatus(MachineCsrBit.SstatusMxr)
  } else {
    io.satpTranslationEnabled := false.B
    io.satpRootPpn := 0.U
    io.satpAsid := 0.U
    io.supervisorSum := false.B
    io.supervisorMxr := false.B
  }

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
  val mprvActive = isa.hasU.B && privilege === PrivilegeMode.Machine.U &&
    effectiveMstatus(MachineCsrBit.MstatusMprv)
  io.effectiveDataPrivilege := Mux(
    mprvActive,
    effectiveMstatus(12, 11),
    privilege
  )
  val sieWriteValue =
    (mie & (~supervisorInterruptMask & allBits).U(xlen.W)) |
      (canonicalWriteData & supervisorInterruptMask.U(xlen.W))
  val effectiveMie = Mux(
    ordinaryWrite && io.writeAddr === MachineCsrAddress.Mie.U,
    canonicalWriteData,
    Mux(
      ordinaryWrite && io.writeAddr === SupervisorCsrAddress.Sie.U,
      sieWriteValue,
      mie
    )
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
  val rawSupervisorExternalInterrupt =
    if (withSupervisorExternalInterrupt) io.supervisorExternalInterruptPending.get else false.B
  val sstcPending = if (isa.hasSstc) sstc.get.io.pending else false.B
  val mipValue =
    Mux(sstcPending, supervisorTimerMask.U(xlen.W), 0.U(xlen.W)) |
      Mux(rawSupervisorExternalInterrupt, supervisorExternalMask.U(xlen.W), 0.U(xlen.W)) |
      Mux(io.timerInterrupt, machineTimerMask.U(xlen.W), 0.U(xlen.W)) |
      Mux(rawExternalInterrupt, machineExternalMask.U(xlen.W), 0.U(xlen.W))
  val machineInterruptGloballyEnabled =
    privilege < PrivilegeMode.Machine.U || effectiveMstatus(MachineCsrBit.MstatusMie)
  val supervisorInterruptGloballyEnabled =
    privilege < PrivilegeMode.Supervisor.U ||
      (privilege === PrivilegeMode.Supervisor.U && effectiveMstatus(MachineCsrBit.SstatusSie))

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
  if (isa.hasSstc) {
    io.supervisorTimerPending.get := sstcPending
    io.supervisorTimerInterrupt.get :=
      sstcPending && mideleg(MachineCsrBit.SupervisorTimerInterrupt) &&
        effectiveMie(MachineCsrBit.SupervisorTimerInterrupt) &&
        supervisorInterruptGloballyEnabled && privilege =/= PrivilegeMode.Machine.U
  }
  if (withMachineExternalInterrupt) {
    io.machineExternalInterrupt.get :=
      rawExternalInterrupt && effectiveMie(MachineCsrBit.MachineExternalInterrupt) &&
        machineInterruptGloballyEnabled
  }
  if (withSupervisorExternalInterrupt) {
    io.supervisorExternalInterrupt.get :=
      rawSupervisorExternalInterrupt && mideleg(MachineCsrBit.SupervisorExternalInterrupt) &&
        effectiveMie(MachineCsrBit.SupervisorExternalInterrupt) &&
        supervisorInterruptGloballyEnabled && privilege =/= PrivilegeMode.Machine.U
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
      io.readWritable := true.B
    }
    is(MachineCsrAddress.Mvendorid.U) {
      io.readData := 0.U
      io.readImplemented := true.B
    }
    is(MachineCsrAddress.Marchid.U) {
      io.readData := 0.U
      io.readImplemented := true.B
    }
    is(MachineCsrAddress.Mimpid.U) {
      io.readData := 0.U
      io.readImplemented := true.B
    }
    is(MachineCsrAddress.Mhartid.U) {
      io.readData := 0.U
      io.readImplemented := true.B
    }
  }

  if (xlen == 32) {
    when(io.readAddr === MachineCsrAddress.Mstatush.U) {
      // AetherCore is little-endian only and currently implements none of the
      // other RV32 mstatush fields, so every writable field has the WARL set {0}.
      io.readData := 0.U
      io.readImplemented := true.B
      io.readWritable := true.B
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
        io.readData := (mstatus & supervisorStatusMask.U(xlen.W)) | sstatusXlenValue.U(xlen.W)
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
      is(SupervisorCsrAddress.Scounteren.U) {
        io.readData := scounteren
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
    }
  }

  if (isa.hasSstc) {
    switch(io.readAddr) {
      is(MachineCsrAddress.Mcounteren.U) {
        io.readData := mcounteren
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(MachineCsrAddress.Mcountinhibit.U) {
        // This core currently implements no mcycle/minstret/HPM state to
        // inhibit, so all implemented bits have the legal WARL value zero.
        // Keep writes legal so OpenSBI can probe Priv v1.11 before it probes
        // menvcfg/Priv v1.12 and enables Sstc STCE.
        io.readData := 0.U
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(MachineCsrAddress.Menvcfg.U) {
        io.readData := 0.U
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(MachineCsrAddress.Menvcfgh.U) {
        io.readData := menvcfgh
        io.readImplemented := true.B
        io.readWritable := true.B
      }
      is(SupervisorCsrAddress.Stimecmp.U) {
        when(stimecmpAccessAllowed) {
          io.readData := sstc.get.io.readLow
          io.readImplemented := true.B
          io.readWritable := true.B
        }
      }
      is(SupervisorCsrAddress.Stimecmph.U) {
        when(stimecmpAccessAllowed) {
          io.readData := sstc.get.io.readHigh
          io.readImplemented := true.B
          io.readWritable := true.B
        }
      }
      is(Rv32SstcCsrAddress.Time.U) {
        when(timeAccessAllowed) {
          io.readData := io.time.get(31, 0)
          io.readImplemented := true.B
        }
      }
      is(Rv32SstcCsrAddress.Timeh.U) {
        when(timeAccessAllowed) {
          io.readData := io.time.get(63, 32)
          io.readImplemented := true.B
        }
      }
    }
  }

  if (isa.hasSv32) {
    when(io.readAddr === SupervisorCsrAddress.Satp.U) {
      io.readData := satp.get.io.readData
      io.readImplemented := true.B
      io.readWritable := true.B
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
  val machineReturnBase = Mux(
    machineReturnPrivilege === PrivilegeMode.Machine.U,
    mstatus,
    mstatus & mprvClearMask.U(xlen.W)
  )
  val machineReturnMstatus =
    (machineReturnBase & mstatusTransitionPreserveMask.U(xlen.W)) |
      (mstatus(MachineCsrBit.MstatusMpie).asUInt << MachineCsrBit.MstatusMie) |
      mstatusMpie.U(xlen.W) |
      (leastPrivilege << MachineCsrBit.MstatusMppLow).U(xlen.W)

  val supervisorReturnPrivilege =
    if (isa.hasU) Mux(mstatus(MachineCsrBit.SstatusSpp), PrivilegeMode.Supervisor.U, PrivilegeMode.User.U)
    else PrivilegeMode.Supervisor.U(2.W)
  val supervisorReturnBase = mstatus & mprvClearMask.U(xlen.W)
  val supervisorReturnMstatus =
    (supervisorReturnBase & sstatusTransitionPreserveMask.U(xlen.W)) |
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
      is(MachineCsrAddress.Mstatush.U) {
        // RV32 mstatush fields are implemented as WARL-zero in this profile.
      }
      is(MachineCsrAddress.Mie.U) { mie := canonicalWriteData }
      is(MachineCsrAddress.Mcounteren.U) { mcounteren := canonicalWriteData }
      is(MachineCsrAddress.Mcountinhibit.U) {
        // No implemented cycle/instret/HPM counter can currently be inhibited.
        // Accept the write and retain the WARL-zero value.
      }
      is(MachineCsrAddress.Menvcfg.U) {
        // The bounded RV32 profile currently implements only menvcfgh.STCE.
      }
      is(MachineCsrAddress.Menvcfgh.U) { menvcfgh := canonicalWriteData }
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
        is(SupervisorCsrAddress.Sie.U) { mie := sieWriteValue }
        is(SupervisorCsrAddress.Scounteren.U) { scounteren := canonicalWriteData }
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
