# AetherSoC v0 Freeze

Status: board-less AetherSoC v0 qualification complete; promotion to `main` pending.

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

The final freeze synthesis ran on exact freeze head `ba5f110a8fab05824f45df1fc4d67107a46e0283`.

Workflow run: `33250167081` — SUCCESS.

Mapped ECP5 proxy result:

- cells: 170,143
- LUT4: 96,718
- TRELLIS_FF: 18,705
- TRELLIS_DPR16X4: 374
- DP16KD: 0
- MULT18X18D: 39
- structural `ltp -noff` depth: 6,667

The workflow summary currently reports distributed RAM as zero because the reporting script searches for `DPR16X4`; the actual mapped ECP5 primitive name in the Yosys `stat` output is `TRELLIS_DPR16X4`. The authoritative mapped count for this freeze is therefore 374.

The `ltp -noff` output also emits combinational-loop warnings around mapped sequential/memory feedback structures, so the scalar depth value is retained only as a structural diagnostic. It is **not** an FPGA Fmax estimate.

No FPGA Fmax or board frequency claim is made by this proxy. That requires a concrete device, constraints, vendor DDR/PLL integration and place-and-route.

## Repository governance closure

With exact-head synthesis and this freeze record complete:

1. merge this freeze branch into `soc/aethercore-v0-icache`;
2. promote the frozen `soc/aethercore-v0-icache` line to `main` via PR #253;
3. treat `main` as the single canonical product line;
4. retain historical milestone PRs/tags as evidence rather than as competing product branches.

After that promotion, new performance or board-specific work must branch from `main` and return to `main` promptly.
