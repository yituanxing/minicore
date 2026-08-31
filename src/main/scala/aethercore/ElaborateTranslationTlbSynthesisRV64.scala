package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.config.PageTableGeometry
import aethercore.core.TranslationTlb

object ElaborateTranslationTlbSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TranslationTlb(PageTableGeometry.Sv39, entries = 8),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
