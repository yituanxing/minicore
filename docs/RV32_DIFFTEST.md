# RV32 commit-level DiffTest checkpoint

## Status

AetherCore now has an independent RV32I commit-level differential-testing path.
The frozen GCC `rv32i/ilp32` workload runs on the complete 32-bit five-stage RTL
under deterministic memory backpressure, while an independently built NEMU
reference executes exactly one architectural instruction for every valid RTL
retirement.

The normal run completes with:

```text
binary bytes:       424
binary words:       106
binary SHA-256:     2707b90ae7084fdf5920f33369c77ed0316fe8eb976ccc9641b53957ed98e757
cycles:             777
retirements:        585
DiffTest matches:   585
stall period:       5
exit status:        0
```

## Independent RV32 adapter

The RV64 adapter remains unchanged. RV32 uses `sim/nemu_difftest_rv32.cpp` and
the ABI frozen by `RV32_NEMU_REFERENCE.md`:

```c
struct NemuState32 {
  uint32_t gpr[32];
  uint32_t pc;
};
```

The validated register-copy region is exactly 132 bytes. The adapter rejects
addresses or architectural state that do not fit in 32 bits rather than
silently truncating an RV64 layout.

For each committed RTL instruction, the checker performs this sequence:

1. copy NEMU pre-state and compare its PC and 32 GPRs with the DUT mirror;
2. verify that the retired instruction matches the frozen image at that PC;
3. ask NEMU to execute exactly one instruction;
4. apply the RTL retirement writeback to the DUT register mirror;
5. compare all 32 post-state GPRs;
6. for every committed store, compare every byte selected by the write mask.

A rolling history of the most recent matched retirements is retained for the
first mismatch report.

## Two reproducible NEMU profiles

Both reference artifacts use the same historical OpenXiangShan/NEMU revision:

```text
8601834e4889e6bf3b6113eb5f824ba7689126f5
```

### Frozen optimized ABI reference

The original PR #18 reference remains the byte-for-byte ABI baseline:

```text
PERF_OPT:          enabled
single-step mode:  disabled
SHA-256:           b064aec211ae22e872d8ab7a3705a2e354e0d968289dbb7880515710d9dd5eb7
```

The workflow rebuilds this artifact at the exact frozen absolute path before
building the execution oracle. The historical shared object is unstripped and
contains build-path data, so changing that path changes the binary hash even
when the source and generated configuration are otherwise equivalent.

### Exact retirement oracle

The commit-level reference uses the same ISA, CPU-state, memory, and DiffTest
sources, but selects NEMU's exact interpreter loop:

```text
PERF_OPT:          disabled
instruction count: enabled
single-step mode:  enabled
SHA-256:           1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
```

The distinction is required by the historical implementation. Its `PERF_OPT`
executor accounts work at basic-block/control-flow boundaries, so
`difftest_exec(1)` may advance through an entire basic block. The non-PERF_OPT
interpreter decrements the requested count once per decoded instruction and is
therefore the correct oracle for retirement-by-retirement comparison.

Both profiles are built twice from clean directories and must reproduce their
own SHA-256 exactly. The optimized reference must also retain the PR #18 hash.

## MMIO stores are not skipped

The program terminates by storing to the platform exit register at
`0x10000008`. The historical RV32 reference has no matching platform device
model, so the RV32 adapter registers one passive 4 KiB NEMU MMIO page:

```text
0x10000000 .. 0x10000fff
```

NEMU therefore executes UART and exit stores normally. The checker reads the
passive MMIO backing storage and compares each enabled byte, including the
final exit store. No retirement is skipped to make the program pass.

## Negative proof

CI runs the same binary a second time with a deliberate x31 corruption at the
first matched retirement. That run must fail with:

```text
RV32 DiffTest mismatch after 0 matched commits:
after reference execution: x31 NEMU=0x00000000 DUT=0x00000001
```

This proves that the RV32 path is detecting architectural differences rather
than merely reporting successful program termination.

## Frozen functional evidence

The final functional run before this documentation checkpoint is:

```text
GitHub Actions run:  30710793791
artifact:            rv32-nemu-difftest-30710793791
artifact ID:         8821827645
artifact ZIP SHA-256: 2b1f50a0fea204b2017cc934b89d8a74e72a0354d89ac4f019560f848741962d
```

The artifact retains both reference configurations and build evidence, both
reference hashes, ABI probes, the frozen GCC ELF/binary/map/disassembly, the
positive DiffTest log, the deliberate mismatch log, emitted RTL, and Verilator
outputs.

## Preserved gates

This checkpoint does not replace any existing gate. The frozen RV64 normal
retirement total remains:

```text
frozen directed/generated architecture:       3,119
S1.1/S1.2 compiler-produced corpus:          204,218
S1.3 pinned CoreMark:                        737,070
---------------------------------------------------
complete normal-retirement gate:            944,407
```

The independent RV32 self-check workload also remains mandatory in addition to
the new 585-retirement DiffTest gate.

## Current boundary

This checkpoint covers RV32I integer architectural state and committed store
bytes. It does not yet claim RV32 exception/CSR state, privilege modes,
interrupts, atomics, compressed instructions, or a complete RV32M software
matrix.

The next software-driven step should expand the RV32 corpus through generated
RV32I programs and real GCC workloads, then enable the existing parameterized
M unit in an `rv32im` profile and run real benchmark software through the same
reference path.
