package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2OpenSbiRV64SimTop

/** Same-software baseline for exact safe full-beat I-cache A/B. */
object ElaborateV2OpenSbiRV64FastSafeFullBeatOff extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2OpenSbiRV64SimTop(enableSafeFullBeatIcache = false),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}

/** Same-software candidate for exact safe full-beat I-cache A/B. */
object ElaborateV2OpenSbiRV64FastSafeFullBeatOn extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2OpenSbiRV64SimTop(enableSafeFullBeatIcache = true),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
