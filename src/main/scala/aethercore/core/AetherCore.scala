package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common._

class IfId extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val fault = Bool()
}

class IdEx extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val rs1Data = UInt(64.W)
  val rs2Data = UInt(64.W)
  val imm = UInt(64.W)
  val ctrl = new ControlSignals
  val exception = Bool()
}

class ExMem extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val rd = UInt(5.W)
  val result = UInt(64.W)
  val storeData = UInt(64.W)
  val ctrl = new ControlSignals
  val exception = Bool()
}

class MemWb extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val rd = UInt(5.W)
  val rdData = UInt(64.W)
  val regWrite = Bool()
  val memValid = Bool()
  val memWrite = Bool()
  val memAddr = UInt(64.W)
  val memWdata = UInt(64.W)
  val memWmask = UInt(8.W)
  val exception = Bool()
}

class AetherCore(resetVector: BigInt = BigInt("80000000", 16)) extends Module {
  val io = IO(new Bundle {
    val imem = new InstructionBusIO
    val dmem = new DataBusIO
    val commit = Output(new CommitTrace)
    val halted = Output(Bool())
  })

  val pc = RegInit(resetVector.U(64.W))
  val ifId = RegInit(0.U.asTypeOf(new IfId))
  val idEx = RegInit(0.U.asTypeOf(new IdEx))
  val exMem = RegInit(0.U.asTypeOf(new ExMem))
  val memWb = RegInit(0.U.asTypeOf(new MemWb))
  val haltedReg = RegInit(false.B)

  val decoder = Module(new Decoder)
  val registerFile = Module(new RegisterFile)
  val alu = Module(new ALU)

  io.imem.addr := pc
  decoder.io.inst := ifId.inst

  registerFile.io.rs1Addr := decoder.io.rs1
  registerFile.io.rs2Addr := decoder.io.rs2
  registerFile.io.writeEnable := memWb.valid && memWb.regWrite && !memWb.exception
  registerFile.io.rdAddr := memWb.rd
  registerFile.io.rdData := memWb.rdData

  val decodedImm = WireDefault(0.U(64.W))
  switch(decoder.io.ctrl.immSel) {
    is(ImmSel.I) { decodedImm := Immediate.i(ifId.inst) }
    is(ImmSel.S) { decodedImm := Immediate.s(ifId.inst) }
    is(ImmSel.B) { decodedImm := Immediate.b(ifId.inst) }
    is(ImmSel.U) { decodedImm := Immediate.u(ifId.inst) }
    is(ImmSel.J) { decodedImm := Immediate.j(ifId.inst) }
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
  val jalrTarget = (forwardedRs1 + idEx.imm) & "hfffffffffffffffe".U
  val redirectTarget = Mux(idEx.ctrl.jalr, jalrTarget, branchTarget)

  val exResult = Mux(idEx.ctrl.wbSel === WbSel.PcPlus4, idEx.pc + 4.U, alu.io.out)

  val storeMask = WireDefault("hff".U(8.W))
  switch(exMem.ctrl.memSize) {
    is(MemSize.Byte)  { storeMask := "h01".U }
    is(MemSize.Half)  { storeMask := "h03".U }
    is(MemSize.Word)  { storeMask := "h0f".U }
    is(MemSize.DWord) { storeMask := "hff".U }
  }

  io.dmem.valid := exMem.valid && (exMem.ctrl.memRead || exMem.ctrl.memWrite) && !exMem.exception
  io.dmem.write := exMem.ctrl.memWrite
  io.dmem.addr := exMem.result
  io.dmem.wdata := exMem.storeData
  io.dmem.wmask := storeMask
  io.dmem.size := exMem.ctrl.memSize

  val loadData = WireDefault(io.dmem.rdata)
  switch(exMem.ctrl.memSize) {
    is(MemSize.Byte) {
      loadData := Mux(exMem.ctrl.memUnsigned, Cat(0.U(56.W), io.dmem.rdata(7, 0)), Cat(Fill(56, io.dmem.rdata(7)), io.dmem.rdata(7, 0)))
    }
    is(MemSize.Half) {
      loadData := Mux(exMem.ctrl.memUnsigned, Cat(0.U(48.W), io.dmem.rdata(15, 0)), Cat(Fill(48, io.dmem.rdata(15)), io.dmem.rdata(15, 0)))
    }
    is(MemSize.Word) {
      loadData := Mux(exMem.ctrl.memUnsigned, Cat(0.U(32.W), io.dmem.rdata(31, 0)), Cat(Fill(32, io.dmem.rdata(31)), io.dmem.rdata(31, 0)))
    }
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
      memWb.exception := exMem.exception || (io.dmem.valid && io.dmem.fault)

      exMem.valid := idEx.valid
      exMem.pc := idEx.pc
      exMem.inst := idEx.inst
      exMem.rd := idEx.rd
      exMem.result := exResult
      exMem.storeData := forwardedRs2
      exMem.ctrl := idEx.ctrl
      exMem.exception := idEx.exception

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
