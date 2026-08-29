# AetherSoC v0 checkpoint

## Current freeze line

```text
canonical branch: main
v0 promotion merge: f67838cc417a12c341fc56394e37aead3aa61295
freeze merge into product line: a502a71df7aac98d3997615e71b29661939ebb5e
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

The production `AetherCoreV2FpgaSoC` top completed the final board-neutral Yosys ECP5 freeze run `33250167081`.

Frozen mapped proxy:
- 96,718 LUT4
- 18,705 TRELLIS_FF
- 374 TRELLIS_DPR16X4
- 39 MULT18X18D

The proxy proves structural synthesizability. It does not claim a board Fmax.

## Repository status

AetherSoC v0 release closure is complete.

- #254 merged the freeze record into the product line as `a502a71df7aac98d3997615e71b29661939ebb5e`.
- #253 promoted that frozen product line to `main` as `f67838cc417a12c341fc56394e37aead3aa61295`.
- `main` is now the single canonical product line; the old CPU-only default state is no longer authoritative.

## Next checkpoint after v0

After v0 freezes, work is no longer classified as "missing basic SoC functionality".

The next bounded lines are:

- concrete FPGA-board bring-up: PLL, DDR/controller, pin constraints, place-and-route and bitstream;
- timing/resource closure on the selected device;
- optional performance work driven by measured Linux bottlenecks;
- later platform expansion only when a real workload or board requires it.

Do not reopen AetherSoC v0 simply to chase unbounded performance improvements.
