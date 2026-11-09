package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.{PmpConstants, PmpSerialRangePredecoder}

/** Standalone FPGA-area probe; no Product CSR or front-end wiring implied. */
object ElaboratePmpSerialRangePredecoderRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new PmpSerialRangePredecoder(64, PmpConstants.MaxEntries, 56),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
