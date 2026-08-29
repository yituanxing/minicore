package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2VirtualFpgaBoardSimTop

object ElaborateV2VirtualFpgaBoardRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2VirtualFpgaBoardSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
