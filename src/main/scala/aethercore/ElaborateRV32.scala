package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32SimTop

object ElaborateRV32 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32SimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
