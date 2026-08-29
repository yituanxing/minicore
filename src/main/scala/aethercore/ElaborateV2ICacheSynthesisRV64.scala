package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherSoCInstructionCache

object ElaborateV2ICacheSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherSoCInstructionCache(
      addrBits = 56,
      dataBits = 64,
      txnIdBits = 2,
      entries = 64
    ),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
