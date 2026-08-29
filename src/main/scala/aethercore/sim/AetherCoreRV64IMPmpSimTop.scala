package aethercore.sim

import aethercore.config.CoreProfiles

/**
  * Simulation top for the bounded RV64 PMP16 contract.
  * RV64 PMP16 第一层隔离合同仿真顶层。
  *
  * This remains RV64IM + Zicsr, M/S/U and bare translation. PMP is the only
  * facility added on top of Supervisor V1; Sv39, A, C and Sstc stay separate.
  */
class AetherCoreRV64IMPmpSimTop
    extends AetherCoreSimTop(
      CoreProfiles.rv64imsuPmpSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = false
    )
