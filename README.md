# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel.

The current stable development checkpoint is **S0.3c**: a five-stage in-order RV64I core with a real Chisel/CIRCT/Verilator build path and eleven self-checking directed regression programs.

## Current core

```text
IF -> ID -> EX -> MEM -> WB/commit
```

Implemented foundations:

- RV64I integer decode and execution, including W-class operations.
- forwarding, load-use interlock and branch/jump recovery.
- blocking instruction/data interfaces with host-backed simulation RAM.
- UART MMIO at `0x10000000` and self-check exit MMIO at `0x10000008`.
- architectural commit trace prepared for future NEMU/Spike DiffTest.
- Chisel unit tests, strict full-core smoke and Verilator regression harness.

## Verified strict smoke

```text
A
PASS: halted after 16 cycles, 7 committed instructions, x3=12, UART="A"
```

## Directed regressions

The Verilator suite currently covers:

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
- remaining integer logical, comparison, SUB and 64-bit shift operations;
- LUI/AUIPC values and PC-relative link-address corner cases;
- FENCE and FENCE.I retirement in the uncached core.

```bash
make run-regressions
make run-completion-regressions
```

Each program writes zero to exit MMIO on success and a unique nonzero code on failure.

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
```

Useful optional trace:

```bash
build/obj/VAetherCoreSimTop build/software/smoke.bin \
  --max-cycles 200 --commit-trace --trace
```

## Development policy

Small changes run unit tests and the strict smoke gate. Feature groups add directed regressions before larger random or differential tests. `main` contains only checkpoints that have passed the complete CI path.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the exact verified state and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the path toward DiffTest, privileged architecture, Linux and XC7Z020 FPGA bring-up.
