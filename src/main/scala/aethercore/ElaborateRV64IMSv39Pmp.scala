package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV64IMSv39PmpSimTop

/** Elaboration entry for the bounded RV64 Sv39 + PMP16 production profile. */
object ElaborateRV64IMSv39Pmp extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV64IMSv39PmpSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
