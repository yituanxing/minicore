package aethercore.sim

import aethercore.config.CoreProfiles

/**
  * Thin simulation shell for the bounded RV64A production slice.
  * RV64A 受限生产切片的薄仿真顶层。
  *
  * Atomic execution remains owned by the shared AetherCore memory pipeline;
  * this wrapper only selects RV64IMA + Zicsr, M/S/U, Sv39 and PMP16 over PA56.
  */
class AetherCoreRV64IMASv39PmpSimTop
    extends AetherCoreSimTop(
      CoreProfiles.rv64imasuSv39PmpSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = false
    )
