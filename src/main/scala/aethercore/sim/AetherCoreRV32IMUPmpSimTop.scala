package aethercore.sim

import aethercore.config.CoreProfiles

class AetherCoreRV32IMUPmpSimTop
    extends AetherCoreSimTop(CoreProfiles.rv32imuPmpSoftware, stopOnTrap = false)
