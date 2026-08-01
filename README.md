# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel and driven by executable software workloads.

The current verified checkpoint is **S1.2**: a five-stage in-order RV64IM core with Chisel/CIRCT/Verilator implementation, commit-level differential testing against a pinned OpenXiangShan/NEMU reference, and a frozen corpus of real freestanding C programs compiled across multiple optimization levels.

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

```text
C source
  -> riscv64-unknown-elf-gcc -march=rv64im -mabi=lp64
  -> crt0.S + linker.ld
  -> ELF + flat binary + map + disassembly
  -> Verilated AetherCore
  -> one-for-one NEMU retirement comparison
```

Current source workloads:

- `call_stack`: recursion, nested calls, stack frames, arrays, structures and remainder;
- `memory`: `.rodata/.data/.bss`, byte loops, pointers, structures and checksum code;
- `arithmetic`: signed/unsigned 64-bit and W-class multiply/divide/remainder;
- `sort`: insertion sort and recursive quicksort;
- `crc_hash`: CRC32, FNV-1a and rotate/multiply hashing;
- `mixed_integer`: signed matrix multiplication and quotient/remainder chains.

The last three are compiled at `-O0`, `-O2` and `-Os`, producing distinct stack layouts, register allocation and control-flow shapes.

## Verified scale

GitHub Actions runs `30702481540` and `30702481539` passed:

```text
frozen directed/generated architecture:     3,119 comparisons
compiler-produced corpus:                 204,218 comparisons
----------------------------------------------------------
complete normal-retirement gate:          207,337 comparisons
```

Every normal retirement matched the pinned NEMU reference. All 12 compiled programs returned exit code zero. Strict smoke, the deliberate mismatch probe and all three precise fault-boundary regressions also remain green.

Exact optimization levels, image sizes, cycle counts, retirement counts and hashes are recorded in [`docs/COMPILED_CORPUS.md`](docs/COMPILED_CORPUS.md).

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

Real programs are design inputs, not final demos. New compiler-generated failures are reduced to focused regressions before RTL is changed. Frozen binary hashes must run unchanged across microarchitectural changes; recompilation is a separate compiler-compatibility gate.

`main` contains only checkpoints that pass the complete CI path. See [`CHECKPOINT.md`](CHECKPOINT.md) for the exact verified boundary and [`docs/ROADMAP.md`](docs/ROADMAP.md) for Minic integration, the multi-cycle M unit, privileged architecture, Linux and FPGA bring-up.
