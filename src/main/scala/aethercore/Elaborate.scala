package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreSimTop

object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
