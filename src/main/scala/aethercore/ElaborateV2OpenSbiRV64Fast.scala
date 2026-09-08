package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2OpenSbiRV64SimTop

/**
  * Fast RV64 OpenSBI/Linux elaboration for host-runtime A/B.
  * Architectural behavior is unchanged; simulation-only performance counter
  * banks are omitted so host throughput is the quantity under test.
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
