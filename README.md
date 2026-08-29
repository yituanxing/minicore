# MiniCore / AetherCore

A correctness-first RISC-V CPU and SoC project written in Chisel and driven by executable software, firmware and real operating-system workloads.

The current product line is **AetherCore V2 + AetherSoC v0**. Board-less v0 is frozen and the frozen product line is now the default `main` branch.

## Current product architecture

```text
AetherCore V2
  -> RV64IMA + M/S/U
  -> Sv39 + TLB + PTW + SFENCE.VMA
  -> PMP/PMA
  -> I-cache / D-cache
  -> PlatformFabric
  -> BootROM
  -> PLIC
  -> ACLINT-style MTIMER
  -> ns16550-compatible UART
  -> MemoryHub
  -> AXI4 external-memory boundary
  -> FPGA-facing SoC top
  -> 8N1 serial PHY
```

The historical five-stage `AetherCore.scala` implementation remains in the repository as an earlier architectural generation and regression source. Current Linux/SoC qualification uses the V2 CPU-complex and SoC path under `src/main/scala/aethercore/core/v2` and `src/main/scala/aethercore/soc`.

## Qualified ISA and privileged execution

The current RV64 Linux profile includes:

- RV64I and RV64M integer execution;
- RV64A LR/SC and AMO operations;
- M/S/U privilege modes;
- Zicsr and system-instruction handling;
- precise synchronous traps and interrupt retirement boundaries;
- supervisor delegation and SRET;
- WFI;
- Sv39 address translation;
- `satp`, TLBs, page-table walking and `SFENCE.VMA`;
- PMP/PMA checks;
- supervisor timer and external-interrupt delivery.

Earlier RV32/RV32C/Sv32/NuttX/FreeRTOS/Zephyr profiles remain as independent regression ladders.

## AetherSoC v0

The SoC implementation is under `src/main/scala/aethercore/soc`.

Major blocks include:

- `AetherCoreV2Complex` CPU-complex boundary;
- `AetherSoCPlatformFabric` address decode and peripheral ownership;
- `AetherSoCMemoryHub` multi-client memory routing;
- `AetherSoCBootRom`;
- `AetherUart16550`;
- `AetherPlic`;
- `AetherAclintMtimer`;
- I-cache and D-cache;
- AetherMem semantic memory links;
- AXI4 external-memory bridge and top;
- DTS generation;
- `AetherCoreV2FpgaSoC` board-neutral FPGA-facing top;
- synthesizable 8N1 UART PHY;
- virtual FPGA board used to qualify real AXI and serial pin boundaries.

Board-specific PLLs, DDR controller/IP and pin constraints deliberately remain outside the logical SoC and are owned by the future concrete FPGA-board integration.

## Linux and OpenSBI qualification

AetherSoC v0 is already beyond first-boot smoke testing.

Qualified software includes:

- OpenSBI v1.6;
- Linux 6.6.143;
- real Sv39/S-mode Linux execution;
- deterministic initramfs/PID1 qualification;
- supervisor timer interrupts;
- supervisor external interrupts through PLIC/UART.

### AXI Linux clocksource baseline

A qualified AXI4 Linux run reached:

```text
clocksource: riscv_clocksource
62,047,788 cycles
18,038,710 retired commits
```

The same run observed 1,289,056 Data AXI requests and responses and 123,939 same-source overlap-issue events.

### Virtual FPGA pin-level PID1

The final board-less functional acceptance path instantiates the production FPGA-facing SoC. Memory traffic crosses the production AXI4 pins into a virtual DDR target; console traffic crosses the production ns16550, 8N1 serializer and serial pins before the host runner observes it.

Qualified milestone:

```text
OpenSBI v1.6
Linux 6.6.143
RV64 USER UART IRQ OK

421,518,021 cycles
135,262,086 retired commits
8,391 interrupts
4,195 STIP
1 SEIP
```

This is the functional stop line for AetherSoC v0 without a concrete FPGA board.

## FPGA synthesis status

The production top `AetherCoreV2FpgaSoC` is elaborated and mapped with a board-neutral Yosys ECP5 synthesis proxy.

The final v0 freeze proxy run (`33250167081`) reported:

```text
cells             170,143
LUT4               96,718
TRELLIS_FF         18,705
TRELLIS_DPR16X4       374
MULT18X18D             39
DP16KD                  0
structural depth     6,667
```

The distributed-RAM primitive count above is taken from the authoritative Yosys `stat` output. The current JSON-summary helper has a naming bug that searches for `DPR16X4` instead of the mapped ECP5 primitive `TRELLIS_DPR16X4` and therefore reports zero there.

These numbers are structural synthesis evidence only. They are **not an FPGA Fmax claim**. A real frequency requires a concrete device, clock constraints, board-specific PLL/DDR integration and place-and-route.

## Verification philosophy

AetherCore is developed by increasing software pressure rather than implementing ISA or SoC features speculatively:

```text
directed programs
  -> compiler-produced programs
  -> CoreMark / Embench / littlefs
  -> FreeRTOS / Zephyr / NuttX
  -> M/S/U privilege
  -> paging
  -> OpenSBI
  -> Linux
  -> Linux PID1 and interrupt paths
  -> FPGA-facing pin-level qualification
```

Failures from broad workloads are reduced into focused permanent regressions before the architecture is widened.

The project also uses pinned NEMU references for deterministic retirement-level differential testing where applicable.

## Repository layout

```text
src/main/scala/aethercore/
  core/          legacy and shared architectural blocks
  core/v2/       current V2 CPU microarchitecture
  memory/        semantic memory/cache structures
  soc/           current AetherSoC implementation
  sim/           simulation-only compatibility and virtual-board wrappers

software/        firmware, OS and workload sources
sim/             Verilator/NEMU host runners
tests_py/        structural and workflow contracts
docs/            architecture, freeze and qualification records
.github/workflows/
                 executable qualification gates
```

## Build

Core verification prerequisites include Java, C++, Python 3, Verilator and the pinned RISC-V toolchains used by each qualification path.

Representative commands:

```bash
chmod +x mill
make python-test
make test
make rtl
make run-smoke

./mill aethercore.runMain aethercore.ElaborateV2OpenSbiRV64
./mill aethercore.runMain aethercore.ElaborateV2FpgaSoCRV64
```

Linux/OpenSBI and FPGA qualification are encoded as reproducible GitHub Actions workflows rather than as README-only claims.

## Freeze and branch policy

A feature is considered frozen only when its focused qualification and relevant regression gates pass on an explicitly recorded source head.

For AetherSoC v0, the authoritative freeze record is:

[`docs/AETHERSOC_V0_FREEZE.md`](docs/AETHERSOC_V0_FREEZE.md)

After v0 promotion, the default `main` branch is the single canonical product line. New performance, architecture or board work should branch from `main` and return to `main` promptly. Historical milestone branches and PRs remain evidence, not alternate definitions of the current product.
