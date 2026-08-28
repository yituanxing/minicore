package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherSoCMemoryHub

object ElaborateV2SoCMemoryHub extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherSoCMemoryHub(
      addrBits = 56,
      dataBits = 64,
      clientTxnIdBits = 2,
      clientCount = 3
    ),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
