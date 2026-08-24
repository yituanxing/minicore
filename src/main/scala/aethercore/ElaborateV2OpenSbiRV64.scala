package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2Rob8MeasuredOpenSbiRV64SimTopHostVisible

/** Elaboration entry for the F7 v2 core on the qualified RV64 OpenSBI board. */
object ElaborateV2OpenSbiRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2Rob8MeasuredOpenSbiRV64SimTopHostVisible,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
