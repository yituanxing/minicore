# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel.

The current verified checkpoint is **S0.4**: a five-stage in-order RV64I core with a real Chisel/CIRCT/Verilator build path, eleven normal self-checking programs, three exact fault-boundary regressions, and commit-level differential testing against a pinned OpenXiangShan/NEMU reference.

## Current core

```text
IF -> ID -> EX -> MEM -> WB/commit
```

Implemented foundations:

- RV64I integer decode and execution, including W-class operations.
- forwarding, load-use interlock and branch/jump recovery.
- blocking instruction/data interfaces with host-backed simulation RAM.
- UART MMIO at `0x10000000` and self-check exit MMIO at `0x10000008`.
- architectural commit trace with PC, instruction, destination write and Store metadata.
- temporary halt-on-exception behavior with precise suppression of younger memory side effects.
- Chisel unit tests, strict full-core smoke, Verilator regression harness and NEMU DiffTest.

## Verified strict smoke

```text
A
PASS: halted after 16 cycles, 7 committed instructions, x3=12, UART="A"
```

## Normal directed regressions

The eleven normal Verilator programs cover:

- EX/MEM and MEM/WB forwarding;
- immediate load-use dependency;
- blocking memory transactions under deterministic backpressure;
- taken-branch wrong-path Store suppression;
- JAL/JALR links, target recovery and JALR bit-zero clearing;
- byte, halfword, word and doubleword stores/loads;
- signed and unsigned load extension;
- RV64 W-class immediate and register operations;
- all six signed/unsigned branch predicates in taken and not-taken cases;
- x0 write suppression and same-cycle WB/read bypass;
- integer logical, comparison, SUB and 64-bit shift operations;
- LUI/AUIPC values and PC-relative link-address corner cases;
- FENCE and FENCE.I retirement in the uncached core.

## NEMU commit-level DiffTest

The reference is pinned to:

```text
OpenXiangShan/NEMU
commit ad6bfde6241f2fc1e864b1efb2bed99b3670eb73
config riscv64-nutshell-ref_defconfig
```

For every normal DUT retirement, the harness checks the pre-instruction PC and all 32 GPRs, executes exactly one NEMU instruction, compares all post-instruction GPRs, and verifies enabled Store bytes in reference memory. It keeps the latest 32 matched retirements for first-failure diagnostics.

GitHub Actions run `30695286414` compared all eleven normal programs successfully:

```text
209 committed instructions
209 NEMU reference steps
0 mismatches
```

The adapter does not copy DUT state back into NEMU after initialization, so the reference remains independent throughout execution.

## Precise fault regressions

The fault suite checks exact commit PC/instruction/count and suppression of immediately younger effects:

```text
illegal_instruction:
PASS: precise fault pc=0x80000008 inst=0xffffffff after 12 cycles, 3 committed instructions

load_bus_fault:
PASS: precise fault pc=0x8000000c inst=0x0000b103 after 13 cycles, 4 committed instructions, stall-period=3

store_bus_fault:
PASS: precise fault pc=0x80000018 inst=0x0020b023 after 16 cycles, 7 committed instructions, stall-period=4
```

These tests found and prevented a younger Store from issuing in the same cycle an older exception retired from WB. Fault tests remain separate from normal DiffTest until trap CSRs and post-trap execution are implemented.

## Build

Prerequisites are Java 21, C++, Python 3, Verilator, Bison, Flex, Readline and SDL2 development files. The repository launcher obtains the pinned Mill version. NEMU is fetched at its pinned revision and cached by CI.

```bash
chmod +x mill
make python-test
make test
make rtl
make run-smoke
make run-regressions
make run-completion-regressions
make run-fault-regressions
make run-difftest
```

Useful optional trace:

```bash
build/obj/VAetherCoreSimTop build/software/smoke.bin \
  --max-cycles 200 --commit-trace --trace
```

## Development policy

Small changes run unit tests and the strict smoke gate. Feature groups add directed regressions before larger generated or differential tests. `main` contains only checkpoints that have passed the complete CI path.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the exact verified state and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the path toward generated DiffTest, privileged architecture, Linux and XC7Z020 FPGA bring-up.
