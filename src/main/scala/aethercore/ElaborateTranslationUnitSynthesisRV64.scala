package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.config.PageTableGeometry
import aethercore.core.TranslationUnit

/** Standalone Sv39 TLB8+PTW synthesis top for FPGA area attribution. */
object ElaborateTranslationUnitSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TranslationUnit(PageTableGeometry.Sv39, tlbEntries = 8),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
