# Product FPGA floating-point incremental cost

The product goal is one configurable, FPGA-feasible SmallOoO RV64GC/LP64D
processor able to run an upstream Debian riscv64 distribution on a physical
board. The hardware implementation is **not** yet qualified for that target.

This experiment compares the **same single-wide `ProductSmallMemoryCore`**
between two architectural switches. It measures the combined cost of FPR
rename/PRF, FP IQ dependencies, bit-level FP operations, HardFloat IEEE
arithmetic (including iterative division/square-root) and completion routing.
It does not compare unrelated two-wide pipelines or larger ROBs.

| Setting | Geometry | ISA request | Physical address | Meaning |
| --- | --- | --- | --- | --- |
| `small integer` | ROB8/IQ4/PRF48 | RV64IMA + LP64 | PA32 | Existing non-F core slice |
| `small floating` | ROB8/IQ4/PRF48/FPR48 | RV64GC + LP64D | PA32 | Partial F/D core slice |
| `expanded integer` | ROB16/IQ8/PRF64 | RV64IMA + LP64 | PA32 | Larger one-wide non-F slice |
| `expanded floating` | ROB16/IQ8/PRF64/FPR64 | RV64GC + LP64D | PA32 | Partial larger one-wide F/D slice |

Run one capacity **explicitly** after installing Mill/JDK, Yosys with
`synth_ecp5`, `jq` and Python 3:

```sh
./tools/compare_product_fpga_floating.sh small /tmp/product-fp-cost
./tools/compare_product_fpga_floating.sh expanded /tmp/product-fp-cost
```

Each invocation elaborates `ElaborateProductFloatingSlice` twice and emits
`<output>/<geometry>/<integer|floating>/metrics.json`, Yosys logs, and a
`summary.json` containing absolute LUT4, FF, BRAM, distributed RAM, DSP and
longest logic-path **proxy** delta. Neither mode changes issue/retire width.
The existing Product FPGA SoC Small/Expanded A/B job is unaffected and remains
the representative non-F full-board-shell comparison.

Important qualification limits:

- F/D-on contains HardFloat-backed arithmetic and conversions as well as the
  real optional FPR/ROB/LSQ path. Having synthesizable IP is **not** proof of
  ISA-complete numerical behavior: rounding, NaN, exceptions and recovery
  still require independent directed and differential verification.
- `ProductSmallPagedCore` now elaborates an opt-in RV64GC/LP64D configuration,
  but this **isolated core measurement** never executes OpenSBI or Debian and
  cannot qualify supervisor FS context switching or real hardware.
- `synth_ecp5` and `ltp -noff` are a board-neutral **area/depth proxy**, not
  routed device Fmax, timing closure or physical FPGA DDR/PLL feasibility.
- The floating RTL uses `io.github.ucb-substrate::hardfloat:0.1.1` with a
  pinned Chisel 7.8.0 toolchain. Before distributing netlists/bitstreams,
  review and preserve the per-source HardFloat copyright/license notices;
  the published Maven POM license label alone is not a sufficient audit.
- This opt-in test deliberately does not run on every PR push: synthesis is
  expensive, and broad architecture batches should trigger it when meaningful.

## Whole-SoC physical pin versus verification-probe boundary

The existing `AetherCoreProductFpgaSoC` exports large architectural CommitTrace,
retirement and profiling buses for exact simulation. Yosys must preserve
observable logic on those top-level ports, so its LUT count is **not** a
credible estimate of a production board with only AXI and UART pins. Do not
silently remove these buses from the verification top: retire-by-retire
correctness and the historical Linux regressions still need them.

`AetherCoreProductFpgaPins` is a separate top **wrapping the exact same
Product SoC**, using the same selected CPU/PA32/AXI/BootROM/interrupt
configuration. Only the external observation boundary differs. Its board
interface retains the AXI master, serial RX/TX, UART clock tick and timebase
tick. Physical DDR/PLL/reset constraints and measurable Fmax remain pending.

An explicit, expensive four-netlist measurement is available for each
implemented one-wide capacity:

```sh
bash tools/compare_product_fpga_pin_cost.sh small /tmp/product-full-fpga
bash tools/compare_product_fpga_pin_cost.sh expanded /tmp/product-full-fpga
```

The GitHub workflow `product-fpga-physical-pin-ppa.yml` automatically measures
**Small only when this physical boundary or measurement recipe changes** on a
PR. Once available on the default branch, `workflow_dispatch` can select
Small or Expanded independently. Each capacity synthesizes all four **full
SoC** combinations:

| ISA/ABI | Verification probe top | Physical pin-only top |
| --- | --- | --- |
| RV64IMAC / LP64 | original, instrumented reference | same hardware without external probes |
| RV64GC / LP64D | HardFloat + probes | HardFloat + physical pins |

The summary gives two **separate** effects: the probe-port overhead for each
ISA, and F/D-on minus F/D-off for each boundary. Never mix these with the
isolated `ProductSmallMemoryCore` F/D numbers or the earlier 70,277/90,080 LUT4
probe-top geometry comparison. LUT4, FF, DPR, BRAM, DSP and `ltp` are
unrouted ECP5 synthesis proxies, **not** a board timing, DDR, Linux, IEEE
instruction-differential or Debian acceptance test.
