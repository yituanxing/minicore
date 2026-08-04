package aethercore.sim

import aethercore.config.CoreProfiles

class AetherCoreRV32IMTrapSimTop
    extends AetherCoreSimTop(
      CoreProfiles.rv32imSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = true
    )
