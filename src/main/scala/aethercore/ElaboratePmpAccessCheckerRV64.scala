package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.{PmpAccessChecker, PmpConstants}

/** Isolated RV64 PA56 PMP access lane for the Xilinx LUT-priority A/B.
  * No range decoder, CSR state, board pins, or complete Product SoC is implied.
  */
object ElaboratePmpAccessCheckerRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new PmpAccessChecker(PmpConstants.MaxEntries, 56),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
