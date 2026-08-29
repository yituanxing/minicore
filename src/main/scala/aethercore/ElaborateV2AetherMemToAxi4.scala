package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.AetherMemToAxi4Bridge

object ElaborateV2AetherMemToAxi4 extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherMemToAxi4Bridge(
      addrBits = 56,
      dataBits = 64,
      txnIdBits = 4
    ),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
