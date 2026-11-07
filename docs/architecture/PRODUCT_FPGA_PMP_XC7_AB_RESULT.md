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
