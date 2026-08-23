package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2Rob8ExperimentTop

/** Elaboration entry for the isolated P8 ROB8/window8 experiment. */
object ElaborateV2Rob8OpenSbiRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2Rob8ExperimentTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
