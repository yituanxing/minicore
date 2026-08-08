package aethercore.sim

import aethercore.config.CoreProfiles
import aethercore.core.MachinePlicMmioMap

/** Correctness-first system shell for the first real NuttX Sv32 boot.
  *
  * N5 now exposes the QEMU-virt hart0 Supervisor PLIC context required by the
  * real kernel's up_irqinitialize() path. RX injection remains disabled in the
  * boot probe, so this slice validates MMIO compatibility without pretending
  * that Supervisor external interrupt delegation is already complete.
  */
class AetherCoreNuttXPagingSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imasuSv32Software,
      stopOnTrap = false,
      withMachineInterruptPlatform = true,
      stopOnWfi = false,
      withNs16550Uart = true,
      interruptPlatformSourceCount = 52,
      interruptPlicEnableBase = MachinePlicMmioMap.SupervisorEnable,
      interruptPlicThresholdOffset = MachinePlicMmioMap.SupervisorThreshold,
      interruptPlicClaimCompleteOffset = MachinePlicMmioMap.SupervisorClaimComplete,
      interruptUartSourceId = 10
    )
