package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.{PmpChecker, PmpConstants}

/**
  * Standalone RV64 PA56 PMP checker synthesis top used only for exact A/B area
  * attribution. It keeps the production PMP16 surface and avoids unrelated CPU
  * logic so NAPOT decode cost is directly observable.
  */
object ElaboratePmpCheckerSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new PmpChecker(64, PmpConstants.MaxEntries, 56),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
