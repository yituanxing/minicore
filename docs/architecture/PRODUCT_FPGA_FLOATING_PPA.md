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
- `ProductSmallPagedCore` still rejects F/D. This experiment cannot run
  OpenSBI or Debian, nor qualify supervisor FS context switching.
- `synth_ecp5` and `ltp -noff` are a board-neutral **area/depth proxy**, not
  routed device Fmax, timing closure or physical FPGA DDR/PLL feasibility.
- The floating RTL uses `io.github.ucb-substrate::hardfloat:0.1.1` with a
  pinned Chisel 7.8.0 toolchain. Before distributing netlists/bitstreams,
  review and preserve the per-source HardFloat copyright/license notices;
  the published Maven POM license label alone is not a sufficient audit.
- This opt-in test deliberately does not run on every PR push: synthesis is
  expensive, and broad architecture batches should trigger it when meaningful.
