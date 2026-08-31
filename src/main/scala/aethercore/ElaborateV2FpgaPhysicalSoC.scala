package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherCoreV2FpgaPhysicalSoC

object ElaborateV2FpgaPhysicalSoCRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2FpgaPhysicalSoC,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
