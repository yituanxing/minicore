package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV64IMPmpSimTop

/** Elaboration entry for the bounded RV64 M/S/U PMP16 V1 profile. */
object ElaborateRV64IMPmp extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV64IMPmpSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
