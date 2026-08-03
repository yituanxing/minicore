package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32IMUPmpSimTop

object ElaborateRV32IMUPmp extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32IMUPmpSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
