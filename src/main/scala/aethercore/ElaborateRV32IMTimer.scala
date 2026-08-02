package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32IMTimerSimTop

object ElaborateRV32IMTimer extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32IMTimerSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
