# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel.

The current stable development checkpoint is **S0.3d**: a five-stage in-order RV64I core with a real Chisel/CIRCT/Verilator build path, eleven normal self-checking programs, and three exact fault-boundary regressions.

## Current core

```text
IF -> ID -> EX -> MEM -> WB/commit
```

Implemented foundations:

- RV64I integer decode and execution, including W-class operations.
- forwarding, load-use interlock and branch/jump recovery.
- blocking instruction/data interfaces with host-backed simulation RAM.
- UART MMIO at `0x10000000` and self-check exit MMIO at `0x10000008`.
- architectural commit trace prepared for NEMU/Spike DiffTest.
- temporary halt-on-exception behavior with precise suppression of younger memory side effects.
- Chisel unit tests, strict full-core smoke and Verilator regression harness.

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
- taken-branch wrong-path store suppression;
- JAL/JALR links, target recovery and JALR bit-zero clearing;
- byte, halfword, word and doubleword stores/loads;
- signed and unsigned load extension;
- RV64 W-class immediate and register operations;
- all six signed/unsigned branch predicates in taken and not-taken cases;
- x0 write suppression and same-cycle WB/read bypass;
- integer logical, comparison, SUB and 64-bit shift operations;
- LUI/AUIPC values and PC-relative link-address corner cases;
- FENCE and FENCE.I retirement in the uncached core.

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

These tests found and prevented a younger Store from issuing in the same cycle an older exception retired from WB.

## Build

Prerequisites are Java 21, C++, Python 3 and Verilator. The repository launcher obtains the pinned Mill version.

```bash
chmod +x mill
make python-test
make test
make rtl
make run-smoke
make run-regressions
make run-completion-regressions
make run-fault-regressions
```

Useful optional trace:

```bash
build/obj/VAetherCoreSimTop build/software/smoke.bin \
  --max-cycles 200 --commit-trace --trace
```

## Development policy

Small changes run unit tests and the strict smoke gate. Feature groups add directed regressions before larger random or differential tests. `main` contains only checkpoints that have passed the complete CI path.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the exact verified state and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the path toward DiffTest, privileged architecture, Linux and XC7Z020 FPGA bring-up.
