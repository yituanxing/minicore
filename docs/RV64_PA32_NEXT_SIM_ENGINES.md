# PA32 high-payoff simulation acceleration experiments

## Release and dependency pins

* Baseline RTL simulator: Verilator **v5.024**, source commit
  `522bead374d6b7b2adb316304126e5361b18bcf1` (unchanged).
* Candidate RTL simulator: Verilator **v5.054**, upstream annotated release
  tag `v5.054` pointing at commit `bbb60a403932a9f695cb6ff001ceb73de6ad9234`.
  Isolated `tools/ensure_verilator_5_054.sh` cache and build tree.
* GSIM frontend feasibility: upstream release
  `master-2026-10-30-45a73d6/gsim-clang19.1.7-X64`, artifact
  SHA256 `c4af0822567e15a2d7322653b36b310206dc51c6c6c89e1ad2ff9c148d19e919`.
* Current source workload: **unchanged** PA32 Chisel 7.7.0 board
  `ElaborateV2VirtualFpgaBoardPA32RV64` + unchanged OpenSBI/Linux firmware.

## Three distinct experiment boundaries

1. **Version/host-speed A/B:** Generates PA32 SV **once**; compiles that
   identical SV with Verilator 5.024 and 5.054; both use Clang -O3 and the
   same already qualified Adaptive Settle. On one Runner, alternate execution
   order for two rounds, 12M guest cycles each. Require identical complete
   `L32_OPENSBI_TIMEOUT` terminal snapshots. Report median cycles/sec and
   source+firmware SHA256. No faster-linux or correctness promotion is implied
   by an architecture-prefix A/B alone. The 5.024 path remains fallback.
2. **GSIM feasibility:** Chisel `emitCHIRRTL` from the **same PA32 top**
   followed by the SHA256-verified upstream GSIM compiler. Success means
   non-empty generated C++ only; this does **not** mean GSIM runs our C++ RAM,
   AXI, UART, RTL trace or Linux. If this compiles, the next step is a
   separate GSIM adapter and cycle/commit differential with Verilator.
3. **Failure replay / LightSSS:** Build on the existing validated-principle
   V2 fork-COW mechanism now migrated to PA32: the in-memory parent is a
   frozen checkpoint and children replay with independent STIP/SEIP proof.
   The next diagnostic-only slice will add bounded periodic snapshot slots
   and a replay-on-failure child with optional waveform when supported.
   It must not replace the canonical reset-to-Linux/PID1 signoff, and the
   current PA32 replay CI must pass before promoting a periodic snapshot
   feature.

## Already measured on PA32 (same-run)

`AETHERCORE_PA32_SPEED_STATE_MATCH`, CI 41553016504:
* GCC reference 237351 cycles/sec
* GCC Adaptive 273004 cycles/sec (+15.02%)
* Clang Adaptive 283832 cycles/sec (+19.58% vs GCC reference)

These are 20M-cycle throughput measurements, **not** real-Linux
cold-start wall-time improvements. The old fork/COW path is also diagnostic,
not a persistent disk checkpoint.

## Promotion rule

Preserve current CPU RTL, Linux MMU/Sv39/SBI/atomic/interrupt settings,
full PA32 UART PHY and firmware image. Each new simulator engine must pass
exact-state tests before running a full immutable-firmware cold-Linux
qualification on the winning mode. An older release winning the A/B is a
valid NO-GO outcome. Do not blindly combine speedup ratios from
different model versions or runner hardware.

## First CI diagnosis (2026-11-03)

* GSIM export produced 5,357,040 bytes of actual PA32 CHIRRTL (89 modules).
  Pinned GSIM parsed, initialized and topology-sorted the design, then aborted
  at `src/splitNodes.cpp:658` in `dupTreeWithBits()` with
  `invalid opType 19`. In pinned `include/ExpTree.h`, 19 is `OP_ASUINT`.
  This is an upstream frontend operator-coverage gap, not a PA32 Linux test
  failure. Keep the original FIRRTL and compiler provenance for reproduction.
  Do not rewrite CPU RTL to hide the issue or claim GSIM is operational.
* Verilator 5.054 was successfully built from fixed upstream commit. The initial
  version-comparison CI stopped before any A/B: GNU cross compiler environment
  exported to `GITHUB_ENV` only becomes available in the **next** Actions
  step. The corrected workflow separates toolchain provision and Linux reuse.
* First PA32 Fork/COW replay actually reached the Linux checkpoint at
  425,018,087 cycles and ran two identical 705,388-cycle / 214,436-commit
  children (STIP +9, SEIP +1 each). Job failed **after** RTL PASS because the
  verifier read `passed` from the wrong `V2_CHECKPOINT_PASS` line instead
  of `V2_CHECKPOINT_RESULT`. A fixture test now protects this contract.

## Next isolated GSIM compatibility experiment

The stock pinned GSIM stopped at `invalid opType 19` (`OP_ASUINT`) during
`dupTreeWithBits`. A new experiment uses **exact GSIM source commit**
`45a73d6afb9b1893781cfb57ccd54096987bb23d`, Clang 19 and a surgical
source patch that wraps casts in a bit-slice rather than distributing a slice
through the cast. Both `OP_ASUINT` and `OP_ASSINT` retain their original
subtree so the result bits are unchanged. The patch applies only to the
`dupTreeWithBits` operator dispatch and aborts if upstream source has drifted.

Its workflow `rv64-pa32-gsim-source-patch.yml` generates fresh PA32
CHIRRTL, builds patched GSIM in an isolated CI directory and tries to emit
C++. It does not touch Chisel/CPU RTL and is **experimental** until
frontend generation, differential simulation and full Linux all pass.
Success at C++ generation is not an ISA or Linux PASS.

The current PA32 Fork/COW runner also supports opt-in
`PA32_CHECKPOINT_REPLAY_ON_FAILURE=1`: only a failed child triggers another
fork from the frozen PID1 parent and traces the last 64 committed instructions.
This is fault reproduction from one checkpoint, not periodic LightSSS.

## Dependency corrections (second real CI pass)

* Verilator v5.054 model compilation reached link, where the new runtime
  requires `__atomic_is_lock_free`: explicit `-latomic` is used in *both*
  version builds. The older 5.024 runtime does not require it, but the same
  link flags preserve an apples-to-apples host comparison.
* Pinned GSIM source clone and scoped cast patch both succeeded; the
  Clang-19 source build failed because Ubuntu's `FlexLexer.h` is shipped
  by `libfl-dev`, not the runtime `flex` package. CI now installs both.

## Native GSIM runtime integration gate

After the first **real** CHIRRTL → GSIM C++ PASS in CI #41560954232,
this branch adds a generated-port ABI manifest plus Clang-19 native-model
build and reset/step smoke. The test drives the same complete PA32 model for
32 reset + 256 post-reset cycles. It does *not* inject OpenSBI/Linux yet,
and has no guest correctness or speedup claim. The actual generated top
header and port manifest are retained as artifacts for the upcoming
host DDR/commit/UART adapter. The Verilator model remains the ground truth.
