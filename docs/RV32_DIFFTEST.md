# RV32 commit-level DiffTest checkpoint

## Status

AetherCore now executes a GCC-produced RV32I program in the complete RV32
Verilator top and compares every normal retirement against an independent NEMU
reference one architectural instruction at a time.

The verified path is:

```text
freestanding GCC RV32I/ILP32 program
  -> frozen ELF and flat binary
  -> AetherCoreRV32SimTop under Verilator
  -> one DUT normal retirement
  -> compare NEMU pre-state PC and 32 GPRs
  -> execute exactly one NEMU instruction
  -> compare post-state 32 GPRs
  -> compare each valid byte of every retired RAM store
```

This is a separate RV32 adapter and reference from the repository's RV64
DiffTest path. The two state layouts are never mixed.

## Frozen software image

Source:

```text
software/rv32/rv32_smoke.c
```

Compiler contract:

```text
compiler:      riscv64-unknown-elf-gcc 13.2.0
-march:        rv32i
-mabi:         ilp32
optimization:  -O2
binary bytes:  424
binary words:  106
binary SHA-256: 2707b90ae7084fdf5920f33369c77ed0316fe8eb976ccc9641b53957ed98e757
```

The program covers startup, BSS clearing, stack-resident arrays, calls and
returns, initialized and zero-initialized globals, loops, pointer loads/stores,
32-bit shifts and arithmetic, deterministic copies and checksums, and an exit
MMIO self-check.

## Single-step NEMU reference

The reference pins:

```text
OpenXiangShan/NEMU
revision: 8601834e4889e6bf3b6113eb5f824ba7689126f5
```

The ABI checkpoint previously proved the exact state layout:

```c
uint32_t gpr[32];
uint32_t pc;
```

For commit-level comparison, the reference uses a distinct configuration with
translation-block execution disabled:

```text
CONFIG_ISA_riscv32=y
CONFIG_CC_GCC=y
CONFIG_CC_O2=y
CONFIG_SHARE=y
# CONFIG_PERF_OPT is not set
CONFIG_TIMER_GETTIMEOFDAY=y
CONFIG_MBASE=0x80000000
CONFIG_MSIZE=0x4000000
CONFIG_PC_RESET_OFFSET=0x0
```

This distinction is mandatory. The ABI/reference artifact built with
`CONFIG_PERF_OPT=y` is suitable for probing state and memory-copy interfaces,
but `difftest_exec(1)` can execute a complete translation block. It must not be
used as an instruction-by-instruction oracle.

The single-step reference is built twice from clean state with Build ID disabled
and a fixed `SOURCE_DATE_EPOCH`. Both builds are byte-identical:

```text
first SHA-256:  71c6fd8ed453fa82042a09c4cfe4649eb9564cbbb6928613b68a46a72ea9fe5
second SHA-256: 71c6fd8ed453fa82042a09c4cfe4649eb9564cbbb6928613b68a46a72ea9fe5
reproducible:   true
```

## Proof that one reference step is one instruction

Before running AetherCore software, the build script independently loads one
instruction at `0x80000000`:

```asm
auipc sp, 0x100
```

After exactly one `difftest_exec(1)` call, the required state is:

```text
x1 = 0x00000000
x2 = 0x80100000
pc = 0x80000004
all other GPRs = 0
```

Verified result:

```text
single_step_matches=true
other_gprs_zero=true
after_x1=0x00000000
after_x2=0x80100000
after_pc=0x80000004
memory_roundtrip_matches=true
```

This probe prevents a translation block from being mistaken for a single
architectural instruction.

## Platform MMIO boundary

The historical reference initializes its own serial region at a different
address. AetherCore exposes UART and exit registers in:

```text
0x10000000 .. 0x1000000f
```

The RV32 adapter registers this 16-byte window through NEMU's unchanged
`new_space` and `add_mmio_map` exports. The backing store is inert. NEMU can
therefore execute the same platform MMIO instructions without treating them as
RAM or asserting on an unmapped access.

Platform-visible UART and exit behavior remains verified by the DUT runner.
Store-byte DiffTest applies to retired stores inside the configured 64 MiB RAM.

## Commit comparison contract

For every normal DUT retirement, the adapter:

1. copies NEMU's 132-byte state to the host;
2. compares pre-execution PC and all 32 GPRs with the tracked DUT state;
3. checks that the committed instruction equals the frozen image word at PC;
4. calls `difftest_exec(1)` exactly once;
5. applies the DUT retirement's architectural register write to the tracked DUT state;
6. compares all 32 post-execution GPRs;
7. for a retired RAM store, copies the reference bytes and compares every byte enabled by the DUT write mask;
8. retains the most recent 32 matching commits for first-difference diagnostics.

No DUT state is copied back into NEMU after initialization. The reference remains
independent throughout execution.

## Verified result

GitHub Actions run:

```text
30710452219
```

Functional result:

```text
self-check exit: 0
cycles:          777
normal commits:  585
memory stalls:   89
DiffTest steps:  585
stall-period:    5
```

Runner summary:

```text
PASS: self-check exit=0 cycles=777 committed=585 stalls=89 difftest=585
```

Every one of the 585 normal RV32 retirements matched the single-step NEMU
reference. Every compared RAM store byte matched.

Artifact:

```text
rv32-difftest-30710452219
artifact ID: 8821718846
ZIP SHA-256: eb209a132bf92e4ec2befcb9818b6d2bfb24f9230c807c7442326dead719e311
```

## Negative checker proof

The dedicated mismatch probe reads the real first instruction from the frozen
binary and deliberately claims that it wrote `x1 = 1`.

The checker must reject that retirement before recording any matched commit:

```text
PASS: deliberate first-commit RV32 x1 mismatch was detected
RV32 DiffTest mismatch after 0 matched commits:
after reference execution: x1 NEMU=0x00000000 DUT=0x00000001
```

This proves that the passing workload is not the result of an inactive or
self-synchronizing checker.

## Cross-ISA verification scale

The independently referenced normal-retirement gates are now:

```text
RV64 directed/generated + software + CoreMark: 944,407
RV32 GCC commit-level DiffTest:                     585
-------------------------------------------------------
combined independently referenced retirements:    944,992
```

The sum is informational. RV32 and RV64 use separate NEMU revisions, shared
objects, state layouts and adapters; neither path substitutes for the other.

## Frozen compatibility rules

- The 424-byte RV32 binary and its hash remain a frozen microarchitecture gate.
- A CPU change may not silently rebuild the source to obtain a passing result.
- A compiler-compatibility rebuild is recorded as a separate artifact.
- The single-step reference configuration must keep `PERF_OPT` disabled.
- The negative mismatch probe remains mandatory.
- All existing RV64 architecture, GCC corpus and CoreMark workflows remain mandatory.

## Next software gate

The configurable-core and RV32 reference foundations are now sufficient to
return to the real-software ladder. The next checkpoint should pin and import a
small first batch of Embench-IoT integer programs, initially keeping each
program independent and self-checking. Selected workloads can then be compiled
for both RV64IM and RV32I to exercise the same upstream algorithms across both
configurations before moving to littlefs and freestanding musl routines.
