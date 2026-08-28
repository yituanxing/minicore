package aethercore.sim

import aethercore.soc.AetherCoreV2LinuxSoC

/**
  * Simulation compatibility name for the production Linux-capable SoC shell.
  *
  * All CPU/cache/PMA/MMIO/UART/PLIC/timer behavior now lives in
  * aethercore.soc.AetherCoreV2LinuxSoC. Verilator runners keep this historical
  * top-level class name so existing executable evidence remains reusable.
  */
class AetherCoreV2OpenSbiRV64SimTop extends AetherCoreV2LinuxSoC()
