# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel.

The current stable development checkpoint is **S0.2**: a five-stage in-order RV64I core that compiles with Chisel/CIRCT, builds with Verilator, and executes a deterministic architectural smoke program under GitHub Actions.

## Current core

```text
IF -> ID -> EX -> MEM -> WB/commit
```

Implemented foundations:

- RV64I integer decode and execution, including W-class operations.
- forwarding, load-use interlock and branch/jump recovery.
- blocking instruction/data interfaces with host-backed simulation RAM.
- UART MMIO at `0x10000000`.
- architectural commit trace prepared for future NEMU/Spike DiffTest.
- Chisel unit tests, full-core smoke test and Verilator harness.

## Verified smoke result

```text
A
PASS: halted after 16 cycles, 7 committed instructions, x3=12, UART="A"
```

## Build

Prerequisites are Java 21, C++, Python 3 and Verilator. The repository launcher obtains the pinned Mill version.

```bash
chmod +x mill
make python-test
make test
make rtl
make run-smoke
```

Useful optional trace:

```bash
build/obj/VAetherCoreSimTop build/software/smoke.bin \
  --max-cycles 200 --commit-trace --trace
```

## Development policy

Small changes run unit tests and the strict smoke gate. Feature groups add directed regressions before larger random or differential tests. `main` should contain only checkpoints that have passed the complete CI path.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the exact verified state and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the path toward DiffTest, privileged architecture, Linux and XC7Z020 FPGA bring-up.
