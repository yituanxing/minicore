package aethercore.sim

import aethercore.config.CoreProfiles

/** AetherCore platform used by the NuttX protected userspace qualification.
  *
  * This deliberately combines every boundary needed by the OS-backed U-mode
  * path in one profile:
  *   - RV32IM + Zicsr with M/U privilege modes;
  *   - four PMP entries;
  *   - CLINT-compatible machine timer;
  *   - PLIC plus UART RX machine external interrupt;
  *   - traps and WFI remain live so NuttX controls termination.
  */
class AetherCoreNuttXProtectedSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imuPmpSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = true,
      stopOnWfi = false
    )
