package aethercore.sim

import aethercore.config.CoreProfiles
import aethercore.core.MachinePlicMmioMap

/** RV32IMAC peer of the frozen N5 Sv32+PMP system shell.
  *
  * The platform/MMU/PMP/Sstc/PLIC wiring is intentionally identical to the
  * historical RV32IMA N5 top. Only the ISA profile adds C, so unchanged NuttX
  * can drive the already-qualified compressed frontend through real S/U-mode
  * paging and interrupt execution.
  */
class AetherCoreNuttXPagingCSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imacsuSv32PmpSoftware,
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
