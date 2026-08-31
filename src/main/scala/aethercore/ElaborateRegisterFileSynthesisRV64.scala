package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.RegisterFile

/** Standalone RV64 architectural register-file synthesis top for FPGA area attribution. */
object ElaborateRegisterFileSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new RegisterFile(64),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
