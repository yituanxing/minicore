# Product Small RV64GC — first real XC7 family mapping

**Evidence:** [Actions run #42206203778](https://github.com/yituanxing/minicore/actions/runs/42206203778),
source commit [a0e29cb](https://github.com/yituanxing/minicore/commit/a0e29cb4268fe8e85c9c230bf66a7d468e07e0ae),
[raw log artifact](https://github.com/yituanxing/minicore/actions/runs/42206203778/artifacts/13497715006),
122 pinned Product SystemVerilog synthesis inputs.

## What completed, and why CI was red

The real Yosys Xilinx 7-series `synth_xilinx -family xc7 -noiopad`
completed on `AetherCoreProductFpgaUartDdrBoard` (Product Small RV64GC,
LP64D, HardFloat), printed full `=== design hierarchy ===` statistics
and completed `CHECK` with **0 problems**. The CI result is nevertheless
**failure** (`YOSYS_EXIT_1`) because the subsequent `tee -o` command
passed literal double quotes around the requested `stats.json` pathname
and Yosys could not create that separate file. This is a post-mapping
report-export bug, not proof of device fit. The command is corrected in
the following commit; the original run stays red.

These are the actual final **hierarchy-wide technology-mapped** counts
extracted from the archived Yosys log, not estimated RTL complexity,
RV64IMAC/ECP5 proxy data or Vivado device-fitted results.

| Xilinx primitive | Count |
| --- | ---: |
| LUT1 | 2,407 |
| LUT2 | 12,182 |
| LUT3 | 15,851 |
| LUT4 | 9,185 |
| LUT5 | 7,288 |
| LUT6 | 26,193 |
| **All LUT1–LUT6** | **73,106** |
| FDCE | 6 |
| FDRE | 21,679 |
| FDSE | 377 |
| **All FF** | **22,062** |
| DSP48E1 | 95 |
| CARRY4 | 3,431 |
| MUXF7 | 10,280 |
| MUXF8 | 2,459 |
| RAM32M | 106 |
| RAM64M | 295 |
| BUFG | 1 |
| **All primitive cells** | **112,930** |
| Yosys estimated logic cells | 58,517 |

The primitive categories sum to exactly 112,930. The final hierarchy
contains the Product floating-point unit, SmallOoO backend and memory
subsystem. RAM32M/RAM64M are **distributed-memory primitives**, and
this summary contains no RAMB18E1/RAMB36E1. Neither these counts nor
the zero BRAM number qualify physical DDR support.

## Instance-weighted module hotspots (same real log)

These five disjoint functional subtrees account for 52,608 / 73,106 LUTs
(72.0%). Each subtree includes its descendants, rather than just its local
module cell count. Do not add these numbers to parent hierarchy rows.

| Distinct hardware area | LUT | FF | DSP48E1 | % of total LUT |
| --- | ---: | ---: | ---: | ---: |
| ProductFpUnit / HardFloat | 18,106 | 713 | 14 | 24.8% |
| ProductSmallOoOBackend | 12,864 | 7,719 | 0 | 17.6% |
| PMP check and geometry (all instances) | 12,784 | 0 | 0 | 17.5% |
| ProductPhysicalLsq | 4,905 | 1,638 | 0 | 6.7% |
| ProductMUnit (integer mul/div) | 3,949 | 496 | 81 | 5.4% |
| **Five disjoint areas** | **52,608** | — | **95** | **72.0%** |

PMP breaks down into two `PmpRangeDecoder` instances at 3,358 LUT
each and four `PmpAccessChecker` instances at 1,517 LUT each. This
looks like duplication but is **not safely removable**: the instruction
side in `ProductSmallPagedCore` sees current PMP CSRs, whereas the
data side in `ProductDataResolver` selects a PMP snapshot captured
when a memory uOp was issued. Combining them would change the permissions
seen by an in-flight operation after a CSR change and violate existing
`ProductDataResolverSpec` snapshot tests. Optimize NAPOT/TOR wide
arithmetic only behind equivalence tests; do **not** combine these two
state lifetimes merely to reduce the mapped gate count.

For contrast, the instruction and data cache subtrees consume only about
479 and 877 LUTs respectively. The first area investigation should
therefore be PMP, then OoO backend and HardFloat, not an arbitrary cache
redesign. Preserve RV64GC/Sv39/precise protection for Linux correctness.

The reusable `tools/ci/product_fpga_xc7_hierarchy.py` parser checks that
every module-instance-weighted primitive cell matches Yosys's total.
On future CI runs it emits `hierarchy.json` and a concise resource line
even if a *later* Yosys statistics-export step fails. Its
`XC7_MAPPED_HIERARCHY_PARSED` status **never** means a CI or board PASS.

## Candidate screening only

AMD [UG474 Artix-7 CLB table](https://docs.amd.com/r/en-US/ug474_7Series_CLB/7-Series-FPGA-CLB-Resources)
lists **63,400 LUTs** for XC7A100T and **134,600 LUTs** for XC7A200T.
Our current 73,106 mapped LUTs are 9,706 (15.3%) above the former,
even before adding real DDR/board IP. They are 54.3% of the latter.
This suggests XC7A200T is a better device-specific synthesis candidate,
but **does not** establish fit, routing, timing, board memory or Linux.

## Provenance and next steps

The exact-SHA full Product export and cache job
[#42205915853](https://github.com/yituanxing/minicore/actions/runs/42205915853)
passed before this run. The XC7 mapper restored all that RTL without
re-elaborating Chisel. Its last sampled Yosys main-process RSS was
approximately 1.67 GiB at 288 seconds; that sample is not peak whole-job
memory. A corrected subsequent mapping should write machine-readable
`stats.json` and only report `XC7_UNPLACED_MAPPING_PASS` when Yosys
exits normally and reports nonzero mapped cells. We should **not**
spend another five-minute synthesis merely to recolor this historical run.
