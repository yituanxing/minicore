package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreNuttXProtectedSimTop

object ElaborateNuttXProtected extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreNuttXProtectedSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
