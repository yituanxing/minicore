package aethercore.sim

import aethercore.config.CoreProfiles

class AetherCoreRV32IMCTrapSimTop
    extends AetherCoreSimTop(
      CoreProfiles.rv32imcSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = true
    )
