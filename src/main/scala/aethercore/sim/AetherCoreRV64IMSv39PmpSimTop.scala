package aethercore.sim

import aethercore.config.CoreProfiles

/**
  * Thin simulation shell for the first production RV64 paged profile.
  * RV64 第一层生产分页合同的薄仿真顶层。
  *
  * The VM implementation remains owned by the shared AetherCore datapath.
  * This wrapper selects only RV64IM + Zicsr, M/S/U, Sv39 and PMP16 over PA56.
  * RV64A, RV64C, Sstc and supervisor interrupt devices remain later layers.
  */
class AetherCoreRV64IMSv39PmpSimTop
    extends AetherCoreSimTop(
      CoreProfiles.rv64imsuSv39PmpSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = false
    )
