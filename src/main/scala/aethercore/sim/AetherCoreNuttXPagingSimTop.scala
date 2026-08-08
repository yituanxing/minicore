package aethercore.sim

import aethercore.config.CoreProfiles

/** Correctness-first system shell for the first real NuttX Sv32 boot.
  *
  * The pinned rv-virt kernel is linked as a supervisor payload at 0x80200000,
  * while 0x80000000 is the platform RAM base normally occupied by firmware.
  * N5-C does not model that firmware yet, so the direct-payload probe starts
  * fetching at the kernel's linked entry instead of manufacturing an illegal
  * fetch from an empty 0x80000000 region. Privilege/firmware services remain
  * intentionally unmasked so the real workload can expose the next contract.
  */
class AetherCoreNuttXPagingSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imasuSv32Software.copy(
        name = "rv32imasu-sv32-nuttx-direct-payload",
        platform = CoreProfiles.rv32imasuSv32Software.platform.copy(
          resetVector = BigInt("80200000", 16)
        )
      ),
      stopOnTrap = false,
      withMachineInterruptPlatform = false,
      stopOnWfi = false
    )
