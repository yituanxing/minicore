package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32IMSupervisorSimTop

object ElaborateRV32IMSupervisor extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32IMSupervisorSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
