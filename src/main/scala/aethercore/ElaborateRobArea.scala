package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.v2.{TinyDependencyBackend, TinyRob}

object ElaborateTinyRobAreaRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyRob(64),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateTinyDependencyBackendAreaRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyDependencyBackend(64),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
