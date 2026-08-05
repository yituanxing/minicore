package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

object ElaborateZephyr extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreSimTop(
      config = CoreProfiles.rv32imSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = true,
      stopOnWfi = false
    ),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
