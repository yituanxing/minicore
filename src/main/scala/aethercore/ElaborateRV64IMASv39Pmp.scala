package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV64IMASv39PmpSimTop

/** Elaboration entry for the bounded RV64A + Sv39 + PMP16 production profile. */
object ElaborateRV64IMASv39Pmp extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV64IMASv39PmpSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
