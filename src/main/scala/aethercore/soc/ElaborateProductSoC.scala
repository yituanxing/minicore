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

/** Opt-in RV64GC/LP64D FPGA emission through the same selected CPU target.
  *
  * The established IMAC engineering entrypoint above remains unchanged. This
  * emits a candidate full SoC netlist for qualification, not a DDR/Fmax or
  * Debian boot pass.
  */
object ElaborateProductFpgaSoCRv64gc extends App {
  require(args.nonEmpty, "RV64GC FPGA emission needs small or expanded capacity")
  val geometry = args.head match {
    case "small" => ProductCoreProfiles.smallGeometry
    case "expanded" => ProductCoreProfiles.expandedSingleLaneGeometry
    case other => throw new IllegalArgumentException(s"unknown RV64GC FPGA geometry: $other")
  }
  val target = AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(32, geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaSoC(implementedPaddrBits = 32,
      productConfigOverride = Some(target)),
    args.tail,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

/** The physical pin-only shell is deliberately separate from the fully
  * instrumented FPGA verification top. Both instantiate the identical CPU,
  * SoC, target configuration and board-neutral PA32 AXI interface.
  */
object ElaborateProductFpgaPinsGeometry extends App {
  require(args.nonEmpty, "Product FPGA pin PPA needs small or expanded capacity")
  val geometry = args.head match {
    case "small" => ProductCoreProfiles.smallGeometry
    case "expanded" => ProductCoreProfiles.expandedSingleLaneGeometry
    case other => throw new IllegalArgumentException(s"unknown Product FPGA pin geometry: $other")
  }
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaPins(implementedPaddrBits = 32,
      productGeometry = geometry),
    args.tail,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateProductFpgaPinsRv64gc extends App {
  require(args.nonEmpty, "RV64GC FPGA pin emission needs small or expanded capacity")
  val geometry = args.head match {
    case "small" => ProductCoreProfiles.smallGeometry
    case "expanded" => ProductCoreProfiles.expandedSingleLaneGeometry
    case other => throw new IllegalArgumentException(s"unknown RV64GC FPGA pin geometry: $other")
  }
  val target = AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(32, geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaPins(implementedPaddrBits = 32,
      productConfigOverride = Some(target)),
    args.tail,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
