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
  val exception = Bool()
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
  val exception = Bool()
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
  val exception = Bool()
}

class AetherCore(val config: CoreConfig = CoreProfiles.rv64imCurrent) extends Module {
  private val xlen = config.isa.xlen
  private val paddrBits = config.platform.paddrBits
  private val busDataBits = config.platform.busDataBits
  private val busBytes = config.platform.busBytes

  require(paddrBits == xlen, "the current core requires physical and architectural address widths to match")
  require(busDataBits == xlen, "the current load/store path requires bus width to match XLEN")

  val io = IO(new Bundle {
    val imem = new InstructionBusIO(paddrBits)
    val dmem = new DataBusIO(paddrBits, busDataBits)
    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  val pc = RegInit(config.platform.resetVector.U(xlen.W))
  val ifId = RegInit(0.U.asTypeOf(new IfId(xlen)))
  val idEx = RegInit(0.U.asTypeOf(new IdEx(xlen)))
  val exMem = RegInit(0.U.asTypeOf(new ExMem(xlen)))
  val memWb = RegInit(0.U.asTypeOf(new MemWb(xlen, paddrBits, busDataBits)))
  val haltedReg = RegInit(false.B)

  val decoder = Module(new Decoder(config.isa))
  val registerFile = Module(new RegisterFile(xlen))
  val alu = Module(new ALU(xlen))
  val csrFile = Module(new MachineCsrFile(config.isa))

  io.imem.addr := pc
  decoder.io.inst := ifId.inst

  registerFile.io.rs1Addr := decoder.io.rs1
  registerFile.io.rs2Addr := decoder.io.rs2
  registerFile.io.writeEnable := memWb.valid && memWb.regWrite && !memWb.exception
  registerFile.io.rdAddr := memWb.rd
  registerFile.io.rdData := memWb.rdData

  csrFile.io.writeEnable := memWb.valid && memWb.csrWrite && !memWb.exception
  csrFile.io.writeAddr := memWb.csrAddr
  csrFile.io.writeData := memWb.csrData

  val decodedImm = WireDefault(0.U(xlen.W))
  switch(decoder.io.ctrl.immSel) {
    is(ImmSel.I) { decodedImm := Immediate.i(ifId.inst, xlen) }
    is(ImmSel.S) { decodedImm := Immediate.s(ifId.inst, xlen) }
    is(ImmSel.B) { decodedImm := Immediate.b(ifId.inst, xlen) }
    is(ImmSel.U) { decodedImm := Immediate.u(ifId.inst, xlen) }
    is(ImmSel.J) { decodedImm := Immediate.j(ifId.inst, xlen) }
  }

  val exMemForward = exMem.valid && exMem.ctrl.regWrite && !exMem.ctrl.memRead && exMem.rd =/= 0.U
  val memWbForward = memWb.valid && memWb.regWrite && !memWb.exception && memWb.rd =/= 0.U

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
  val redirect = branchTaken || jumpTaken
  val branchTarget = idEx.pc + idEx.imm
  val jalrAlignmentMask = ((BigInt(1) << xlen) - 2).U(xlen.W)
  val jalrTarget = (forwardedRs1 + idEx.imm) & jalrAlignmentMask
  val redirectTarget = Mux(idEx.ctrl.jalr, jalrTarget, branchTarget)

  val csrInstruction = idEx.ctrl.csrOp =/= CsrOp.None
  val csrAddr = idEx.inst(31, 20)
  csrFile.io.readAddr := csrAddr

  val csrReadData = Mux(
    exMem.valid && exMem.csrWrite && !exMem.exception && exMem.csrAddr === csrAddr,
    exMem.csrData,
    Mux(
      memWb.valid && memWb.csrWrite && !memWb.exception && memWb.csrAddr === csrAddr,
      memWb.csrData,
      csrFile.io.readData
    )
  )
  val csrImmediate = Cat(0.U((xlen - 5).W), idEx.rs1)
  val csrOperand = Mux(idEx.ctrl.csrUseImm, csrImmediate, forwardedRs1)
  val csrSourceFieldNonZero = idEx.rs1 =/= 0.U
  val csrWriteIntent = idEx.ctrl.csrOp === CsrOp.Write ||
    ((idEx.ctrl.csrOp === CsrOp.Set || idEx.ctrl.csrOp === CsrOp.Clear) && csrSourceFieldNonZero)
  val csrLegal = csrFile.io.readImplemented && (!csrWriteIntent || csrFile.io.readWritable)
  val csrWriteData = WireDefault(csrOperand)
  switch(idEx.ctrl.csrOp) {
    is(CsrOp.Set) { csrWriteData := csrReadData | csrOperand }
    is(CsrOp.Clear) { csrWriteData := csrReadData & ~csrOperand }
  }
  val canonicalCsrWriteData = MachineCsrWarl.canonicalize(config.isa, csrAddr, csrWriteData)
  val csrException = csrInstruction && !csrLegal

  val ordinaryExResult = Mux(idEx.ctrl.wbSel === WbSel.PcPlus4, idEx.pc + 4.U, alu.io.out)
  val exResult = Mux(idEx.ctrl.wbSel === WbSel.Csr, csrReadData, ordinaryExResult)

  val fullStoreMask = ((BigInt(1) << busBytes) - 1).U(busBytes.W)
  val storeMask = WireDefault(fullStoreMask)
  switch(exMem.ctrl.memSize) {
    is(MemSize.Byte)  { storeMask := 1.U(busBytes.W) }
    is(MemSize.Half)  { storeMask := 3.U(busBytes.W) }
    is(MemSize.Word)  { storeMask := 15.U(busBytes.W) }
    is(MemSize.DWord) { storeMask := fullStoreMask }
  }

  // A faulting instruction retires from WB while a younger instruction may
  // already occupy MEM. Suppress that younger request combinationally so no
  // store or MMIO side effect can escape in the exception-retirement cycle.
  val retiringException = memWb.valid && memWb.exception
  io.dmem.valid := exMem.valid && (exMem.ctrl.memRead || exMem.ctrl.memWrite) &&
    !exMem.exception && !retiringException
  io.dmem.write := exMem.ctrl.memWrite
  io.dmem.addr := exMem.result
  io.dmem.wdata := exMem.storeData
  io.dmem.wmask := storeMask
  io.dmem.size := exMem.ctrl.memSize

  def extendLoad(bits: Int): UInt = {
    require(bits <= xlen, s"cannot extend a $bits-bit load into XLEN=$xlen")
    val value = io.dmem.rdata(bits - 1, 0)
    if (bits == xlen) {
      value
    } else {
      Mux(
        exMem.ctrl.memUnsigned,
        Cat(0.U((xlen - bits).W), value),
        Cat(Fill(xlen - bits, value(bits - 1)), value)
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

  val memoryStall = io.dmem.valid && !io.dmem.ready
  val loadUseHazard = idEx.valid && idEx.ctrl.memRead && idEx.rd =/= 0.U && ifId.valid && (
    (decoder.io.ctrl.usesRs1 && decoder.io.rs1 === idEx.rd) ||
      (decoder.io.ctrl.usesRs2 && decoder.io.rs2 === idEx.rd)
  )

  io.commit.valid := memWb.valid
  io.commit.pc := memWb.pc
  io.commit.inst := memWb.inst
  io.commit.rd := memWb.rd
  io.commit.rdWrite := memWb.regWrite && !memWb.exception && memWb.rd =/= 0.U
  io.commit.rdData := memWb.rdData
  io.commit.memValid := memWb.memValid
  io.commit.memWrite := memWb.memWrite
  io.commit.memAddr := memWb.memAddr
  io.commit.memWdata := memWb.memWdata
  io.commit.memWmask := memWb.memWmask
  io.commit.exception := memWb.exception
  io.halted := haltedReg

  when(memWb.valid && memWb.exception) {
    haltedReg := true.B
  }

  when(!haltedReg) {
    when(memoryStall) {
      memWb.valid := false.B

      // MEM backpressure freezes ID/EX while the current WB result retires and
      // its forwarding source disappears. Persist the values already selected
      // by the forwarding network so the frozen EX instruction cannot fall
      // back to stale register-file snapshots on the following cycle.
      idEx.rs1Data := forwardedRs1
      idEx.rs2Data := forwardedRs2
    }.otherwise {
      memWb.valid := exMem.valid
      memWb.pc := exMem.pc
      memWb.inst := exMem.inst
      memWb.rd := exMem.rd
      memWb.rdData := Mux(exMem.ctrl.memRead, loadData, exMem.result)
      memWb.regWrite := exMem.ctrl.regWrite
      memWb.memValid := exMem.valid && (exMem.ctrl.memRead || exMem.ctrl.memWrite)
      memWb.memWrite := exMem.ctrl.memWrite
      memWb.memAddr := exMem.result
      memWb.memWdata := exMem.storeData
      memWb.memWmask := storeMask
      memWb.csrWrite := exMem.csrWrite
      memWb.csrAddr := exMem.csrAddr
      memWb.csrData := exMem.csrData
      memWb.exception := exMem.exception || (io.dmem.valid && io.dmem.fault)

      exMem.valid := idEx.valid
      exMem.pc := idEx.pc
      exMem.inst := idEx.inst
      exMem.rd := idEx.rd
      exMem.result := exResult
      exMem.storeData := forwardedRs2
      exMem.ctrl := idEx.ctrl
      exMem.csrWrite := idEx.valid && csrInstruction && csrWriteIntent && csrLegal && !idEx.exception
      exMem.csrAddr := csrAddr
      exMem.csrData := canonicalCsrWriteData
      exMem.exception := idEx.exception || csrException

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
        idEx.exception := ifId.fault || decoder.io.ctrl.illegal || decoder.io.ctrl.trap

        ifId.valid := true.B
        ifId.pc := pc
        ifId.inst := io.imem.inst
        ifId.fault := io.imem.fault
        pc := pc + 4.U
      }
    }
  }
}
