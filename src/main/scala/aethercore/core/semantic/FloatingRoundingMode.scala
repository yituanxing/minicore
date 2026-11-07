package aethercore.core.semantic

import chisel3._

/** RISC-V F/D rounding-mode resolution at an operation that actually rounds.
  *
  * The three-bit FCSR.frm is architectural state and must preserve all
  * encodings, including reserved 101, 110 and 111.  An instruction rm of 111
  * selects that stored state; static rm 101/110 are also reserved.  No
  * substitution of a guessed default is permitted when a rounding-dependent
  * operation encounters a reserved value.
  *
  * Call only for instructions that use rm (FP arithmetic and conversions).
  * FSGNJ/FMIN/FMAX/FCMP/FCLASS/FMVs use funct3 as an operation selector or
  * have no rm field, and must not be rejected based on an unrelated frm.
  * Invalid selection may be handled with an exact illegal-instruction trap.
  */
object FloatingRoundingMode {
  val Dynamic: Int = 7
  val LargestLegal: Int = 4

  def resolveForArithmetic(instructionRm: UInt, frm: UInt): (UInt, Bool) = {
    val selected = Mux(instructionRm === Dynamic.U(3.W), frm, instructionRm)
    (selected, selected <= LargestLegal.U)
  }
}
