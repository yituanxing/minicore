# RV32 external software checkpoint

## Status

AetherCore now has an external GCC-produced RV32I software path in addition to
the whole-core ChiselSim regression.

The path is:

```text
freestanding C + RV32 startup
  -> riscv64-unknown-elf-gcc -march=rv32i -mabi=ilp32
  -> ELF + flat binary + linker map + disassembly
  -> separately emitted AetherCoreRV32SimTop
  -> Verilator C++ simulation
  -> deterministic self-check exit MMIO
```

The existing RV64IM top, binaries and workflows remain separate and unchanged.

## Frozen workload

Source: `software/rv32/rv32_smoke.c`

The workload covers:

- function calls and return-address handling;
- stack-resident arrays;
- initialized `.data` and zero-initialized `.bss`;
- loops and pointer-based word loads/stores;
- 32-bit arithmetic, shifts and rotate-style expressions;
- deterministic copies and checksums;
- 32-bit startup, BSS clearing and exit MMIO;
- periodic external-memory backpressure.

Build contract:

```text
compiler:     riscv64-unknown-elf-gcc 13.2.0
-march:       rv32i
-mabi:        ilp32
optimization: -O2
```

## Verified result

GitHub Actions run `30707666074`:

```text
binary bytes:      424
binary words:      106
cycles:            777
retirements:       585
stall-period:      5
exit status:       0
binary SHA-256:    2707b90ae7084fdf5920f33369c77ed0316fe8eb976ccc9641b53957ed98e757
```

Artifact:

```text
rv32-workload-30707666074
artifact ID:       8820849245
ZIP SHA-256:       da54fda17e7f0ce4d3ac5fe6d34dcacf44c7dd771ab030227557d7714df14d13
```

The linker currently emits the same non-fatal RWX LOAD-segment warning as the
RV64 freestanding corpus. Segment separation is deferred until after the
software ladder is broader.

## Compatibility boundary

This checkpoint proves external GCC and Verilator compatibility for RV32I. It
is not yet an RV32 commit-level DiffTest gate.

The existing `sim/nemu_difftest.cpp` adapter is intentionally tied to the exact
register-copy layout of the pinned RV64 NEMU configuration. It must not be
reused for RV32 by merely truncating 64-bit values. A future checkpoint will
first probe and freeze the actual RV32 reference-model ABI, then add a separate
validated adapter.

## Frozen RV64 gate

Adding the RV32 top and software path must preserve the complete RV64 gate:

```text
frozen directed/generated architecture:       3,119
S1.1/S1.2 compiler-produced corpus:          204,218
S1.3 pinned CoreMark:                        737,070
---------------------------------------------------
complete normal-retirement gate:            944,407
```

The RV32 workload is an additional compatibility gate; it does not replace or
renumber the frozen RV64 retirement total until an independent RV32 reference
model is connected.

## Next gate

1. identify an exact RV32 reference-model revision and build configuration;
2. probe and document its `regcpy` state layout;
3. add an RV32-specific DiffTest adapter without changing the RV64 adapter;
4. compare every normal RV32 retirement and store byte;
5. retain the RV32 ELF, binary, map, disassembly, hashes and DiffTest logs.
