package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV12

/** Elaboration entry for the F7 v2 core on the qualified RV64 OpenSBI board. */
object ElaborateV2OpenSbiRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV11,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}