package aethercore.sim

import aethercore.config.CoreProfiles
import aethercore.core.MachinePlicMmioMap

/** Correctness-first system shell for the real NuttX Sv32+PMP boot.
  *
  * N5 exposes the QEMU-virt hart0 Supervisor PLIC context required by the real
  * kernel's up_irqinitialize() path. The M-mode handoff installs an allow-all
  * PMP entry before entering S-mode; focused core tests independently qualify
  * denied translated instruction/data/PTW accesses.
  */
class AetherCoreNuttXPagingSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imasuSv32PmpSoftware,
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
