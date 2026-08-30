package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.config.PageTableGeometry
import aethercore.core.{DataPathAdapter, PmpChecker, PmpConstants}
import aethercore.core.v2.{TinyBlockingLsu, TinyDualReplaySafeLoadUnit}

object ElaborateTinyBlockingLsuSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyBlockingLsu(
      PageTableGeometry.Sv39,
      paddrBits = PageTableGeometry.Sv39.architecturalPhysicalAddressBits,
      tlbEntries = 8,
      txnIdBits = 2,
      allowAtomics = false
    ),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateTinyDualReplaySafeLoadUnitSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyDualReplaySafeLoadUnit(
      PageTableGeometry.Sv39,
      paddrBits = PageTableGeometry.Sv39.architecturalPhysicalAddressBits,
      tlbEntries = 8,
      txnIdBits = 2
    ),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}


object ElaborateDataPathAdapterSynthesisRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new DataPathAdapter(
      PageTableGeometry.Sv39,
      paddrBits = PageTableGeometry.Sv39.architecturalPhysicalAddressBits,
      tlbEntries = 8
    ),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaboratePmpCheckerSynthesisRV64Pa56 extends App {
  ChiselStage.emitSystemVerilogFile(
    new PmpChecker(
      xlen = 64,
      entries = PmpConstants.MaxEntries,
      paddrBits = PageTableGeometry.Sv39.architecturalPhysicalAddressBits
    ),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
