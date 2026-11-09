# Product RV64GC SmallOoO cache-capacity A/B — independent experiment

## Scope

Current Product UnifiedMemorySoC caches are each `64 * 8B = 512B` with
same-cycle direct-mapped reads. This branch introduces **only an opt-in SoC
cache-size parameter**, not a new cache implementation. The current SD1
production emitter and its 64/64 default are untouched; previous longrun
checkpoints **must not** be reused by the changed RTL. ISA, geometry, AXI,
physical DDR aperture, SPI, firmware and device tree are unchanged.

| A/B case | I entries | D entries | Nominal I/D data | Status |
| --- | ---: | ---: | ---: | --- |
| P0 | 64 | 64 | 512B / 512B | Existing design baseline, not newly requalified |
| C1 | 256 | 256 | 2KiB / 2KiB | Opt-in, not yet performance qualified |
| C2 | 512 | 512 | 4KiB / 4KiB | Opt-in, not yet performance qualified |

Generate a complete board-neutral RTL candidate **to a separate directory**:

```sh
sh ./mill aethercore.runMain \
  aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1CacheAbRv64gc \
  small 256 256 --target-dir build/cache-ab-256

sh ./mill aethercore.runMain \
  aethercore.soc.ElaborateProductFpgaSoCRv64gcCacheAb \
  small 512 512 --target-dir build/cache-ab-instrumented-512
```

The second path retains `icacheHitCount/icacheMissCount`,
`dcacheHitCount/dcacheMissCount/dcacheBypassCount` so a short, controlled
Linux or firmware test can record actual cache effects. Inputs and reported
source SHA/RTL SHA must be pinned separately for each emitted case.

## Reproducible local preflight (2026-11-09)

Commands:

```sh
sh ./mill aethercore.compile
python3 -m unittest tests_py.test_product_cache_capacity_ab \
  tests_py.test_product_debian_boot_config \
  tests_py.test_product_sd1_official_media_cache_contract \
  tests_py.test_product_sd1_spi_polling_dts -v
sh ./mill aethercore.test.testOnly \
  aethercore.AetherSoCInstructionCacheSpec \
  aethercore.AetherDirectMappedReadCacheSpec
```

Observed: main Scala compiled; 16 Python contract tests passed; Scala test
sources compiled, but **all six simulation cases could not execute** because
this machine lacks `verilator` (including the four pre-existing cases).
The two added simulation cases exercise all three sizes separately, but their
functional behavior remains **unverified** until rerun on a simulator-equipped
runner.

Independent SystemVerilog elaboration succeeded for the original SD1 64/64
emitter, the full UART-DDR-SPI-SD 256/256 A/B emitter, and the instrumented
256/256 and 512/512 emitters. Generated `lineValid` declarations were 64,
256 and 512 per I/D-cache, respectively. This establishes geometry plumbing,
**not** timing closure, cache hit-rate improvement or a Debian boot pass.
Vivado is also unavailable locally, so FPGA area and routed Fmax are pending.

## Real Debian RAM short-prefix A/B (separate from SD1)

The existing Debian Verilator top previously always selected its default
64/64 cache, even when an alternative FPGA shell had been emitted. The
opt-in `AetherCoreProductDebianCacheAbRV64SimTop` now forwards the same cache
profile into the actual Debian RV64GC unified-memory CPU/SoC and exposes both
I-cache counters alongside the existing D-cache counters. Its C++ header
alias is selected only with `-DAETHERCORE_PRODUCT_CACHE_AB`; the original
G5/SD1 host top and their Verilator pin ABI remain unchanged.

On a single host with the pinned Verilator 5.054 and a validated real Debian
RV64GC `fw_payload.bin`, after committing the source under test:

```sh
bash tools/ci/product_cache_capacity_short_ab.sh preflight
bash tools/ci/product_cache_capacity_short_ab.sh build
AETHERCORE_ALLOW_PRODUCT_CACHE_AB=1 \
  bash tools/ci/product_cache_capacity_short_ab.sh run
```

The independent harness generates and caches 64/64 and 256/256 models by
default; after that comparison is useful, explicitly request
`AETHERCORE_CACHE_AB_PROFILES='64 256 512'` to include the more expensive
512/512 model. This avoids three native Product models consuming an entire CI
runner before any performance evidence is obtained. Each profile has its own
directory and existing `Makefile.l32-linux-boot` / content-addressed
`product_sim_cache.sh` identity. Default is only 5M cycles per model
(configurable, capped at 30M) with a wall-time bound. Runtime emits
`PRODUCT_CACHE_AB_SNAPSHOT` with cycle/commit and I/D hit/miss/bypass counts.
All selected models share the same firmware, source SHA, reference settle mode
and pinned toolchain. It refuses stale/partial model directories, mutated
firmware and a dirty source worktree. The optional `AETHERCORE_CACHE_AB_MILESTONE`
requires every selected profile to reach the same **real UART milestone**; otherwise it
reports only fixed-cycle prefix statistics. Different cache capacities must
not be compared by identical cycle-by-cycle architectural snapshots: they
legitimately change the timing of execution.

