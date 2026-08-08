package aethercore.sim

import aethercore.config.CoreProfiles

/** First L32 execution shell for pinned RV32 OpenSBI v1.6.
  *
  * Keep the platform intentionally small. The embedded FDT describes only the
  * RAM, NS16550 console and ACLINT MTIMER already present in the frozen N5
  * platform. Add PLIC/MSIP or Linux-only devices only when real execution
  * demonstrates the requirement.
  */
class AetherCoreOpenSbiSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imasuSv32Software,
      stopOnTrap = false,
      withMachineInterruptPlatform = false,
      stopOnWfi = false,
      withNs16550Uart = true
    )
