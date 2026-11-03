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
