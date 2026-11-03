package aethercore.soc

import _root_.circt.stage.ChiselStage
import aethercore.core.product.ProductCoreProfiles

/** Independent emission entrypoints; never replace the qualified V2 SoC top.
  *
  * The 32-bit PA FPGA shell must be synthesized with the actual board DDR/clock
  * constraints before any frequency or Linux-boot qualification is claimed.
  */
object ElaborateProductUnifiedMemorySoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductUnifiedMemorySoC(),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateProductAxi4SoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductAxi4SoC(),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateProductFpgaSoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaSoC(),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

/** Exact same FPGA top/PA32 interface for reproducible Small-vs-Expanded PPA.
  * Selection changes physical storage depth, never decode/issue/retire width.
  */
object ElaborateProductFpgaSoCGeometry extends App {
  require(args.nonEmpty, "Product FPGA PPA needs small or expanded capacity")
  val capacity = args.head
  val geometry = capacity match {
    case "small" => ProductCoreProfiles.smallGeometry
    case "expanded" => ProductCoreProfiles.expandedSingleLaneGeometry
    case other => throw new IllegalArgumentException(s"unknown Product FPGA PPA geometry: $other")
  }
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaSoC(implementedPaddrBits = 32, productGeometry = geometry),
    args.tail,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
