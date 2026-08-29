package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherCoreV2LinuxSoC

/**
  * Production elaboration entry for the Linux-capable AetherSoC v0 shell.
  *
  * This is intentionally distinct from the historical Verilator SimTop name:
  * FPGA/synthesis flows should elaborate this product boundary directly.
  */
object ElaborateV2SoCRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreV2LinuxSoC,
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
