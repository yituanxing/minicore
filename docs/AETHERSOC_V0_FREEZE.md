# AetherSoC v0 Freeze

Status: final board-less freeze qualification in progress.

## Canonical product baseline

- Product branch before freeze: `soc/aethercore-v0-icache`
- Exact baseline entering freeze: `e9d01f403e78bfb3785515a80d07d7f50dd5dc06`
- Default `main` is intentionally not promoted until this freeze closes.

## Functional architecture frozen for v0

- RV64IMA privileged core with M/S/U execution
- Sv39 translation, TLB, PTW and SFENCE.VMA
- PMP/PMA ownership
- I-cache and D-cache
- concurrent Data read lifetimes through the platform/AXI path
- BootROM
- PlatformFabric and MemoryHub
- ns16550-compatible UART
- physical 8N1 UART PHY
- PLIC supervisor external interrupt path
- ACLINT-style MTIMER/timebase path
- DTS generation
- AXI4 external-memory boundary
- board-neutral FPGA-facing top
- virtual FPGA board with pin-level serial and AXI DDR model

## Frozen runtime evidence

### AXI Linux clocksource

Exact qualification head: `70ef5ef16660cb46f118bca417e006ee5c0f11d8`

- OpenSBI v1.6
- Linux 6.6.143
- `clocksource: riscv_clocksource`
- cycles: 62,047,788
- retired commits: 18,038,710
- Data AXI requests/responses: 1,289,056 / 1,289,056
- same-source overlap issue events: 123,939

### Virtual FPGA pin-level PID1

Exact qualification head: `d47c0e504789ce4abcc58877327e73e0724f2059`
Workflow run: `33244119745`

The workload crosses the production FPGA-facing AXI4 and serial-pin boundaries.

Observed:

- OpenSBI v1.6
- Linux 6.6.143
- `RV64 USER UART IRQ OK`
- cycles: 421,518,021
- retired commits: 135,262,086
- interrupts: 8,391
- STIP: 4,195
- SEIP: 1
- runtime milestone: PASS

This is the final functional acceptance line for board-less AetherSoC v0.

## FPGA synthesis evidence

The synthesis methodology is:

- production top: `AetherCoreV2FpgaSoC`
- Yosys `synth_ecp5`
- mapped structural resource counts
- flattened `ltp -noff` topological depth

The first successful synthesis-proxy run (`33242719855`) on the pre-freeze synthesis branch reported:

- cells: 170,235
- LUT4: 96,076
- TRELLIS_FF: 18,624
- DP16KD: 0
- DPR16X4: 0
- MULT18X18D: 39
- structural logic depth: 7,014

These values are **not yet the final frozen resource baseline**, because that run predates later production memory-path changes. The freeze branch intentionally reruns the same synthesis methodology on the exact current product baseline. This section must be updated with that exact-head result before promotion to `main`.

No FPGA Fmax or board frequency claim is made by this proxy. That requires a concrete device, constraints, vendor DDR/PLL integration and place-and-route.

## Repository governance closure

After the exact-head synthesis rerun succeeds and this document is updated:

1. merge the freeze branch into `soc/aethercore-v0-icache`;
2. promote `soc/aethercore-v0-icache` to `main` via PR #253;
3. treat `main` as the single canonical product line;
4. retain historical milestone PRs/tags as evidence rather than as competing product branches.

After that promotion, new performance or board-specific work must branch from `main` and return to `main` promptly.
