package aethercore.sim

import aethercore.config.CoreProfiles

/**
  * V1 Supervisor-mode qualification top.
  *
  * Keep the platform deliberately identical to the existing RV32 bare-metal
  * harness. V1 qualifies privilege transitions and delegated synchronous
  * traps only; it does not add Sv32, PMP or supervisor interrupt devices.
  */
class AetherCoreRV32IMSupervisorSimTop
    extends AetherCoreSimTop(
      CoreProfiles.rv32imsuSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = false
    )
