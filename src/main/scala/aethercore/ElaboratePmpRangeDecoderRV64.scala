package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.{PmpConstants, PmpRangeDecoder}

/** Local RV64 PA56 PMP combinational PPA probe: NOT a CPU or board fit test. */
object ElaboratePmpRangeDecoderRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new PmpRangeDecoder(64, PmpConstants.MaxEntries, 56),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
