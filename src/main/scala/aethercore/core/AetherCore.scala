package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common._
import aethercore.config.{CoreConfig, CoreProfiles}

class IfId(val xlen: Int = 64) extends Bundle {
  val valid = Bool()
  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val fault = Bool()
  val pageFault = Bool()
}

class IdEx(val xlen: Int = 64) extends Bundle {
  val valid = Bool()
  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val rs1Data = UInt(xlen.W)
  val rs2Data = UInt(xlen.W)
  val imm = UInt(xlen.W)
  val ctrl = new ControlSignals
  val trap = new TrapInfo(xlen)
}

class ExMem(val xlen: Int = 64) extends Bundle {
  val valid = Bool()
  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val rd = UInt(5.W)
  val result = UInt(xlen.W)
  val storeData = UInt(xlen.W)
  val ctrl = new ControlSignals
  val csrWrite = Bool()
  val csrAddr = UInt(12.W)
  val csrData = UInt(xlen.W)
  val trap = new TrapInfo(xlen)
}

class MemWb(
    val xlen: Int = 64,
    val paddrBits: Int = 64,
    val busDataBits: Int = 64
) extends Bundle {
  val valid = Bool()
  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val rd = UInt(5.W)
  val rdData = UInt(xlen.W)
  val regWrite = Bool()
  val memValid = Bool()
  val memWrite = Bool()
  val memAddr = UInt(paddrBits.W)
  val memWdata = UInt(busDataBits.W)
  val memWmask = UInt((busDataBits / 8).W)
  val csrWrite = Bool()
  val csrAddr = UInt(12.W)
  val csrData = UInt(xlen.W)
  val wfi = Bool()
  val xret = XRetOp()
  val trap = new TrapInfo(xlen)
}

