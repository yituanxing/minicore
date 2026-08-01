# RV32IM Embench-IoT batch 1 checkpoint

## Status

AetherCore now runs a multi-program upstream benchmark corpus on the named
`rv32im-software` profile. Four independent Embench-IoT programs compile with
GCC, execute on the complete five-stage RTL under deterministic memory
backpressure, pass their upstream verification routines, and match the exact
RV32 NEMU reference at every retirement and committed store byte.

This checkpoint broadens software shape beyond a single long CoreMark binary:

```text
crc32        table-driven byte and bit processing
edn          DSP-style arrays and multiply/accumulate kernels
matmult-int  nested loops and integer matrix multiplication
statemate    control-heavy generated state-machine logic
```

## Pinned upstream source

The source is the official `embench/embench-iot` repository at:

```text
09c2ed8c3b7008c95d08b038de4a3f6dc103ed70
```

The selected benchmark C files, input data, `support/main.c`,
`support/beebsc.c`, and each `verify_benchmark()` implementation remain
upstream. Benchmark algorithm sources are fetched into the build directory and
are not copied into this repository.

The artifact retains the upstream GPLv3-or-later `COPYING` file and the exact
source revision.

## Correctness-only scaling

Embench local scale factors repeat the same benchmark body enough times to
normalize a timing run to approximately seconds on a target platform. Repeating
those identical iterations would add large simulation cost without adding
instruction-shape coverage.

For this correctness corpus, the build creates a temporary source copy and
mechanically changes exactly one line per benchmark:

```c
#define LOCAL_SCALE_FACTOR 1
```

The exact unified patch is retained as `correctness-scaling.patch`. Inputs,
algorithm bodies, and verification functions are not edited. The remaining
correctness-run controls are:

```text
WARMUP_HEAT=0
GLOBAL_SCALE_FACTOR=1
LOCAL_SCALE_FACTOR=1
```

This checkpoint does not claim an Embench performance score.

## Platform integration

The repository adds only the platform-owned pieces needed by freestanding
software:

- empty board initialization and trigger hooks because the current profile has
  no cycle-counter CSR;
- freestanding declarations for the required C interfaces;
- small byte-correct implementations of memory/string primitives and `abs`;
- the established RV32 startup, linker layout, passive MMIO page, Verilator
  runner, and exact RV32 NEMU adapter.

Embench's own deterministic random generator and fixed-size heap implementation
remain upstream. This runtime is intentionally a narrow platform layer, not a
replacement for the planned musl validation stage.

## Compiler contract

```text
compiler:             riscv64-unknown-elf-gcc 13.2.0
ISA:                  RV32IM
ABI:                  ILP32
optimization:         -O2
freestanding/static:  yes
memory alignment:     strict
stall period:         5
```

## Frozen binaries

```text
benchmark     bytes   words  SHA-256
crc32         1,472     368  4c126e8244b5d05b74824d4c1c927d5db44eb8078b4353e8be5a42bc52588aca
edn           3,972     993  d521ef81684801adde928cbcf843083ce36c3aab14cf108995fe8d921006aac3
matmult-int   2,492     623  33d3f7ada07f51198589424a17d9e2d5203a620da3dc18489eb88ded1742615a
statemate     7,356   1,839  25c74615a0215731111ecf54c5477eb8f89291e768423eeee5c8dfd0dd2aaf4b
```

The linker currently emits the already-known non-fatal RWX load-segment
warning. Program-header separation remains a later platform cleanup and is not
hidden by this checkpoint.

## Exact execution results

```text
benchmark     cycles   retirements  DiffTest  exit
crc32          33,442       25,703    25,703     0
edn            61,875       47,338    47,338     0
matmult-int    143,851      109,152   109,152     0
statemate       2,716        1,992     1,992     0
----------------------------------------------------
total          241,884      184,185   184,185
```

Every upstream `verify_benchmark()` returns success. Each program runs in a new
simulator process, so RTL state, NEMU state, RAM, MMIO, and the DUT register
mirror are independently reset for every binary.

Reference contract:

```text
OpenXiangShan/NEMU revision:  8601834e4889e6bf3b6113eb5f824ba7689126f5
exact reference SHA-256:      1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
register-copy layout:         uint32_t gpr[32]; uint32_t pc
```

## First functional evidence

The first complete green four-program execution is GitHub Actions run:

```text
run:                  30712366479
artifact:             rv32im-embench-batch1-30712366479
artifact ID:          8822302724
artifact ZIP SHA-256: b9078d5b1fb2ff1dad977f89e975a18f3804611a8deb8ed526acc91803a204c1
```

The artifact retains the exact NEMU build/ABI evidence, source revision and
license, scaling patch, freestanding compiler flags, ELF/bin/map/disassembly
for all four programs, emitted RTL, Verilator outputs, per-program logs, and
combined result table.

## Preserved gates

This checkpoint is additive. It preserves:

- the frozen RV64 normal-retirement total of 944,407;
- the independent 585-retirement RV32I GCC DiffTest gate;
- the 646,301-retirement RV32IM CoreMark gate;
- strict smoke, precise-fault tests, deliberate mismatch probes, and all
  directed/generated RV64I and RV64M regressions.

The first Embench batch contributes another 184,185 exact reference-matched
retirements without combining or obscuring architecture-specific totals.

## Current boundary

Only four of the nineteen Embench-IoT programs are included. This first batch
proves the reusable multi-program import, freestanding support, per-binary
reset, exact reference execution, artifact retention, and freeze discipline.

The next step can add further Embench batches chosen for new dependencies and
code shapes, then move to stateful software such as littlefs and a selected
freestanding musl subset. Those stages should continue driving architectural
and platform completeness before FreeRTOS, xv6, Linux, and later performance
optimization.
