package aethercore.sim

import _root_.circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** The real RV64GC/LP64D Debian RAM-initramfs Product Top, not G3 IMAC.
  * SD1 media requires a separate host-device bridge and is not selected here.
  */
object ElaborateProductDebianRV64GSIMCHIRRTL extends App {
  require(args.length == 1,
    "usage: ElaborateProductDebianRV64GSIMCHIRRTL OUTPUT.fir")
  val output = Paths.get(args(0)).toAbsolutePath.normalize()
  val parent = output.getParent
  if (parent != null) Files.createDirectories(parent)
  val chirrtl = ChiselStage.emitCHIRRTL(new AetherCoreProductDebianRV64SimTop)
  require(chirrtl.startsWith("FIRRTL version") || chirrtl.startsWith("circuit "),
    "Product Debian GSIM export missing FIRRTL circuit header")
  Files.writeString(output, chirrtl, StandardCharsets.UTF_8)
  println(s"AETHERCORE_PRODUCT_G5_GSIM_CHIRRTL_EXPORT_PASS bytes=${chirrtl.length} path=$output")
}
