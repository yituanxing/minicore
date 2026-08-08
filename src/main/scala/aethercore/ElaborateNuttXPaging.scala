package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreNuttXPagingSimTop

object ElaborateNuttXPaging extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreNuttXPagingSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
