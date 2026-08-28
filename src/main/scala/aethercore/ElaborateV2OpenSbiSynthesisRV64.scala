package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2OpenSbiRV64SimTop

/**
  * Measurement-only synthesis entry for the raw v2 OpenSBI core-complex shell.
  *
  * Unlike the historical TinyPagedCore-only proxy, this boundary intentionally
  * includes the stage-1 D-cache composition point while excluding host-side
  * C++ runtime/measurement infrastructure.
  */
object ElaborateV2OpenSbiSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2OpenSbiRV64SimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
