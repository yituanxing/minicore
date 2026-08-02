package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreRV32IMUSimTop

object ElaborateRV32IMU extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreRV32IMUSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
