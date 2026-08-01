package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32IMSimTop

object ElaborateRV32IM extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32IMSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
