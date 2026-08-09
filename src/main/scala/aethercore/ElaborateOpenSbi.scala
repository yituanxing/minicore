package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreOpenSbiSimTop

object ElaborateOpenSbi extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreOpenSbiSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
