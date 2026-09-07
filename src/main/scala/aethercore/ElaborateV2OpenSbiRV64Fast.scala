package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2OpenSbiRV64SimTop

/**
  * Fast RV64 OpenSBI/Linux elaboration for cycle-accurate performance A/B.
  *
  * This deliberately instantiates the plain qualified V2 SoC top without the
  * simulation-only performance/top-down/attribution counter banks. Architectural
  * behavior is unchanged; only observation state is removed so long Linux and
  * BusyBox runs are not dominated by instrumentation overhead.
  */
object ElaborateV2OpenSbiRV64Fast extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2OpenSbiRV64SimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
