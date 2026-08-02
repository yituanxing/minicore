package aethercore.sim

import aethercore.config.CoreProfiles

class AetherCoreRV32IMTimerSimTop
    extends AetherCoreSimTop(
      CoreProfiles.rv32imSoftware,
      stopOnTrap = false,
      enableMachineTimer = true
    )
