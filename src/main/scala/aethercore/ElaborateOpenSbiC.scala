package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreOpenSbiCSimTop

object ElaborateOpenSbiC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreOpenSbiCSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
