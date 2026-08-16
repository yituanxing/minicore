package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32IMCTrapSimTop

object ElaborateRV32IMCTrap extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32IMCTrapSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
