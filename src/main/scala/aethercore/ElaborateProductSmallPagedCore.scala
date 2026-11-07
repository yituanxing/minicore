package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.product.{ProductCoreProfiles, ProductSmallPagedCore}

/** Elaborate the fetch/Sv39/privileged Small Product integration directly.
  * This is a physical CPU-bus boundary, not a platform or Linux simulator.
  */
object ElaborateProductSmallPagedCore extends App {
  ChiselStage.emitSystemVerilogFile(
    new ProductSmallPagedCore(ProductCoreProfiles.smallEngineering),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}

/** Explicit RV64GC/LP64D variant of the *same* Small paged CPU.
  * Do not mutate the established soft-float engineering elaboration or
  * infer distro/board qualification from successful RTL generation.
  */
object ElaborateProductSmallPagedRv64gc extends App {
  ChiselStage.emitSystemVerilogFile(
    new ProductSmallPagedCore(ProductCoreProfiles.smallDebianTarget),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
