package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.soc.{
  AetherSoCLegacyDataAdapter,
  AetherSoCInstructionReadAdapter,
  AetherSoCPtwReadAdapter
}

object ElaborateV2SoCLegacyDataAdapter extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherSoCLegacyDataAdapter(addrBits = 56, dataBits = 64, txnIdBits = 2),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateV2SoCInstructionReadAdapter extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherSoCInstructionReadAdapter(addrBits = 56, dataBits = 64, txnIdBits = 2),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateV2SoCPtwReadAdapter extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherSoCPtwReadAdapter(addrBits = 56, dataBits = 64, pteBits = 64, txnIdBits = 2),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
