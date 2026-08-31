package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherCoreV2FpgaSoC

object ElaborateV2FpgaSoCPA32RV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2FpgaSoC(implementedPaddrBits = 32),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
