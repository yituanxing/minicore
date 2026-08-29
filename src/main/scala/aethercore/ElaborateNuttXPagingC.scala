package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreNuttXPagingCSimTop

object ElaborateNuttXPagingC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreNuttXPagingCSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
