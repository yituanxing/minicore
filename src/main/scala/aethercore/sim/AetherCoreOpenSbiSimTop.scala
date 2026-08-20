package aethercore.sim

import aethercore.config.{CoreConfig, CoreProfiles}

/** Shared OpenSBI/Linux simulation shell.
  *
  * Keep the existing first-stage RAM, ACLINT mtime/mtimecmp, ns16550 UART and
  * QEMU-virt-compatible Supervisor PLIC context. OpenSBI owns the PMP domain
  * policy; AetherCore only enforces the resulting physical accesses.
  *
  * The default remains the frozen L32 profile so every historical call site
  * keeps identical construction semantics. RV64 selects only a different ISA
  * and VM profile; it does not duplicate the platform implementation.
  */
class AetherCoreOpenSbiSimTop(
    openSbiConfig: CoreConfig = CoreProfiles.rv32imasuSv32PmpSoftware
) extends AetherCoreSimTop(
      config = openSbiConfig,
      stopOnTrap = false,
      withMachineInterruptPlatform = false,
      withSupervisorInterruptPlatform = true,
      stopOnWfi = false,
      withNs16550Uart = true,
      supervisorPlicSourceCount = 52,
      supervisorUartSourceId = 10
    )

/** First bounded RV64 OpenSBI shell.
  *
  * Zifencei is added here as firmware pressure rather than folded backward
  * into the pure RV64A qualification slice. Without Sstc, OpenSBI needs the
  * base mip.STIP injection path, while Linux also reads the architectural time
  * CSR backed by the same platform mtime counter.
  */
class AetherCoreOpenSbiRV64SimTop
    extends AetherCoreOpenSbiSimTop(
      CoreProfiles.rv64imasuSv39PmpSoftware.copy(
        name = "rv64imasu-sv39-pmp-opensbi",
        isa = CoreProfiles.rv64imasuSv39PmpSoftware.isa.copy(
          zExtensions = CoreProfiles.rv64imasuSv39PmpSoftware.isa.zExtensions + "Zifencei",
          machineProvidedSupervisorTimer = true,
          timeCounter = true
        )
      )
    )
