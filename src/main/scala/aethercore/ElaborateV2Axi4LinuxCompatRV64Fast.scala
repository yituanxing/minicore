package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2Axi4CompatSimTop

/**
  * Fast architectural-cycle BusyBox measurement through the production AXI4
  * memory path. It intentionally omits P8 counter banks; the AXI compat top
  * retains only lightweight same-source read-concurrency counters.
  */
object ElaborateV2Axi4LinuxCompatRV64Fast extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2Axi4CompatSimTop,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
