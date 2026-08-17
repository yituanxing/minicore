package aethercore.sim

import aethercore.config.CoreProfiles

/**
  * Simulation top for the first bounded RV64 privileged-mode contract.
  * RV64 第一层受控特权态合同的仿真顶层。
  *
  * This profile intentionally stops at RV64IM + Zicsr, M/S/U and bare
  * translation. Sv39, PMP, A, C, Sstc and supervisor interrupt devices remain
  * later system frontiers rather than being pulled into this qualification.
  */
class AetherCoreRV64IMSupervisorSimTop
    extends AetherCoreSimTop(
      CoreProfiles.rv64imsuSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = false
    )
