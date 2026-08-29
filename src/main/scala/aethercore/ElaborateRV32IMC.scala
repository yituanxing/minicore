package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32IMCSimTop

object ElaborateRV32IMC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32IMCSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
