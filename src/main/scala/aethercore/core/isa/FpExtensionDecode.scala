package aethercore.core.isa

import aethercore.config.IsaConfig
import aethercore.core.semantic.{DecodedFloatingOperation, FloatingFormat, FloatingOperationKind}
import chisel3._
import chisel3.util._

/** Pure architectural F/D decoding. A backend may choose any execution and
  * register-file geometry; neither a physical register nor an issue port is
  * allocated here. The legacy integer Decoder intentionally remains unchanged.
  */
class FpExtensionDecodeResult extends Bundle {
  val valid = Bool()
  val floating = new DecodedFloatingOperation
  val integerUsesRs1 = Bool()
  val integerWritesRd = Bool()
  val memoryLoad = Bool()
  val memoryStore = Bool()
}

object FpExtensionDecode {
  def decode(inst: UInt, isa: IsaConfig): FpExtensionDecodeResult = {
    val d = WireInit(0.U.asTypeOf(new FpExtensionDecodeResult))
    val opcode = inst(6, 0)
    val funct3 = inst(14, 12)
    val rs2 = inst(24, 20)
    val fmt = inst(26, 25)
    val group = inst(31, 27)
    val rmValid = funct3 <= 4.U || funct3 === 7.U
    val fSupported = isa.extensions.contains('F').B
    val dSupported = (isa.extensions.contains('D') && isa.extensions.contains('F')).B
    val fmtSupported =
      (fmt === 0.U && fSupported) || (fmt === 1.U && dSupported)

    d.floating.rs1 := inst(19, 15)
    d.floating.rs2 := rs2
    d.floating.rs3 := inst(31, 27)
    d.floating.rd := inst(11, 7)
    d.floating.roundingMode := funct3
    d.floating.subop := funct3
    d.floating.format := FloatingFormat.Single
    d.floating.sourceFormat := FloatingFormat.Single
    when(fmt === 1.U) { d.floating.format := FloatingFormat.Double }
    when(fmt === 1.U) { d.floating.sourceFormat := FloatingFormat.Double }

    def fprWrite(kind: FloatingOperationKind.Type,
                 src1: Boolean, src2: Boolean = false, src3: Boolean = false): Unit = {
      d.valid := true.B
      d.floating.kind := kind
      d.floating.usesRs1 := src1.B
      d.floating.usesRs2 := src2.B
      d.floating.usesRs3 := src3.B
      d.floating.writesRd := true.B
    }
    def xprWrite(kind: FloatingOperationKind.Type): Unit = {
      d.valid := true.B
      d.floating.kind := kind
      d.floating.usesRs1 := true.B
      d.integerWritesRd := true.B
    }
    def xprRead(kind: FloatingOperationKind.Type): Unit = {
      d.valid := true.B
      d.floating.kind := kind
      d.integerUsesRs1 := true.B
      d.floating.writesRd := true.B
    }

    // FLW/FLD and FSW/FSD retain the one architectural memory-operation
    // lifetime. Their base register is an integer XPR; their destination or
    // store data belongs exclusively to the FPR namespace.
    when(opcode === "b0000111".U) {
      when((funct3 === 2.U && fSupported) || (funct3 === 3.U && dSupported)) {
        fprWrite(FloatingOperationKind.Load, src1 = false)
        d.integerUsesRs1 := true.B
        d.memoryLoad := true.B
        d.floating.format := FloatingFormat.Single
        when(funct3 === 3.U) { d.floating.format := FloatingFormat.Double }
        d.floating.sourceFormat := FloatingFormat.Single
        when(funct3 === 3.U) { d.floating.sourceFormat := FloatingFormat.Double }
      }
    }
    when(opcode === "b0100111".U) {
      when((funct3 === 2.U && fSupported) || (funct3 === 3.U && dSupported)) {
        d.valid := true.B
        d.floating.kind := FloatingOperationKind.Store
        d.floating.usesRs2 := true.B
        d.integerUsesRs1 := true.B
        d.memoryStore := true.B
        d.floating.format := FloatingFormat.Single
        when(funct3 === 3.U) { d.floating.format := FloatingFormat.Double }
        d.floating.sourceFormat := FloatingFormat.Single
        when(funct3 === 3.U) { d.floating.sourceFormat := FloatingFormat.Double }
      }
    }

    // Fused ternary operations: rs3 is bits [31:27], while [26:25] encode
    // the *destination* format. Invalid rounding and reserved fmt must trap.
    when(fmtSupported && rmValid) {
      switch(opcode) {
        is("b1000011".U) { fprWrite(FloatingOperationKind.Fma, true, true, true) }
        is("b1000111".U) { fprWrite(FloatingOperationKind.Fmsub, true, true, true) }
        is("b1001011".U) { fprWrite(FloatingOperationKind.Fnmsub, true, true, true) }
        is("b1001111".U) { fprWrite(FloatingOperationKind.Fnmadd, true, true, true) }
      }
    }

    when(opcode === "b1010011".U && fmtSupported) {
      switch(group) {
        is("b00000".U) {
          when(rmValid) { fprWrite(FloatingOperationKind.Add, true, true) }
        }
        is("b00001".U) {
          when(rmValid) { fprWrite(FloatingOperationKind.Sub, true, true) }
        }
        is("b00010".U) {
          when(rmValid) { fprWrite(FloatingOperationKind.Mul, true, true) }
        }
        is("b00011".U) {
          when(rmValid) { fprWrite(FloatingOperationKind.Div, true, true) }
        }
        is("b01011".U) {
          when(rs2 === 0.U && rmValid) { fprWrite(FloatingOperationKind.Sqrt, true) }
        }
        is("b00100".U) {
          when(funct3 <= 2.U) { fprWrite(FloatingOperationKind.Sign, true, true) }
        }
        is("b00101".U) {
          when(funct3 <= 1.U) { fprWrite(FloatingOperationKind.MinMax, true, true) }
        }
        is("b10100".U) {
          when(funct3 <= 2.U) {
            xprWrite(FloatingOperationKind.Compare)
            d.floating.usesRs2 := true.B
          }
        }
        // FMV.X.W / FMV.X.D, FCLASS.S / FCLASS.D.
        is("b11100".U) {
          when(rs2 === 0.U) {
            when(funct3 === 1.U) {
              xprWrite(FloatingOperationKind.Classify)
            }.elsewhen(funct3 === 0.U && (fmt === 0.U || (isa.xlen == 64).B)) {
              xprWrite(FloatingOperationKind.MoveToInt)
            }
          }
        }
        // FCVT.{W,WU,L,LU}.{S,D}. Reserved rs2 values trap.
        is("b11000".U) {
          when(rs2 <= 3.U && rmValid && ((isa.xlen == 64).B || rs2 <= 1.U)) {
            xprWrite(FloatingOperationKind.ConvertToInt)
            d.floating.integerWidth := rs2(1)
            d.floating.integerUnsigned := rs2(0)
          }
        }
        // FCVT.{S,D}.{W,WU,L,LU}.
        is("b11010".U) {
          when(rs2 <= 3.U && rmValid && ((isa.xlen == 64).B || rs2 <= 1.U)) {
            xprRead(FloatingOperationKind.ConvertFromInt)
            d.floating.integerWidth := rs2(1)
            d.floating.integerUnsigned := rs2(0)
          }
        }
        // FCVT.S.D (fmt=00, rs2=1), FCVT.D.S (fmt=01, rs2=0).
        is("b01000".U) {
          when(dSupported && rmValid &&
            ((fmt === 0.U && rs2 === 1.U) || (fmt === 1.U && rs2 === 0.U))) {
            fprWrite(FloatingOperationKind.ConvertFormat, true)
            when(fmt === 0.U) {
              d.floating.sourceFormat := FloatingFormat.Double
            }.otherwise {
              d.floating.sourceFormat := FloatingFormat.Single
            }
          }
        }
        // FMV.W.X / FMV.D.X requires rs2=0 and rm=000.
        is("b11110".U) {
          when(rs2 === 0.U && funct3 === 0.U && (fmt === 0.U || (isa.xlen == 64).B)) {
            xprRead(FloatingOperationKind.MoveFromInt)
          }
        }
      }
    }

    d
  }
}
