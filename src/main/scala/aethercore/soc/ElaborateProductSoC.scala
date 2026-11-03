package aethercore.soc

import _root_.circt.stage.ChiselStage

/** Independent emission entrypoints; never replace the qualified V2 SoC top.
  *
  * The 32-bit PA FPGA shell must be synthesized with the actual board DDR/clock
  * constraints before any frequency or Linux-boot qualification is claimed.
  */
object ElaborateProductUnifiedMemorySoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductUnifiedMemorySoC(),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateProductAxi4SoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductAxi4SoC(),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateProductFpgaSoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaSoC(),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