Local preflight of this RAM path: `aethercore.compile` succeeded, the
256/256 Debian CacheAb SimTop emitted successfully with both I-cache counter
pins and 256 entry-valid registers per cache. The unchanged original Debian
SimTop also emitted successfully and retained **no** I-cache pins. The local
machine still lacks Verilator and a Debian firmware image, so **no actual
cache performance improvement or RTL boot milestone is yet measured**.
The separate `.github/workflows/product-cache-capacity-short-ab.yml` is an
explicit dispatch or same-repository PR-label workflow (not a push-triggered
longrun). Its initial bounded 64/256 comparison retains independent model
identities and logs; 512 remains a follow-up requiring an explicit selection.

## Separate XC7 unplaced full-board cache-area A/B

The original signed SD1/P0 Artix-7 export and XC7 map still accept only
their production `ElaborateProductFpgaUartDdrSpiSd1Rv64gc` emitter.
The independent opt-in `.github/workflows/product-fpga-xc7-cache-ab.yml`
instead exports the **same full board-neutral UART-DDR-SPI-SD Product top**
twice: 64/64 entries (512B I + 512B D) and 256/256 (2KiB + 2KiB).
It has a one-time, tightly scoped `push` trigger matching only the isolated
`work/xc7-cache-ab-isolated-20261109` branch **and a change to the workflow
file itself**. Other source pushes cannot start the expensive comparison;
no draft PR or unrelated PR-opening jobs are needed.
Both must have the same exact committed source SHA and Yosys version, while
each has its own source/RTL SHA256, cache geometry and mapping output.

On a machine with `jq`, Chisel/Mill/Java and genuine `yosys synth_xilinx`
available, after committing the isolated source:

```sh
bash tools/ci/product_fpga_xc7_cache_ab.sh prepare 64 build/xc7-cache-ab/cache-64
bash tools/ci/product_fpga_xc7_cache_ab.sh map 64 build/xc7-cache-ab/cache-64
bash tools/ci/product_fpga_xc7_cache_ab.sh prepare 256 build/xc7-cache-ab/cache-256
bash tools/ci/product_fpga_xc7_cache_ab.sh map 256 build/xc7-cache-ab/cache-256
python3 tools/ci/product_fpga_xc7_cache_ab_compare.py \
  build/xc7-cache-ab build/xc7-cache-ab/comparison.json
```

The `map` path reuses the existing bounded, real Xilinx 7-series Yosys mapper
(420s, 12GiB process RSS or 84% cgroup cap) via an opt-in manifest identity;
the original mapper default remains locked to the production emitter.
CI executes the two full mappings **serially** to avoid aggregate memory
pressure. The comparison rejects mismatched SHA, emitter, geometry, failed
mapping, or missing hierarchy, and records the deltas in LUT/FF/DSP,
RAM32M/RAM64M/discrete BRAM and relevant routing primitives. Mapping a
larger cache may increase distributed LUTRAM instead of inferring BRAM.

**Qualification:** XC7 unplaced primitive statistics only. Local Yosys and
Vivado are unavailable, so this workflow is authored and contract-tested but
has **not** produced actual area results. Neither the existing 50MHz OOC
timing constraint nor a successful mapping establishes XC7A200T pinout,
vendor DDR/MIG/PLL/CDC, routed timing, 200MHz, or interactive Debian speed.

## Measurement and merge gate

1. **Correctness first:** on each 64/256/512 case run the cache directed
   specs and Product integration/privilege tests. Hold `FENCE.I`, redirect
   response handling, narrow fetch byte-valid masks, PMP/PMA authorization,
   writes, atomics and MMIO bypass invariant. Do not expand line-fill width
   or issue unauthorized speculative physical reads as an implicit side effect.
2. **Same workload, same model:** follow
   `.github/workflows/v2-soc-icache-arch-ab.yml`'s frozen-source,
   fixed memory wait, same binary/DTB and short milestones pattern. Gather
   retired instructions, elapsed RTL cycles (IPC), cache hit/miss/bypass,
   SD reads and any correctness/serial markers. No full cold Debian restart
   just to compare cache sizing.
3. **Real 7-series area and timing:** synthesize the same full Product target
   for each profile/device at identical constraints; report LUT, FF, RAM and
   timing. The existing asynchronous `Mem` same-cycle read and whole-cache
   invalidation do **not** guarantee FPGA BRAM inference and may hurt Fmax.
4. Only promote a profile with correct RTL + improved relevant Linux work
   per cycle + acceptable **routed** timing/resource margins; keep P0
   immediately selectable. This branch makes no performance claim.

The SD1 1152M-1352M archived polling phase recorded 200M cycles with about
46.99M retired instructions, i.e. workload IPC ~0.235. That is one specific
SPI-intensive workload, not a general CPU IPC or hardware MHz measurement.
The SPI-SD rootfs is still 12.5MHz-max PIO and the DDR window remains
256MiB; frequency alone cannot establish smooth Debian use.
