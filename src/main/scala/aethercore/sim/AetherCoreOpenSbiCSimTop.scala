package aethercore.sim

import aethercore.config.CoreProfiles

/** Linux/OpenSBI peer top that differs from the frozen L32 system only by C. */
class AetherCoreOpenSbiCSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imacsuSv32PmpSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = false,
      withSupervisorInterruptPlatform = true,
      stopOnWfi = false,
      withNs16550Uart = true,
      supervisorPlicSourceCount = 52,
      supervisorUartSourceId = 10
    )
