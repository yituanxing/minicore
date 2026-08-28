package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherCoreV2Axi4SoC

object ElaborateV2Axi4SoCRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2Axi4SoC,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
