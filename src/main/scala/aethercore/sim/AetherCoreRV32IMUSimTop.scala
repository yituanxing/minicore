package aethercore.sim

import aethercore.config.CoreProfiles

class AetherCoreRV32IMUSimTop
    extends AetherCoreSimTop(CoreProfiles.rv32imuSoftware, stopOnTrap = false)
