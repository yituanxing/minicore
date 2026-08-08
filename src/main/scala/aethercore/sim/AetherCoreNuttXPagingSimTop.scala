package aethercore.sim

import aethercore.config.CoreProfiles

/** Correctness-first system shell for the first real NuttX Sv32 boot.
  *
  * Keep the platform deliberately minimal. The real kernel is expected to
  * expose the next missing architectural contract; we add devices/privilege
  * features only when execution reaches them.
  */
class AetherCoreNuttXPagingSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imasuSv32Software,
      stopOnTrap = false,
      withMachineInterruptPlatform = false,
      stopOnWfi = false,
      withNs16550Uart = true
    )
