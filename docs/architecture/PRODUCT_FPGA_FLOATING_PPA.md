# Product FPGA floating-point incremental cost

The product goal is one configurable, FPGA-feasible SmallOoO RV64GC/LP64D
processor able to run an upstream Debian riscv64 distribution on a physical
board. The hardware implementation is **not** yet qualified for that target.

This experiment compares the **same single-wide `ProductSmallMemoryCore`**
between two architectural switches. It isolates the cost of newly added FPR
rename/PRF, FP IQ dependencies, one FP bit-level FU and FP completion routing.
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

- F/D-on uses the real optional FPR/ROB/LSQ path, but the FPU currently
  implements only sign/minmax/compare/classify/move operations. General
  IEEE-754 arithmetic remains *precisely illegal* pending qualified IP.
- `ProductSmallPagedCore` still rejects F/D. This experiment cannot run
  OpenSBI or Debian, nor qualify supervisor FS context switching.
- `synth_ecp5` and `ltp -noff` are a board-neutral **area/depth proxy**, not
  routed device Fmax, timing closure or physical FPGA DDR/PLL feasibility.
- This opt-in test deliberately does not run on every PR push: synthesis is
  expensive, and broad architecture batches should trigger it when meaningful.
