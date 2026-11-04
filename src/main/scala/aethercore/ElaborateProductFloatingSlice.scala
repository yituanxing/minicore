package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.product.{ProductCoreProfiles, ProductSmallMemoryCore}

/** Isolated one-wide FPGA *core* cost oracle, not an FPGA SoC or Linux machine.
  *
  * Compare the same executable SmallOoO geometry with its architectural F/D
  * resources off/on. The FP-enabled lane still intentionally traps unqualified
  * IEEE arithmetic. This entrypoint makes FPR/IQ/LSQ cost visible without
  * lying about ProductSmallPagedCore (which continues to reject F/D).
  *
  * Usage: ... ElaborateProductFloatingSlice small|expanded integer|floating
  *        --target-dir PATH
  */
object ElaborateProductFloatingSlice extends App {
  require(args.length >= 2,
    "FPGA floating cost needs geometry small|expanded and ISA integer|floating")
  val geometry = args(0) match {
    case "small" => ProductCoreProfiles.smallGeometry
    case "expanded" => ProductCoreProfiles.expandedSingleLaneGeometry
    case other => throw new IllegalArgumentException(s"unsupported one-wide geometry: $other")
  }
  val software = args(1) match {
    case "integer" => ProductCoreProfiles.engineeringSoftware
    case "floating" => ProductCoreProfiles.distroSoftware
    case other => throw new IllegalArgumentException(s"unsupported floating cost target: $other")
  }
  require(geometry.decodeWidth == 1 && geometry.dispatchWidth == 1 &&
    geometry.issueWidth == 1 && geometry.retireWidth == 1,
    "FPGA floating cost must compare the same one-wide backend")
  // Match the real Product FPGA PA32 envelope, not the broader simulator
  // platform's default PA geometry. Both measurements share this platform.
  val config = ProductCoreProfiles.configure(
    s"cost-${args(0)}-${args(1)}", geometry, software).copy(
      platform = ProductCoreProfiles.smallEngineering.platform.copy(paddrBits = 32))
  ChiselStage.emitSystemVerilogFile(
    new ProductSmallMemoryCore(config),
    args.drop(2),
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
