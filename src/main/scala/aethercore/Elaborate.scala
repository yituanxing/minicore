package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreSimTop(CoreProfiles.rv64imCurrent),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
