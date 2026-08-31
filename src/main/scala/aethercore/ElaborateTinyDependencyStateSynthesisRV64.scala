package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.v2.TinyDependencyState

/** Standalone RV64 dependency-state synthesis top for FPGA area attribution. */
object ElaborateTinyDependencyStateSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyDependencyState(64),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
