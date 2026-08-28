package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherCoreV2UnifiedMemorySoC

object ElaborateV2UnifiedMemorySoCRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2UnifiedMemorySoC,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
