# RV32IM CoreMark checkpoint

## Status

AetherCore now has a named RV32IM real-software profile validated by the same
pinned upstream CoreMark corpus already used by RV64IM.

```text
profile:            rv32im-software
XLEN:               32
ISA:                RV32IM
ABI:                ILP32
physical address:   32 bits
memory bus:         32 bits
privilege modes:    M
```

The existing `rv32i-minimal` and `rv64im-current` profiles remain separate and
unchanged. RV32IM has its own elaboration entry point and Verilator top:

```text
AetherCoreRV32IMSimTop
```

## Pinned real software

The software input is EEMBC CoreMark at the exact revision:

```text
1f483d5b8316753a742cbf5590caf5bd0a4e4777
```

The five upstream algorithm files remain unchanged:

```text
core_list_join.c
core_main.c
core_matrix.c
core_state.c
core_util.c
```

The repository reuses its existing platform-owned CoreMark port and wrapper,
plus the established RV32 startup and linker layout. The build is a deterministic
correctness corpus, not a published CoreMark performance score.

Compiler contract:

```text
compiler:           riscv64-unknown-elf-gcc 13.2.0
-march:             rv32im
-mabi:              ilp32
optimization:       -O2
iterations:         2
total data size:    2000
```

Frozen binary:

```text
file:                coremark_rv32im_O2.bin
bytes:               10,244
32-bit words:        2,561
SHA-256:             6e094fad601d16ca8279065ab01083583749d2fb654e4a75bf4d0427df8c5c59
```

## Real M-extension coverage

The CI does not accept the `rv32im` profile name or compiler flag alone as
coverage. It inspects the frozen disassembly and requires this exact static
M-opcode mix:

```text
mul:   12
 divu:  5
```

No other M opcode is currently present in this particular CoreMark binary. This
proves that the program genuinely drives the 32-bit M execution path while
keeping the corpus reproducible.

## Exact execution result

The program runs on the complete five-stage RV32IM RTL with deterministic
memory backpressure and the independent exact RV32 NEMU retirement oracle:

```text
cycles:             883,272
retirements:        646,301
DiffTest matches:   646,301
stall period:       5
self-check exit:    0
```

Every valid retirement matches NEMU. Every committed RAM or passive-MMIO store
byte selected by the RTL write mask is also compared. The final exit store is
not skipped.

Reference contract:

```text
OpenXiangShan/NEMU revision:  8601834e4889e6bf3b6113eb5f824ba7689126f5
exact reference SHA-256:      1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
register-copy ABI:            uint32_t gpr[32]; uint32_t pc
```

## First functional evidence

The first complete green execution was GitHub Actions run:

```text
run:                 30711389841
artifact:            rv32im-coremark-30711389841
artifact ID:         8822005770
artifact ZIP SHA-256: c19a0422080fadf91cc483e2f6012812c494f821fa627d1b656759e84d787dd5
```

The artifact retains the pinned source license/revision, compiler metadata,
manifest, ELF, raw binary, map, disassembly, ELF metadata, emitted RTL,
Verilator build, exact NEMU reference evidence and the execution log.

## Preserved gates

This checkpoint is additive. It does not replace the existing RV32I self-check
or 585-retirement RV32I DiffTest gate, and it does not alter the frozen RV64
normal-retirement total:

```text
frozen directed/generated architecture:       3,119
S1.1/S1.2 compiler-produced corpus:          204,218
S1.3 pinned RV64 CoreMark:                   737,070
---------------------------------------------------
complete frozen RV64 normal-retirement gate: 944,407
```

The new RV32IM CoreMark gate adds another 646,301 exact reference-matched
retirements without combining or obscuring the architecture-specific totals.

## Current boundary

This checkpoint validates the existing single-cycle RV32 M semantics through a
large upstream program. It does not yet optimize the M unit, add atomics or
compressed instructions, or claim system-software readiness.

The next software-driven step should broaden the workload shape rather than
immediately optimize the datapath: introduce an upstream multi-program corpus
such as Embench-IoT, then a stateful system-style library such as littlefs or a
freestanding musl subset. Those workloads can drive further architectural and
platform requirements before FreeRTOS, xv6 and Linux.
