package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreV2VirtualFpgaBoardSimTop
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

/** Experimental only: export the unchanged PA32 virtual FPGA board as CHIRRTL for GSIM. */
object ElaborateV2VirtualFpgaBoardPA32CHIRRTL extends App {
  require(args.length == 1, "usage: ElaborateV2VirtualFpgaBoardPA32CHIRRTL OUTPUT.fir")
  val output = Paths.get(args(0)).toAbsolutePath.normalize()
  val parent = output.getParent
  if (parent != null) Files.createDirectories(parent)
  val chirrtl = ChiselStage.emitCHIRRTL(
    new AetherCoreV2VirtualFpgaBoardSimTop(implementedPaddrBits = 32)
  )
  require(chirrtl.startsWith("FIRRTL version") || chirrtl.startsWith("circuit "),
    "CHIRRTL output missing FIRRTL circuit header")
  Files.writeString(output, chirrtl, StandardCharsets.UTF_8)
  println(s"AETHERCORE_PA32_CHIRRTL_EXPORT_PASS bytes=${chirrtl.length} path=${output}")
}
