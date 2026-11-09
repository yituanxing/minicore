package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.{PmpAccessChecker, PmpConstants}

/** Opt-in PA56 PMP priority experiment. Never selected by Product SoC. */
object ElaboratePmpAccessCheckerOneHotRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new PmpAccessChecker(PmpConstants.MaxEntries, 56, oneHotPriorityExperiment = true),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
