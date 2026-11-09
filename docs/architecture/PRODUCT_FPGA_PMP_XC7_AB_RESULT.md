# Product PMP decoder: isolated XC7 A/B result (2026-11-06)

This is an **isolated, unplaced synthesis measurement** of the product PMP
geometry decoder, not an FPGA device fit, board validation, or timing result.

## Exact comparison

Both designs use `PmpRangeDecoder(64, 16, 56)`, the same Chisel elaboration entry
point (`ElaboratePmpRangeDecoderRV64`), and the identical pinned
`yowasp-yosys==0.70.0.0.post1259` Xilinx 7-series mapping command:

```text
read_verilog -sv -DSYNTHESIS rtl/PmpRangeDecoder.sv
hierarchy -check -top PmpRangeDecoder
synth_xilinx -family xc7 -noiopad -top PmpRangeDecoder
stat -tech xilinx
```

| Isolated decoder | Pre-optimization | Optimized | Difference |
| --- | ---: | ---: | ---: |
| LUT2..LUT6 total | 4,651 | 4,011 | **-640 (-13.8%)** |
| Estimated logic cells | 3,671 | 3,153 | **-518 (-14.1%)** |
| Yosys structural check problems | 0 | 0 | 0 |

The only production PMP decoder change is the `active` predicate: TOR compares
encoded addresses directly, NA4 and NAPOT are intrinsically nonempty, and OFF
is inactive. The old generic `upper > lower` comparison is no longer needed
for each entry. The baseline is commit
`a15197f96e5869e69269202a0a9caffdbe1f5255`.

Evidence:

- [Successful isolated A/B run #42216239075](https://github.com/yituanxing/minicore/actions/runs/42216239075),
  artifact `product-pmp-local-xc7-42216239075-1` contains both elaborated SV
  files, both Yosys logs, and `lut-comparison.txt`.
- [Focused correctness run #42211270907](https://github.com/yituanxing/minicore/actions/runs/42211270907):
  28/28 tests passed across PMP geometry, permissions, RV64 CSR and data-side
  in-flight PMP snapshots. The additional PA56 boundary cases are included.

## Integration limits and next gate

Product currently has **two intentionally separate range decoders**: one sees
live PMP CSRs for instruction requests, the other sees the PMP snapshot owned
by an in-flight data request. Sharing these instances would break the
precise-privilege snapshot contract. This local reduction applies to the logic
of each decoder, but **do not multiply the local LUT delta and report it as a
measured full-SoC saving**: synthesis across parents can change mapping.

The earlier whole-SoC native-Yosys report of 3,358 LUT per decoder and this
YoWASP standalone total of 4,011 LUT are **not comparable** because of
different synthesis contexts and tool versions. Full Product XC7 mapping is
reserved for a later combined checkpoint. Device-specific Vivado OOC results,
placement, timing and DDR integration remain unverified.

The next local area candidate is `PmpAccessChecker`'s priority selection
network. Keep all four comparisons per entry unless a separately proven
equivalence and old/new XC7 map justify a change; preserve lowest-index PMP
priority, partial-overlap denial and the data-side snapshot lifecycle.

## 2026-11-09: first-hit priority A/B is a negative area result

The first-hit one-hot alternative has now been evaluated **in isolation**, using
`PmpAccessChecker(16, 56)`. The original reversed `when` priority chain and the
experimental `PriorityEncoderOH`/`OHToUInt` network were elaborated with the
same FIRRTL lowering settings and mapped with the same
`yowasp-yosys==0.70.0.0.post1259` XC7 flow.

| Independent PA56/PMP16 checker | LUT1..LUT6 | Compared with original |
| --- | ---: | ---: |
| Original first-matching priority chain, source `5166bfcc144d7419e1c4eeb354237a457b13680c` | 2,583 | baseline |
| One-hot first-hit alternative | 2,611 | **+28 (+1.08%)** |

These numbers were reproduced in a **local standalone** mapping, not yet a
published CI run or full SoC implementation. The candidate is an **area
regression** and is **not enabled in Product**; the constructor defaults to the
original chain. `ElaboratePmpAccessCheckerOneHotRV64` alone opts in, while
`ElaboratePmpAccessCheckerRV64` emits the production-default baseline. The
opt-in GitHub workflow records both SystemVerilog files, both Yosys logs and a
`checker-lut-comparison.txt` when invoked; it is not triggered by normal PR
updates. PA8 and PA56 random-priority reference tests are wired for both modes;
local Scala compilation and static contracts succeeded, **but the dynamic
ChiselSim checks have not been run locally** because Verilator is unavailable.

These standalone checks cannot be multiplied by the four Product access lanes
to assert a whole-SoC LUT delta, nor can they establish placed-and-routed Fmax.
In particular, the `matchedEntry` output of the isolated checker may be
optimized differently when connected to the actual CPU.

The next structural candidate is to compare a CSR-update-time PMP geometry
cache against the existing combinational range decoding, following the
FPGA-oriented VexRiscv PMP approach. It must retain **full TOR/NA4/NAPOT,
16 entries, PA56, locked M-mode checks and distinct live-CSR / in-flight
snapshot ranges**. No PMP mode or security check may be removed merely to
improve LUT counts.
