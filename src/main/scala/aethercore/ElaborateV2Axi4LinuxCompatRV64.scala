package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2Axi4CompatSimTop

object ElaborateV2Axi4LinuxCompatRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2Axi4CompatSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
