package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2MemoryDecompositionTop

/** Measurement-only elaboration entry stacked on the frozen #161 v2 RV64 top. */
object ElaborateV2MemoryDecomposition extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2MemoryDecompositionTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
