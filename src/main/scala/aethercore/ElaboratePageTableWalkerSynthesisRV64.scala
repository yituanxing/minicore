package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.config.PageTableGeometry
import aethercore.core.PageTableWalker

object ElaboratePageTableWalkerSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new PageTableWalker(PageTableGeometry.Sv39),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
