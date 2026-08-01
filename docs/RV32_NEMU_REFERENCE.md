# RV32 NEMU reference checkpoint

## Status

AetherCore now has a reproducible and independently probed RV32 NEMU reference
shared object suitable for the next commit-level DiffTest checkpoint.

This checkpoint establishes the reference-model ABI only. It does not yet claim
that an AetherCore RV32 program has been compared instruction by instruction.

## Why the existing RV64 reference cannot be reused

The repository's RV64 DiffTest path pins OpenXiangShan/NEMU at:

```text
ad6bfde6241f2fc1e864b1efb2bed99b3670eb73
```

That tree still contains `src/isa/riscv32`, but it has no RV32 reference
defconfig and its common reference path has evolved around RV64-only state and
headers. Reusing the RV64 adapter by truncating 64-bit values would therefore
mix two different state layouts and would not be an independent RV32 oracle.

## Pinned RV32 reference

The validated RV32 reference revision is:

```text
OpenXiangShan/NEMU
8601834e4889e6bf3b6113eb5f824ba7689126f5
```

This revision follows the focused upstream RV32/common-code adaptation and
includes the host-TLB rewrite that restores explicit `vaddr_t` typing.

The repository derives the following minimal shared-reference configuration:

```text
CONFIG_ISA_riscv32=y
CONFIG_CC_GCC=y
CONFIG_CC_O2=y
CONFIG_SHARE=y
CONFIG_PERF_OPT=y
CONFIG_TIMER_GETTIMEOFDAY=y
CONFIG_MBASE=0x80000000
CONFIG_MSIZE=0x4000000
CONFIG_PC_RESET_OFFSET=0x0
# CONFIG_CC_LTO is not set
# CONFIG_CC_DEBUG is not set
# CONFIG_CC_ASAN is not set
# CONFIG_DEBUG is not set
# CONFIG_DIFFTEST is not set
# CONFIG_MEM_RANDOM is not set
```

The generated configuration is named:

```text
riscv32-minicore-ref_defconfig
```

## Historical shared-object composition

In this NEMU era, `CONFIG_SHARE` excludes the complete device subsystem, while
the reference initialization and physical-address fallback still call the
standard MMIO mapping primitives. The build therefore adds only these two
unchanged upstream implementation files to the shared object:

```text
src/device/io/map.c
src/device/io/mmio.c
```

The shared object is also linked explicitly with `readline`, which supplies the
historical monitor symbols that the old build expected from its host process.
No RISC-V ISA, instruction execution, CPU state, memory semantics or DiffTest
copy implementation is patched.

Ubuntu 24.04 currently uses a much newer GCC than this 2021 source tree. Two
known host-compiler diagnostics are kept as warnings instead of errors:

```text
-Wno-error=format
-Wno-error=array-bounds
```

All other compiler warnings remain fatal.

## Verified reference artifact

GitHub Actions run:

```text
30709204540
```

Reference shared object:

```text
riscv32-nemu-interpreter-so
SHA-256: a9d3497f6492cc65c21a8904fda49c5bfe45555ac4b337ff15e39677e09d1dfc
```

The file is an x86-64 host shared object containing the RV32 NEMU interpreter.
The workflow retains the exact generated config, build-composition patch,
source copies, dynamic symbols, ELF metadata, linked libraries, build logs and
ABI probe output.

Artifact:

```text
rv32-nemu-probe-30709204540
artifact ID: 8821316109
ZIP SHA-256: 83349ff54369e8f5cda8112bc33de3ab69db14a15495cf259551c9136b13791c
```

## Register-copy ABI

The public and implementation layouts agree on:

```c
uint32_t gpr[32];
uint32_t pc;
```

Therefore:

```text
33 × 4 bytes = 132 bytes
```

The probe writes a nontrivial 132-byte pattern into the reference with
`difftest_regcpy(..., TO_REF)`, copies it back with
`difftest_regcpy(..., TO_DUT)`, and surrounds the destination with guard bytes.

Verified result:

```text
expected_reg_bytes=132
prefix_matches=true
guard_matches=true
layout=uint32_t gpr[32]; uint32_t pc
```

The unchanged guard region proves that the reference writes exactly the
expected state footprint rather than an assumed larger RV64 structure.

## Memory-copy ABI

The probe also performs a bidirectional 16-byte memory copy at the AetherCore
RV32 reset base:

```text
address=0x80000000
memory_roundtrip_matches=true
```

This proves that the exported `difftest_memcpy` entry accepts the RV32 physical
address used by the frozen GCC workload.

## Compatibility boundary

This checkpoint proves:

- exact NEMU revision and build recipe;
- a loadable self-contained reference shared object;
- exact 132-byte GPR/PC layout;
- bidirectional register copying without guard corruption;
- bidirectional memory copying at `0x80000000`;
- retained evidence and hashes.

It does not yet prove:

- one NEMU step per AetherCore RV32 retirement;
- pre-state and post-state register comparison;
- store-byte comparison;
- deliberate RV32 mismatch detection;
- the 585-retirement GCC RV32 workload matching NEMU.

Those become the mandatory next checkpoint.

## Next gate

1. add an RV32-specific adapter with the validated `uint32_t[32] + pc` layout;
2. keep the existing RV64 adapter unchanged;
3. load the frozen RV32 GCC binary into both RTL memory and NEMU;
4. compare pre-state, execute one reference instruction, then compare post-state;
5. compare every valid store byte;
6. add a deliberate RV32 mismatch probe;
7. freeze cycle, retirement, binary and reference hashes after a complete pass.