class AetherCore(
    val config: CoreConfig = CoreProfiles.rv64imCurrent,
    val withMachineExternalInterrupt: Boolean = false,
    val withSupervisorExternalInterrupt: Boolean = false
) extends Module {
  private val xlen = config.isa.xlen
  private val paddrBits = config.platform.paddrBits
  private val busDataBits = config.platform.busDataBits
  private val busBytes = config.platform.busBytes
  private val supervisorTimerCause =
    (BigInt(1) << (xlen - 1)) | BigInt(MachineCsrBit.SupervisorTimerInterrupt)
  private val machineTimerCause =
    (BigInt(1) << (xlen - 1)) | BigInt(MachineInterruptCode.MachineTimer)
  private val supervisorExternalCause =
    (BigInt(1) << (xlen - 1)) | BigInt(MachineCsrBit.SupervisorExternalInterrupt)
  private val machineExternalCause =
    (BigInt(1) << (xlen - 1)) | BigInt(MachineInterruptCode.MachineExternal)

  require(paddrBits >= xlen, "physical address width must cover the architectural address width")
  require(busDataBits == xlen, "the current load/store path requires bus width to match XLEN")
  require(!config.isa.hasA || xlen == 32, "the current atomic execution path implements RV32A word operations only")
  require(!config.isa.hasSv32 || paddrBits >= 34, "Sv32 requires a PA width of at least 34 bits")
  require(!withSupervisorExternalInterrupt || config.isa.hasS,
    "supervisor external interrupt requires an S-mode profile")

  val io = IO(new Bundle {
    val imem = new InstructionBusIO(paddrBits)
    val dmem = new DataBusIO(paddrBits, busDataBits)
    val ptw = if (config.isa.hasSv32) Some(new PageTableReadBusIO(paddrBits)) else None
    val timerInterrupt = Input(Bool())
    val time = if (config.isa.hasSstc) Some(Input(UInt(64.W))) else None
    val externalInterrupt = if (withMachineExternalInterrupt) Some(Input(Bool())) else None
    val supervisorExternalInterrupt =
      if (withSupervisorExternalInterrupt) Some(Input(Bool())) else None
    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  val pc = RegInit(config.platform.resetVector.U(xlen.W))
  val ifId = RegInit(0.U.asTypeOf(new IfId(xlen)))
  val idEx = RegInit(0.U.asTypeOf(new IdEx(xlen)))
  val exMem = RegInit(0.U.asTypeOf(new ExMem(xlen)))
  val memWb = RegInit(0.U.asTypeOf(new MemWb(xlen, paddrBits, busDataBits)))

  val reservationValid = RegInit(false.B)
  val reservationAddress = RegInit(0.U(paddrBits.W))
  val atomicWritePhase = RegInit(false.B)
  val atomicOldData = RegInit(0.U(xlen.W))

  val decoder = Module(new Decoder(config.isa))
  val registerFile = Module(new RegisterFile(xlen))
  val alu = Module(new ALU(xlen))
  val csrFile = Module(new MachineCsrFile(
    config.isa,
    withMachineExternalInterrupt,
    withSupervisorExternalInterrupt
  ))
  val instructionPmp = Module(new PmpChecker(xlen))
  val dataPmp = Module(new PmpChecker(xlen))
  val dataVm = if (config.isa.hasSv32) Some(Module(new Sv32DataPathAdapter(paddrBits))) else None
  val fetchVm = if (config.isa.hasSv32) Some(Module(new Sv32InstructionFetchAdapter(paddrBits))) else None
  val ptwArbiter = if (config.isa.hasSv32) Some(Module(new Sv32PtwArbiter(paddrBits))) else None

  val ifIdSfenceVma = if (config.isa.hasSv32) Sv32SystemInstruction.isSfenceVma(ifId.inst) else false.B
  val idExSfenceVma = if (config.isa.hasSv32) Sv32SystemInstruction.isSfenceVma(idEx.inst) else false.B
  val memWbSfenceVma = if (config.isa.hasSv32) Sv32SystemInstruction.isSfenceVma(memWb.inst) else false.B

  val takingTrap = memWb.valid && memWb.trap.valid
  val takingXret = memWb.valid && memWb.xret =/= XRetOp.None && !memWb.trap.valid
  val takingSfence = memWb.valid && memWbSfenceVma && !memWb.trap.valid

  instructionPmp.io.privilege := csrFile.io.currentPrivilege
  instructionPmp.io.address := pc
  instructionPmp.io.bytes := 4.U
  instructionPmp.io.write := false.B
  instructionPmp.io.execute := true.B
  instructionPmp.io.config := csrFile.io.pmpConfig
  instructionPmp.io.pmpAddress := csrFile.io.pmpAddress

  dataPmp.io.privilege := csrFile.io.currentPrivilege
  dataPmp.io.config := csrFile.io.pmpConfig
  dataPmp.io.pmpAddress := csrFile.io.pmpAddress

  val fetchKill = WireDefault(false.B)
  val fetchResponseReady = WireDefault(false.B)
  val fetchResponseValid = WireDefault(true.B)
  val fetchPhysicalAddress = WireDefault(pc.pad(paddrBits))
  val fetchPageFault = WireDefault(false.B)
  val fetchAccessFault = WireDefault(false.B)

  val dataPteValid = WireDefault(false.B)
  val dataPteAddress = WireDefault(0.U(paddrBits.W))
  val dataPteReady = WireDefault(false.B)
  val dataPteRdata = WireDefault(0.U(32.W))
  val dataPteFault = WireDefault(false.B)

  if (config.isa.hasSv32) {
    val fetch = fetchVm.get
    fetch.io.requestValid := !fetchKill
    fetch.io.kill := fetchKill
    fetch.io.flush := takingSfence
    fetch.io.virtualAddress := pc(31, 0)
    fetch.io.privilege := csrFile.io.currentPrivilege
    fetch.io.satpTranslationEnabled := csrFile.io.satpTranslationEnabled
    fetch.io.satpRootPpn := csrFile.io.satpRootPpn
    fetch.io.mxr := csrFile.io.supervisorMxr
    fetch.io.responseReady := fetchResponseReady

    val arbiter = ptwArbiter.get
    arbiter.io.dataValid := dataPteValid
    arbiter.io.dataAddress := dataPteAddress
    dataPteReady := arbiter.io.dataReady
    dataPteRdata := arbiter.io.dataRdata
    dataPteFault := arbiter.io.dataFault

    arbiter.io.fetchValid := fetch.io.pteValid
    arbiter.io.fetchAddress := fetch.io.pteAddress
    fetch.io.pteReady := arbiter.io.fetchReady
    fetch.io.pteData := arbiter.io.fetchRdata
    fetch.io.pteFault := arbiter.io.fetchFault

    io.ptw.get.valid := arbiter.io.memoryValid
    io.ptw.get.addr := arbiter.io.memoryAddress
    arbiter.io.memoryReady := io.ptw.get.ready
    arbiter.io.memoryRdata := io.ptw.get.rdata
    arbiter.io.memoryFault := io.ptw.get.fault

    fetchResponseValid := fetch.io.responseValid
    fetchPhysicalAddress := fetch.io.physicalAddress
    fetchPageFault := fetch.io.pageFault
    fetchAccessFault := fetch.io.accessFault
  }

  io.imem.addr := fetchPhysicalAddress
  decoder.io.inst := ifId.inst

  registerFile.io.rs1Addr := decoder.io.rs1
  registerFile.io.rs2Addr := decoder.io.rs2
  registerFile.io.writeEnable := memWb.valid && memWb.regWrite && !memWb.trap.valid
  registerFile.io.rdAddr := memWb.rd
  registerFile.io.rdData := memWb.rdData

  csrFile.io.writeEnable := memWb.valid && memWb.csrWrite && !memWb.trap.valid
  csrFile.io.writeAddr := memWb.csrAddr
  csrFile.io.writeData := memWb.csrData
  csrFile.io.timerInterrupt := io.timerInterrupt
  if (config.isa.hasSstc) {
    csrFile.io.time.get := io.time.get
  }
  val rawExternalInterrupt =
    if (withMachineExternalInterrupt) io.externalInterrupt.get else false.B
  if (withMachineExternalInterrupt) {
    csrFile.io.externalInterrupt.get := rawExternalInterrupt
  }
  val rawSupervisorExternalInterrupt =
    if (withSupervisorExternalInterrupt) io.supervisorExternalInterrupt.get else false.B
  if (withSupervisorExternalInterrupt) {
    csrFile.io.supervisorExternalInterruptPending.get := rawSupervisorExternalInterrupt
  }
  csrFile.io.trapReturn := takingXret
  csrFile.io.trapReturnSupervisor := takingXret && memWb.xret === XRetOp.Supervisor

  val wfiRetiring = memWb.valid && memWb.wfi && !memWb.trap.valid
  val rawSupervisorTimerPending =
    if (config.isa.hasSstc) csrFile.io.supervisorTimerPending.get else false.B
  val rawInterruptPending =
    io.timerInterrupt || rawExternalInterrupt || rawSupervisorExternalInterrupt || rawSupervisorTimerPending
  val waitingForInterrupt = wfiRetiring && !rawInterruptPending

  val interruptPc = Mux(
    wfiRetiring,
    memWb.pc + 4.U,
    Mux(exMem.valid, exMem.pc, Mux(idEx.valid, idEx.pc, Mux(ifId.valid, ifId.pc, pc)))
  )
  val takingExternalInterrupt =
    if (withMachineExternalInterrupt) csrFile.io.machineExternalInterrupt.get else false.B
  val takingTimerInterrupt = csrFile.io.machineTimerInterrupt
  val takingSupervisorExternalInterrupt =
    if (withSupervisorExternalInterrupt) csrFile.io.supervisorExternalInterrupt.get else false.B
  val takingSupervisorTimerInterrupt =
    if (config.isa.hasSstc) csrFile.io.supervisorTimerInterrupt.get else false.B
  val qualifiedInterrupt =
    takingExternalInterrupt || takingTimerInterrupt ||
      takingSupervisorExternalInterrupt || takingSupervisorTimerInterrupt
  val takingInterrupt =
    memWb.valid && !memWb.trap.valid && memWb.xret === XRetOp.None && qualifiedInterrupt
  val interruptCause = Mux(
    takingExternalInterrupt,
    machineExternalCause.U(xlen.W),
    Mux(
      takingTimerInterrupt,
      machineTimerCause.U(xlen.W),
      Mux(
        takingSupervisorExternalInterrupt,
        supervisorExternalCause.U(xlen.W),
        supervisorTimerCause.U(xlen.W)
      )
    )
  )

  csrFile.io.trapEnter := takingTrap || takingInterrupt
  csrFile.io.trapPc := Mux(takingInterrupt, interruptPc, memWb.pc)
  csrFile.io.trapCause := Mux(takingInterrupt, interruptCause, memWb.trap.cause)
  csrFile.io.trapValue := Mux(takingInterrupt, 0.U, memWb.trap.value)

  val decodedImm = WireDefault(0.U(xlen.W))
  switch(decoder.io.ctrl.immSel) {
    is(ImmSel.I) { decodedImm := Immediate.i(ifId.inst, xlen) }
    is(ImmSel.S) { decodedImm := Immediate.s(ifId.inst, xlen) }
    is(ImmSel.B) { decodedImm := Immediate.b(ifId.inst, xlen) }
    is(ImmSel.U) { decodedImm := Immediate.u(ifId.inst, xlen) }
    is(ImmSel.J) { decodedImm := Immediate.j(ifId.inst, xlen) }
  }

  val instructionTrapValue =
    if (xlen == 32) ifId.inst else Cat(0.U((xlen - 32).W), ifId.inst)
  val environmentCallCause = Mux(
    csrFile.io.currentPrivilege === PrivilegeMode.User.U,
    MachineExceptionCode.EnvironmentCallFromU.U(xlen.W),
    Mux(
      csrFile.io.currentPrivilege === PrivilegeMode.Supervisor.U,
      MachineExceptionCode.EnvironmentCallFromS.U(xlen.W),
      MachineExceptionCode.EnvironmentCallFromM.U(xlen.W)
    )
  )
  val decodedTrap = WireInit(0.U.asTypeOf(new TrapInfo(xlen)))
  when(ifId.pageFault) {
    decodedTrap.valid := true.B
    decodedTrap.cause := MachineExceptionCode.InstructionPageFault.U(xlen.W)
    decodedTrap.value := ifId.pc
  }.elsewhen(ifId.fault) {
    decodedTrap.valid := true.B
    decodedTrap.cause := MachineExceptionCode.InstructionAccessFault.U(xlen.W)
    decodedTrap.value := ifId.pc
  }.elsewhen(ifIdSfenceVma && csrFile.io.currentPrivilege < PrivilegeMode.Supervisor.U) {
    decodedTrap.valid := true.B
    decodedTrap.cause := MachineExceptionCode.IllegalInstruction.U(xlen.W)
    decodedTrap.value := instructionTrapValue
  }.elsewhen(decoder.io.ctrl.illegal && !ifIdSfenceVma) {
    decodedTrap.valid := true.B
    decodedTrap.cause := MachineExceptionCode.IllegalInstruction.U(xlen.W)
    decodedTrap.value := instructionTrapValue
  }.elsewhen(decoder.io.ctrl.trap) {
    decodedTrap.valid := true.B
    when(ifId.inst === "h00100073".U) {
      decodedTrap.cause := MachineExceptionCode.Breakpoint.U(xlen.W)
      decodedTrap.value := ifId.pc
    }.otherwise {
      decodedTrap.cause := environmentCallCause
      decodedTrap.value := 0.U
    }
  }

  val exMemForward = exMem.valid && exMem.ctrl.regWrite && !exMem.ctrl.memRead &&
    exMem.ctrl.atomicOp === AtomicOp.None && !exMem.trap.valid && exMem.rd =/= 0.U
  val memWbForward = memWb.valid && memWb.regWrite && !memWb.trap.valid && memWb.rd =/= 0.U

  val forwardedRs1 = Mux(
    exMemForward && exMem.rd === idEx.rs1,
    exMem.result,
    Mux(memWbForward && memWb.rd === idEx.rs1, memWb.rdData, idEx.rs1Data)
  )
  val forwardedRs2 = Mux(
    exMemForward && exMem.rd === idEx.rs2,
    exMem.result,
    Mux(memWbForward && memWb.rd === idEx.rs2, memWb.rdData, idEx.rs2Data)
  )

  val aluA = WireDefault(forwardedRs1)
  switch(idEx.ctrl.opASel) {
    is(OpASel.Pc) { aluA := idEx.pc }
    is(OpASel.Zero) { aluA := 0.U }
  }
  val aluB = Mux(idEx.ctrl.opBSel === OpBSel.Imm, idEx.imm, forwardedRs2)

  alu.io.a := aluA
  alu.io.b := aluB
  alu.io.op := idEx.ctrl.aluOp
  alu.io.wordOp := idEx.ctrl.wordOp

  val branchCondition = WireDefault(false.B)
  switch(idEx.ctrl.branch) {
    is(BranchType.Eq)  { branchCondition := forwardedRs1 === forwardedRs2 }
    is(BranchType.Ne)  { branchCondition := forwardedRs1 =/= forwardedRs2 }
    is(BranchType.Lt)  { branchCondition := forwardedRs1.asSInt < forwardedRs2.asSInt }
    is(BranchType.Ge)  { branchCondition := forwardedRs1.asSInt >= forwardedRs2.asSInt }
    is(BranchType.Ltu) { branchCondition := forwardedRs1 < forwardedRs2 }
    is(BranchType.Geu) { branchCondition := forwardedRs1 >= forwardedRs2 }
  }

  val branchTaken = idEx.valid && idEx.ctrl.branch =/= BranchType.None && branchCondition
  val jumpTaken = idEx.valid && idEx.ctrl.jump
  val controlTransferTaken = branchTaken || jumpTaken
  val branchTarget = idEx.pc + idEx.imm
  val jalrAlignmentMask = ((BigInt(1) << xlen) - 2).U(xlen.W)
  val jalrTarget = (forwardedRs1 + idEx.imm) & jalrAlignmentMask
  val redirectTarget = Mux(idEx.ctrl.jalr, jalrTarget, branchTarget)
  val instructionAlignmentMask = (if (config.isa.hasC) BigInt(1) else BigInt(3)).U(xlen.W)
  val controlTransferMisaligned =
    controlTransferTaken && ((redirectTarget & instructionAlignmentMask) =/= 0.U)
  val redirect = controlTransferTaken && !controlTransferMisaligned

  val csrInstruction = idEx.ctrl.csrOp =/= CsrOp.None
  val csrAddr = idEx.inst(31, 20)
  csrFile.io.readAddr := csrAddr

  val csrReadData = Mux(
    exMem.valid && exMem.csrWrite && !exMem.trap.valid && exMem.csrAddr === csrAddr,
    exMem.csrData,
    Mux(
      memWb.valid && memWb.csrWrite && !memWb.trap.valid && memWb.csrAddr === csrAddr,
      memWb.csrData,
      csrFile.io.readData
    )
  )
  val csrImmediate = Cat(0.U((xlen - 5).W), idEx.rs1)
  val csrOperand = Mux(idEx.ctrl.csrUseImm, csrImmediate, forwardedRs1)
  val csrSourceFieldNonZero = idEx.rs1 =/= 0.U
  val csrWriteIntent = idEx.ctrl.csrOp === CsrOp.Write ||
    ((idEx.ctrl.csrOp === CsrOp.Set || idEx.ctrl.csrOp === CsrOp.Clear) && csrSourceFieldNonZero)
  val csrPrivilegeLegal = csrFile.io.currentPrivilege >= csrAddr(9, 8)
  val csrLegal = csrFile.io.readImplemented && csrPrivilegeLegal &&
    (!csrWriteIntent || csrFile.io.readWritable)
  val csrWriteData = WireDefault(csrOperand)
  switch(idEx.ctrl.csrOp) {
    is(CsrOp.Set) { csrWriteData := csrReadData | csrOperand }
    is(CsrOp.Clear) { csrWriteData := csrReadData & ~csrOperand }
  }
  val canonicalCsrWriteData = MachineCsrWarl.canonicalize(
    config.isa,
    csrAddr,
    csrWriteData,
    withSupervisorExternalInterrupt
  )
  val csrException = csrInstruction && !csrLegal
  // This core currently exposes mstatus.TW as read-only zero. WFI is therefore
  // legal in S-mode; keep U-mode trapping when Supervisor mode is implemented.
  val wfiException =
    idEx.ctrl.wfi && csrFile.io.currentPrivilege === PrivilegeMode.User.U
  val machineXretException =
    idEx.ctrl.xret === XRetOp.Machine && csrFile.io.currentPrivilege =/= PrivilegeMode.Machine.U
  val supervisorXretException =
    idEx.ctrl.xret === XRetOp.Supervisor && csrFile.io.currentPrivilege < PrivilegeMode.Supervisor.U
  val xretException = machineXretException || supervisorXretException
  val sfencePrivilegeException = idExSfenceVma &&
    csrFile.io.currentPrivilege < PrivilegeMode.Supervisor.U

  val ordinaryExResult = Mux(idEx.ctrl.wbSel === WbSel.PcPlus4, idEx.pc + 4.U, alu.io.out)
  val exResult = Mux(idEx.ctrl.wbSel === WbSel.Csr, csrReadData, ordinaryExResult)
  val idExInstructionValue =
    if (xlen == 32) idEx.inst else Cat(0.U((xlen - 32).W), idEx.inst)

  val fullStoreMask = ((BigInt(1) << busBytes) - 1).U(busBytes.W)
  val storeMask = WireDefault(fullStoreMask)
  val dataAccessBytes = WireDefault(busBytes.U(4.W))
  switch(exMem.ctrl.memSize) {
    is(MemSize.Byte) {
      storeMask := 1.U(busBytes.W)
      dataAccessBytes := 1.U
    }
    is(MemSize.Half) {
      storeMask := 3.U(busBytes.W)
      dataAccessBytes := 2.U
    }
    is(MemSize.Word) {
      storeMask := 15.U(busBytes.W)
      dataAccessBytes := 4.U
    }
    is(MemSize.DWord) {
      storeMask := fullStoreMask
      dataAccessBytes := 8.U
    }
  }

  val translatedPhysicalAddress = WireDefault(exMem.result.pad(paddrBits))
  val scReservationMatch = WireDefault(reservationValid && reservationAddress === exMem.result.pad(paddrBits))

  val atomicInstruction = exMem.ctrl.atomicOp =/= AtomicOp.None
  val atomicLr = exMem.ctrl.atomicOp === AtomicOp.Lr
  val atomicSc = exMem.ctrl.atomicOp === AtomicOp.Sc
  val atomicRmw = atomicInstruction && !atomicLr && !atomicSc
  val atomicReadPhase = atomicRmw && !atomicWritePhase
  val atomicWriteRequest = atomicRmw && atomicWritePhase

  val atomicWriteData = WireDefault(exMem.storeData)
  switch(exMem.ctrl.atomicOp) {
    is(AtomicOp.Swap) { atomicWriteData := exMem.storeData }
    is(AtomicOp.Add) { atomicWriteData := atomicOldData + exMem.storeData }
    is(AtomicOp.Xor) { atomicWriteData := atomicOldData ^ exMem.storeData }
    is(AtomicOp.And) { atomicWriteData := atomicOldData & exMem.storeData }
    is(AtomicOp.Or) { atomicWriteData := atomicOldData | exMem.storeData }
    is(AtomicOp.Min) {
      atomicWriteData := Mux(atomicOldData.asSInt < exMem.storeData.asSInt, atomicOldData, exMem.storeData)
    }
    is(AtomicOp.Max) {
      atomicWriteData := Mux(atomicOldData.asSInt > exMem.storeData.asSInt, atomicOldData, exMem.storeData)
    }
    is(AtomicOp.Minu) {
      atomicWriteData := Mux(atomicOldData < exMem.storeData, atomicOldData, exMem.storeData)
    }
    is(AtomicOp.Maxu) {
      atomicWriteData := Mux(atomicOldData > exMem.storeData, atomicOldData, exMem.storeData)
    }
  }

  val ordinaryDataAccess = !atomicInstruction && (exMem.ctrl.memRead || exMem.ctrl.memWrite)
  val memoryBoundaryOpen = !exMem.trap.valid && !takingTrap && !takingInterrupt &&
    !takingXret && !takingSfence && !waitingForInterrupt
  val candidateDataAccess = exMem.valid && (ordinaryDataAccess || atomicInstruction) && memoryBoundaryOpen
  val atomicNeedsWritePermission = atomicSc || atomicRmw

  dataPmp.io.address := exMem.result
  dataPmp.io.bytes := dataAccessBytes
  dataPmp.io.write := Mux(atomicInstruction, atomicNeedsWritePermission, exMem.ctrl.memWrite)
  dataPmp.io.execute := false.B

  val dataPmpFault = candidateDataAccess &&
    (if (config.isa.hasPmp) !dataPmp.io.allow else false.B)
  val atomicScBusRequest = if (config.isa.hasSv32) atomicSc else atomicSc && scReservationMatch
  val atomicBusRequest = atomicLr || atomicReadPhase || atomicWriteRequest || atomicScBusRequest
  val rawDataRequest = candidateDataAccess && Mux(atomicInstruction, atomicBusRequest, true.B)
  val dataBusWrite = Mux(
    atomicInstruction,
    atomicWriteRequest || (atomicSc && scReservationMatch),
    exMem.ctrl.memWrite
  )

  val vmCsrHazard = if (config.isa.hasSv32) {
    memWb.valid && memWb.csrWrite && !memWb.trap.valid && (
      memWb.csrAddr === SupervisorCsrAddress.Satp.U ||
        memWb.csrAddr === SupervisorCsrAddress.Sstatus.U ||
        memWb.csrAddr === MachineCsrAddress.Mstatus.U
    )
  } else false.B

  val vmRequestComplete = WireDefault(false.B)
  val vmPageFault = WireDefault(false.B)
  val vmAccessFault = WireDefault(false.B)

  if (config.isa.hasSv32) {
    val vm = dataVm.get
    vm.io.requestValid := rawDataRequest && !dataPmpFault && !vmCsrHazard
    vm.io.flush := takingSfence
    vm.io.virtualAddress := exMem.result(31, 0)
    vm.io.privilege := csrFile.io.currentPrivilege
    vm.io.translateWrite := Mux(atomicInstruction, atomicNeedsWritePermission, exMem.ctrl.memWrite)
    vm.io.write := dataBusWrite
    vm.io.wdata := Mux(atomicWriteRequest, atomicWriteData, exMem.storeData)(31, 0)
    vm.io.wmask := storeMask(3, 0)
    vm.io.size := exMem.ctrl.memSize
    vm.io.satpTranslationEnabled := csrFile.io.satpTranslationEnabled
    vm.io.satpRootPpn := csrFile.io.satpRootPpn
    vm.io.sum := csrFile.io.supervisorSum
    vm.io.mxr := csrFile.io.supervisorMxr

    dataPteValid := vm.io.pteValid
    dataPteAddress := vm.io.pteAddress
    vm.io.pteReady := dataPteReady
    vm.io.pteData := dataPteRdata
    vm.io.pteFault := dataPteFault

    io.dmem.valid := vm.io.dataValid
    io.dmem.write := vm.io.dataWrite
    io.dmem.addr := vm.io.dataAddress
    io.dmem.wdata := vm.io.dataWdata
    io.dmem.wmask := vm.io.dataWmask
    io.dmem.size := vm.io.dataSize
    vm.io.dataReady := io.dmem.ready
    vm.io.dataRdata := io.dmem.rdata(31, 0)
    vm.io.dataFault := io.dmem.fault

    translatedPhysicalAddress := vm.io.physicalAddress
    scReservationMatch := reservationValid && reservationAddress === vm.io.physicalAddress
    vmRequestComplete := vm.io.requestComplete
    vmPageFault := vm.io.pageFault
    vmAccessFault := vm.io.accessFault
  } else {
    io.dmem.valid := rawDataRequest && !dataPmpFault
    io.dmem.write := dataBusWrite
    io.dmem.addr := exMem.result.pad(paddrBits)
    io.dmem.wdata := Mux(atomicWriteRequest, atomicWriteData, exMem.storeData)
    io.dmem.wmask := storeMask
    io.dmem.size := exMem.ctrl.memSize
  }

  def extendLoad(bits: Int): UInt = {
    require(bits <= xlen, s"cannot extend a $bits-bit load into XLEN=$xlen")
    val value = io.dmem.rdata(bits - 1, 0)
    if (bits == xlen) {
      value
    } else {
      Mux(
        exMem.ctrl.memUnsigned,
        Cat(0.U((xlen - bits).W), value),
        Cat(Fill(xlen - bits, value(bits - 1)), value(bits - 1, 0))
      )
    }
  }

  val loadData = WireDefault(io.dmem.rdata)
  switch(exMem.ctrl.memSize) {
    is(MemSize.Byte)  { loadData := extendLoad(8) }
    is(MemSize.Half)  { loadData := extendLoad(16) }
    is(MemSize.Word)  { loadData := extendLoad(32) }
    is(MemSize.DWord) { loadData := io.dmem.rdata }
  }

  val memoryStall = if (config.isa.hasSv32) {
    candidateDataAccess && (vmCsrHazard || !vmRequestComplete)
  } else {
    io.dmem.valid && !io.dmem.ready
  }
  val memoryPageFault = if (config.isa.hasSv32) vmPageFault else false.B
  val memoryFault = dataPmpFault ||
    (if (config.isa.hasSv32) vmAccessFault else io.dmem.valid && io.dmem.fault)
  val atomicReadHold = atomicReadPhase && io.dmem.valid && io.dmem.ready && !io.dmem.fault
  val lateResultHazard = idEx.ctrl.memRead || idEx.ctrl.atomicOp =/= AtomicOp.None
  val loadUseHazard = idEx.valid && lateResultHazard && idEx.rd =/= 0.U && ifId.valid && (
    (decoder.io.ctrl.usesRs1 && decoder.io.rs1 === idEx.rd) ||
      (decoder.io.ctrl.usesRs2 && decoder.io.rs2 === idEx.rd)
  )

  val atomicRdData = WireDefault(loadData)
  when(atomicSc) {
    atomicRdData := Mux(scReservationMatch, 0.U, 1.U)
  }.elsewhen(atomicRmw) {
    atomicRdData := atomicOldData
  }
  val memStageRdData = Mux(
    atomicInstruction,
    atomicRdData,
    Mux(exMem.ctrl.memRead, loadData, exMem.result)
  )
  val atomicCommittedMemory = Mux(
    atomicLr,
    io.dmem.valid && io.dmem.ready && !io.dmem.fault,
    Mux(
      atomicSc,
      scReservationMatch && io.dmem.valid && io.dmem.ready && !io.dmem.fault,
      atomicWriteRequest && io.dmem.valid && io.dmem.ready && !io.dmem.fault
    )
  )
  val ordinaryCommittedMemory = exMem.valid && ordinaryDataAccess && !exMem.trap.valid &&
    !memoryPageFault && !memoryFault
  val committedMemoryAccess = Mux(atomicInstruction, atomicCommittedMemory, ordinaryCommittedMemory)
  val committedMemoryWrite = Mux(
    atomicInstruction,
    atomicCommittedMemory && (atomicSc || atomicRmw),
    exMem.ctrl.memWrite
  )
  val committedMemoryWdata = Mux(atomicRmw, atomicWriteData, exMem.storeData)
  val memoryFaultIsLoad = atomicLr || (!atomicInstruction && exMem.ctrl.memRead)

  val fetchContextChange = vmCsrHazard
  fetchKill := takingTrap || takingInterrupt || takingXret || takingSfence || waitingForInterrupt ||
    redirect || fetchContextChange
  val frontendAdvance = !takingTrap && !takingInterrupt && !takingXret && !takingSfence &&
    !waitingForInterrupt && !memoryStall && !atomicReadHold && !redirect && !loadUseHazard
  if (config.isa.hasSv32) {
    fetchResponseReady := frontendAdvance && fetchResponseValid
  }

  io.commit.valid := memWb.valid && !waitingForInterrupt
  io.commit.pc := memWb.pc
  io.commit.inst := memWb.inst
  io.commit.rd := memWb.rd
  io.commit.rdWrite := memWb.regWrite && !memWb.trap.valid && memWb.rd =/= 0.U
  io.commit.rdData := memWb.rdData
  io.commit.memValid := memWb.memValid
  io.commit.memWrite := memWb.memWrite
  io.commit.memAddr := memWb.memAddr
  io.commit.memWdata := memWb.memWdata
  io.commit.memWmask := memWb.memWmask
  io.commit.exception := memWb.trap.valid
  io.commit.exceptionCause := memWb.trap.cause
  io.commit.exceptionValue := memWb.trap.value
  io.commit.interrupt := takingInterrupt
  io.commit.interruptCause := interruptCause
  io.commit.interruptPc := interruptPc
  io.halted := waitingForInterrupt

  when(takingTrap || takingInterrupt) {
    pc := csrFile.io.trapVector
    ifId.valid := false.B
    idEx.valid := false.B
    exMem.valid := false.B
    memWb.valid := false.B
    reservationValid := false.B
    atomicWritePhase := false.B
  }.elsewhen(takingXret) {
    pc := csrFile.io.returnPc
    ifId.valid := false.B
    idEx.valid := false.B
    exMem.valid := false.B
    memWb.valid := false.B
    reservationValid := false.B
    atomicWritePhase := false.B
  }.elsewhen(takingSfence) {
    pc := memWb.pc + 4.U
    ifId.valid := false.B
    idEx.valid := false.B
    exMem.valid := false.B
    memWb.valid := false.B
    atomicWritePhase := false.B
  }.elsewhen(waitingForInterrupt) {
    pc := memWb.pc + 4.U
    ifId.valid := false.B
    idEx.valid := false.B
    exMem.valid := false.B
    reservationValid := false.B
    atomicWritePhase := false.B
  }.elsewhen(memoryStall) {
    memWb.valid := false.B
    idEx.rs1Data := forwardedRs1
    idEx.rs2Data := forwardedRs2
  }.elsewhen(atomicReadHold) {
    memWb.valid := false.B
    atomicOldData := loadData
    atomicWritePhase := true.B
    reservationValid := false.B
    idEx.rs1Data := forwardedRs1
    idEx.rs2Data := forwardedRs2
  }.otherwise {
    atomicWritePhase := false.B

    when(exMem.valid && atomicInstruction) {
      when(atomicLr && !memoryFault && io.dmem.valid && io.dmem.ready && !io.dmem.fault) {
        reservationValid := true.B
        reservationAddress := translatedPhysicalAddress
      }.elsewhen(!atomicLr) {
        reservationValid := false.B
      }.otherwise {
        reservationValid := false.B
      }
    }.elsewhen(
      exMem.valid && !exMem.trap.valid && exMem.ctrl.memWrite &&
        io.dmem.valid && io.dmem.ready && !io.dmem.fault
    ) {
      reservationValid := false.B
    }

    memWb.valid := exMem.valid
    memWb.pc := exMem.pc
    memWb.inst := exMem.inst
    memWb.rd := exMem.rd
    memWb.rdData := memStageRdData
    memWb.regWrite := exMem.ctrl.regWrite
    memWb.memValid := committedMemoryAccess
    memWb.memWrite := committedMemoryWrite
    memWb.memAddr := translatedPhysicalAddress
    memWb.memWdata := committedMemoryWdata
    memWb.memWmask := storeMask
    memWb.csrWrite := exMem.csrWrite
    memWb.csrAddr := exMem.csrAddr
    memWb.csrData := exMem.csrData
    memWb.wfi := exMem.ctrl.wfi
    memWb.xret := exMem.ctrl.xret
    memWb.trap := exMem.trap
    when((memoryPageFault || memoryFault) && !exMem.trap.valid) {
      memWb.trap.valid := true.B
      memWb.trap.cause := Mux(
        memoryPageFault,
        Mux(
          memoryFaultIsLoad,
          MachineExceptionCode.LoadPageFault.U(xlen.W),
          MachineExceptionCode.StorePageFault.U(xlen.W)
        ),
        Mux(
          memoryFaultIsLoad,
          MachineExceptionCode.LoadAccessFault.U(xlen.W),
          MachineExceptionCode.StoreAccessFault.U(xlen.W)
        )
      )
      memWb.trap.value := exMem.result
    }

    exMem.valid := idEx.valid
    exMem.pc := idEx.pc
    exMem.inst := idEx.inst
    exMem.rd := idEx.rd
    exMem.result := exResult
    exMem.storeData := forwardedRs2
    exMem.ctrl := idEx.ctrl
    exMem.csrWrite := idEx.valid && csrInstruction && csrWriteIntent && csrLegal && !idEx.trap.valid
    exMem.csrAddr := csrAddr
    exMem.csrData := canonicalCsrWriteData
    exMem.trap := idEx.trap
    when(controlTransferMisaligned && !idEx.trap.valid) {
      exMem.trap.valid := true.B
      exMem.trap.cause := MachineExceptionCode.InstructionAddressMisaligned.U(xlen.W)
      exMem.trap.value := redirectTarget
    }.elsewhen((csrException || wfiException || xretException || sfencePrivilegeException) && !idEx.trap.valid) {
      exMem.trap.valid := true.B
      exMem.trap.cause := MachineExceptionCode.IllegalInstruction.U(xlen.W)
      exMem.trap.value := idExInstructionValue
    }

    when(redirect) {
      pc := redirectTarget
      ifId.valid := false.B
      idEx.valid := false.B
    }.elsewhen(loadUseHazard) {
      idEx.valid := false.B
    }.otherwise {
      idEx.valid := ifId.valid
      idEx.pc := ifId.pc
      idEx.inst := ifId.inst
      idEx.rs1 := decoder.io.rs1
      idEx.rs2 := decoder.io.rs2
      idEx.rd := decoder.io.rd
      idEx.rs1Data := registerFile.io.rs1Data
      idEx.rs2Data := registerFile.io.rs2Data
      idEx.imm := decodedImm
      idEx.ctrl := decoder.io.ctrl
      idEx.trap := decodedTrap

      if (config.isa.hasSv32) {
        when(fetchResponseValid) {
          ifId.valid := true.B
          ifId.pc := pc
          ifId.inst := io.imem.inst
          ifId.pageFault := fetchPageFault
          ifId.fault := fetchAccessFault || (!fetchPageFault && io.imem.fault)
          pc := pc + 4.U
        }.otherwise {
          ifId.valid := false.B
          ifId.fault := false.B
          ifId.pageFault := false.B
        }
      } else {
        ifId.valid := true.B
        ifId.pc := pc
        ifId.inst := io.imem.inst
        ifId.fault := io.imem.fault ||
          (if (config.isa.hasPmp) !instructionPmp.io.allow else false.B)
        ifId.pageFault := false.B
        pc := pc + 4.U
      }
    }
  }
}
