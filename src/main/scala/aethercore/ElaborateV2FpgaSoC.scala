package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherCoreV2FpgaSoC

object ElaborateV2FpgaSoCRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2FpgaSoC,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
