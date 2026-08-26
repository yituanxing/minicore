package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore

/**
  * Core-only RV64 synthesis entry used for exact-head structural-cost A/B.
  *
  * It intentionally matches the qualified v2 OpenSBI CPU profile while leaving
  * RAM/MMIO termination outside the synthesized boundary. This keeps the
  * #189-vs-#191 gray-zone comparison focused on the CPU/frontend/backend rather
  * than host-model infrastructure.
  */
object ElaborateV2CoreSynthesisRV64 extends App {
  private val base = CoreProfiles.rv64imasuSv39PmpSoftware
  private val config = base.copy(
    name = "rv64imasu-sv39-pmp-v2-synthesis",
    isa = base.isa.copy(
      zExtensions = base.isa.zExtensions + "Zifencei",
      machineProvidedSupervisorTimer = true,
      timeCounter = true
    )
  )

  ChiselStage.emitSystemVerilogFile(
    new TinyPagedCore(
      config,
      PageTableGeometry.Sv39,
      txnIdBits = 2,
      enableAsyncInterrupts = true,
      withSupervisorExternalInterrupt = true
    ),
    args,
    Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket"
    )
  )
}
