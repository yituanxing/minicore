package aethercore.sim

import aethercore.config.CoreProfiles

/** Frozen L32 OpenSBI/Linux simulation shell.
  *
  * Keep the existing first-stage RAM, ACLINT mtime/mtimecmp and ns16550 UART,
  * then add only the QEMU-virt-compatible Supervisor PLIC context required by
  * Linux's real ttyS0 interrupt-driven transmit path. OpenSBI owns the PMP
  * domain policy; AetherCore only enforces the resulting physical accesses.
  */
class AetherCoreOpenSbiSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imasuSv32PmpSoftware,
      stopOnTrap = false,
      withMachineInterruptPlatform = false,
      withSupervisorInterruptPlatform = true,
      stopOnWfi = false,
      withNs16550Uart = true,
      supervisorPlicSourceCount = 52,
      supervisorUartSourceId = 10
    )
