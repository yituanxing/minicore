package aethercore.sim

import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Export the exact Product G3 RTL host boundary for the pinned GSIM engine.
  * The GSIM engine must simulate this target rather than the older V2/PA32 CPU.
  */
object ElaborateProductOpenSbiRV64GSIMCHIRRTL extends App {
  require(args.length == 1,
    "usage: ElaborateProductOpenSbiRV64GSIMCHIRRTL OUTPUT.fir")
  val output = Paths.get(args(0)).toAbsolutePath.normalize()
  val parent = output.getParent
  if (parent != null) Files.createDirectories(parent)
  val chirrtl = ChiselStage.emitCHIRRTL(new AetherCoreProductOpenSbiRV64SimTop)
  require(chirrtl.startsWith("FIRRTL version") || chirrtl.startsWith("circuit "),
    "Product GSIM export missing FIRRTL circuit header")
  Files.writeString(output, chirrtl, StandardCharsets.UTF_8)
  println(s"AETHERCORE_PRODUCT_GSIM_CHIRRTL_EXPORT_PASS bytes=${chirrtl.length} path=${output}")
}
