package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32IMTrapSimTop

object ElaborateRV32IMTrap extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32IMTrapSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
