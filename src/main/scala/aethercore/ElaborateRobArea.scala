package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.v2._

object ElaborateTinyRobAreaRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyRob(64),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateTinyDependencyStateAreaRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyDependencyState(64),
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

object ElaborateTinySelectiveComputeIssueAreaRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinySelectiveComputeIssue(64),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
