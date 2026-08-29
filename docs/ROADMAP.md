# Roadmap

AetherCore/AetherSoC is advanced by executable software pressure and frozen qualification points. Real workloads expose missing architectural boundaries; focused regressions lock them down before the design is widened.

## Completed / frozen ladder

- directed RV32/RV64 pipeline and NEMU differential bring-up;
- RV64M, compiler-produced workloads, CoreMark, Embench and littlefs;
- FreeRTOS Machine-mode traps, timer/external interrupts and preemption;
- U-mode ECALL/syscall paths and PMP isolation;
- Zephyr and NuttX qualification;
- protected NuttX userspace and RV32A atomics;
- Supervisor V1 M/S/U execution;
- Supervisor Sv32 V2 translation, TLB/PTW, precise page faults and `SFENCE.VMA`;
- RV64 supervisor/Sv39 line;
- RV64A LR/SC and AMO execution;
- OpenSBI firmware bring-up;
- Linux 6.6.143 first execution and deeper runtime;
- AetherCore V2 architecture and performance line;
- AetherSoC PlatformFabric, MemoryHub and peripheral ownership;
- BootROM, PLIC, ACLINT-style MTIMER and ns16550-compatible UART;
- I-cache and D-cache;
- unified semantic memory and AXI4 external-memory boundary;
- DTS generation;
- FPGA-facing SoC and physical UART PHY;
- virtual FPGA board;
- pin-level OpenSBI -> Linux -> deterministic PID1 qualification;
- board-neutral FPGA synthesis proxy.

The authoritative current freeze record is `docs/AETHERSOC_V0_FREEZE.md`.

## AetherSoC v0 release closure — complete

Board-less AetherSoC v0 is frozen and promoted to `main`.

- final freeze synthesis: run `33250167081` — SUCCESS;
- freeze merge: `a502a71df7aac98d3997615e71b29661939ebb5e`;
- canonical `main` promotion: `f67838cc417a12c341fc56394e37aead3aa61295`.

New SoC features no longer belong to v0 closure. The next bounded hardware line is concrete FPGA-board bring-up and resource/timing closure.

## Next: concrete FPGA board

After v0, the next hardware milestone is board-specific rather than another abstract SoC rewrite.

Target sequence:

1. select a concrete FPGA device/board;
2. bind `AetherCoreV2FpgaSoC` to board clocks and reset;
3. integrate vendor/device PLL or clocking primitives;
4. integrate the actual DDR/SDRAM controller or external-memory interface;
5. write pin and timing constraints;
6. run synthesis, place-and-route and timing analysis;
7. generate a bitstream;
8. bring up UART first;
9. boot OpenSBI;
10. boot Linux and reach the same frozen PID1 milestone on real hardware.

Only this phase can establish a real Fmax and board boot-time claim.

## Performance after v0

Performance work is optional and measurement-driven. It must not be confused with missing SoC functionality.

Current evidence already shows that memory serialization mattered materially and that same-source Data overlap opportunities exist. Future candidates include:

- latency-bearing DDR models;
- deeper memory-level parallelism;
- cache refill/burst refinements;
- larger or more associative caches if workload data justifies them;
- branch/front-end work when measured as a material Linux bottleneck;
- execution/ROB/load-queue changes only when attribution supports them.

Each optimization requires an A/B measurement with identical retired work.

## Platform expansion

Do not add peripherals or architectural features merely because other SoCs contain them.

Possible later additions should be workload- or board-driven, for example:

- SD/eMMC;
- SPI/QSPI;
- Ethernet;
- DMA;
- additional interrupt sources;
- multi-hart support;
- debug/JTAG;
- richer boot media.

These belong to later platform revisions, not AetherSoC v0 release closure.

## Repository policy

After v0 promotion:

- `main` is the single canonical product branch;
- feature/performance/board work branches from `main`;
- completed work returns to `main` promptly;
- milestone PRs/tags preserve history;
- long-lived product branches must not diverge thousands of commits ahead of `main` again.
