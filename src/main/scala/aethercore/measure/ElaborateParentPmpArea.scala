package aethercore.measure

import chisel3._
import _root_.circt.stage.ChiselStage
import aethercore.config.{CoreConfig, CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyLoadQueueMemoryBackend

/**
  * Measurement-only variant used to estimate the integrated area ceiling of
  * removing the exact-head Store/Atomic LSU's private PMP checker.
  *
  * This is NOT a functionally valid product candidate: parent PMP enforcement
  * is intentionally disabled so synthesis can remove that cone. The result is
  * only an admission bound for a future design that would move parent
  * Store/Atomic permission checking onto the already-single physical request
  * arbitration seam shared with LoadQ2.
  */
class TinyLoadQueueMemoryBackendNoParentPmp(
    config: CoreConfig,
    geometry: PageTableGeometry
) extends TinyLoadQueueMemoryBackend(
      config,
      geometry,
      tlbEntries = 8,
      txnIdBits = 2,
      enableAsyncInterrupts = true,
      withSupervisorExternalInterrupt = true,
      allowAtomics = true
    ) {
  // Last-connect overrides the qualified parent wiring from TinyMemoryBackend.
  // The dual-Load shared PMP and the shared PTW PMP remain enabled.
  lsu.io.pmpEnabled := false.B
}

private object ParentPmpMeasureConfig {
  val base = CoreProfiles.rv64imasuSv39PmpSoftware
  val value = base.copy(
    name = "rv64imasu-sv39-pmp-parent-pmp-area",
    isa = base.isa.copy(
      zExtensions = base.isa.zExtensions + "Zifencei",
      machineProvidedSupervisorTimer = true,
      timeCounter = true
    )
  )
}

object ElaborateLoadQueueBackendCurrentRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyLoadQueueMemoryBackend(
      ParentPmpMeasureConfig.value,
      PageTableGeometry.Sv39,
      tlbEntries = 8,
      txnIdBits = 2,
      enableAsyncInterrupts = true,
      withSupervisorExternalInterrupt = true,
      allowAtomics = true
    ),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateLoadQueueBackendNoParentPmpRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyLoadQueueMemoryBackendNoParentPmp(
      ParentPmpMeasureConfig.value,
      PageTableGeometry.Sv39
    ),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
