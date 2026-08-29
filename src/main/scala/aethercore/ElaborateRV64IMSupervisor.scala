package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV64IMSupervisorSimTop

/** Elaboration entry for the bounded RV64 M/S/U Supervisor V1 profile. */
object ElaborateRV64IMSupervisor extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV64IMSupervisorSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
