# AetherSoC v0 checkpoint

## Current freeze line

```text
product branch: soc/aethercore-v0-icache
freeze branch:  release/aethersoc-v0-freeze
product baseline entering freeze: e9d01f403e78bfb3785515a80d07d7f50dd5dc06
```

The authoritative detailed evidence record is `docs/AETHERSOC_V0_FREEZE.md`.

Historical Supervisor V1 and Sv32 V2 checkpoints remain regression references, but they no longer describe the current product boundary.

## Architecture now qualified

The current board-less AetherSoC v0 line covers:

- RV64IMA execution with M/S/U privilege;
- supervisor delegation, SRET and WFI;
- Sv39 virtual memory;
- `satp`, TLBs, page-table walker and `SFENCE.VMA`;
- PMP/PMA ownership;
- I-cache and D-cache;
- concurrent Data read lifetimes through the memory path;
- BootROM;
- PlatformFabric and MemoryHub;
- ns16550-compatible UART;
- PLIC supervisor external interrupt delivery;
- ACLINT-style MTIMER/timebase delivery;
- AXI4 external-memory boundary;
- DTS generation;
- board-neutral FPGA-facing SoC top;
- synthesizable 8N1 UART PHY;
- virtual FPGA board that exercises production AXI and serial pin boundaries.

Earlier RV32/RV32C/Sv32, FreeRTOS, Zephyr and NuttX profiles remain active regression ladders.

## Real software qualification

AetherSoC v0 has passed real firmware and Linux execution:

### AXI Linux clocksource

```text
OpenSBI v1.6
Linux 6.6.143
clocksource: riscv_clocksource
cycles=62047788
commits=18038710
```

The same qualification observed 1,289,056 Data AXI requests and responses plus 123,939 same-source overlap-issue events.

### Virtual FPGA pin-level PID1

Exact qualification head:

```text
d47c0e504789ce4abcc58877327e73e0724f2059
workflow run 33244119745
```

Observed:

```text
OpenSBI v1.6
Linux 6.6.143
RV64 USER UART IRQ OK
cycles=421518021
commits=135262086
interrupts=8391
stip=4195
seip=1
```

This path crosses the production FPGA-facing AXI4 and serial-pin boundaries and is the final board-less functional acceptance line for v0.

## FPGA synthesis

The production `AetherCoreV2FpgaSoC` top has already completed the board-neutral Yosys ECP5 synthesis proxy once. The freeze branch is rerunning the same flow on the latest product generation before final resource numbers are frozen.

The proxy proves structural synthesizability and reports mapped resource counts and topological depth. It does not claim a board Fmax.

## Repository status

The default `main` branch is currently stale and still represents the early CPU-only generation. PR #253 is the planned v0 promotion.

Promotion sequence:

1. complete the latest-head synthesis run on `release/aethersoc-v0-freeze`;
2. record exact final synthesis figures in `docs/AETHERSOC_V0_FREEZE.md`;
3. merge the freeze branch into `soc/aethercore-v0-icache`;
4. promote that frozen product line to `main` with PR #253.

After promotion, `main` is the single canonical product line.

## Next checkpoint after v0

After v0 freezes, work is no longer classified as "missing basic SoC functionality".

The next bounded lines are:

- concrete FPGA-board bring-up: PLL, DDR/controller, pin constraints, place-and-route and bitstream;
- timing/resource closure on the selected device;
- optional performance work driven by measured Linux bottlenecks;
- later platform expansion only when a real workload or board requires it.

Do not reopen AetherSoC v0 simply to chase unbounded performance improvements.
