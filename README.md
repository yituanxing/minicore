# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel and driven by executable software workloads.

The current verified checkpoint is **S1.1**: a five-stage in-order RV64IM core with Chisel/CIRCT/Verilator implementation, commit-level differential testing against a pinned OpenXiangShan/NEMU reference, and real freestanding C programs compiled by RISC-V GCC.

## Current core

```text
IF -> ID -> EX -> MEM -> WB/commit
```

Implemented foundations:

- complete RV64I integer execution, including W-class operations;
- complete RV64M multiply/divide extension;
- EX/MEM and MEM/WB forwarding;
- load-use interlock and branch/jump recovery;
- preservation of forwarded EX operands across memory backpressure;
- blocking instruction/data interfaces with host-backed 64 MiB simulation RAM;
- UART MMIO at `0x10000000` and self-check exit MMIO at `0x10000008`;
- architectural commit trace with PC, instruction, destination write and Store metadata;
- temporary halt-on-exception behavior with precise suppression of younger memory side effects;
- Chisel unit tests, strict full-core smoke, directed/generated programs and NEMU DiffTest.

Supported M instructions:

```text
MUL  MULH  MULHSU  MULHU
DIV  DIVU  REM     REMU
MULW DIVW  DIVUW   REMW  REMUW
```

The current M implementation is combinational and correctness-first. A later checkpoint will replace division, and potentially multiplication, with a multi-cycle unit while preserving the same architectural results.

## Real compiler-produced software

S1.1 adds a freestanding RV64IM software path:

```text
C source
  -> riscv64-unknown-elf-gcc -march=rv64im -mabi=lp64
  -> crt0.S + linker.ld
  -> ELF + flat binary + map + disassembly
  -> Verilated AetherCore
  -> one-for-one NEMU retirement comparison
```

The first matrix contains:

- `call_stack`: recursion, nested calls, stack frames, arrays, structures and remainder;
- `memory`: `.rodata/.data/.bss`, byte loops, pointers, structures and checksum code;
- `arithmetic`: signed/unsigned 64-bit and W-class multiply/divide/remainder.

GitHub Actions run `30700575123` passed:

```text
call_stack:  2541 commits, difftest=2541
memory:       791 commits, difftest=791, stall-period=4
arithmetic:   289 commits, difftest=289, stall-period=3
-------------------------------------------------------
compiled C:  3621 normal retirement comparisons
```

All three programs returned exit code zero. ELF files, binaries, maps, disassemblies, logs and SHA-256 values are retained in the CI artifact.

## Complete verified total

The frozen S1 directed/generated matrix remains green:

```text
RV64I directed/generated: 1540 comparisons
RV64M directed:            108 comparisons
RV64M generated:          1471 comparisons
-------------------------------------------
S1 existing:              3119 comparisons
S1.1 compiled C:          3621 comparisons
-------------------------------------------
combined:                 6740 comparisons
```

Every normal retirement matched the pinned NEMU reference. The strict smoke, deliberate mismatch probe and all three precise fault-boundary regressions also remain green.

## NEMU reference

```text
OpenXiangShan/NEMU
commit ad6bfde6241f2fc1e864b1efb2bed99b3670eb73
config riscv64-nutshell-ref_defconfig
```

For every normal DUT retirement, the harness checks the pre-instruction PC and all 32 GPRs, executes exactly one NEMU instruction, compares all post-instruction GPRs, and verifies enabled Store bytes. DUT state is never copied back into NEMU after initialization.

## Build

Core verification prerequisites are Java 21, C++, Python 3, Verilator, Bison, Flex, Readline and SDL2 development files. Compiled workloads additionally require `gcc-riscv64-unknown-elf` and `binutils-riscv64-unknown-elf`.

```bash
chmod +x mill
make python-test
make test
make rtl
make run-smoke
make run-difftest
make run-generated-difftest
make run-rv64m-regressions
make run-generated-rv64m

bash tools/build_compiled_workloads.sh build/compiled-workloads
bash tools/run_compiled_workloads.sh \
  build/obj/VAetherCoreSimTop \
  "$(pwd)/build/nemu/build/riscv64-nemu-interpreter-so" \
  build/compiled-workloads
```

## Development policy

Real programs are treated as design inputs, not merely final demos. New compiler-generated failures are reduced to focused regressions before RTL is changed. `main` contains only checkpoints that pass the complete CI path.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the exact verified boundary and [`docs/ROADMAP.md`](docs/ROADMAP.md) for real-program expansion, Minic integration, the multi-cycle M unit, privileged architecture, Linux and FPGA bring-up.
