package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreOpenSbiRV64SimTop

/** Elaboration entry for the shared RV64 OpenSBI platform shell. */
object ElaborateOpenSbiRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreOpenSbiRV64SimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
