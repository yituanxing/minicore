# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel.

The current verified checkpoint is **S0.5**: a five-stage in-order RV64I core with a real Chisel/CIRCT/Verilator path, directed and deterministic generated programs, exact fault-boundary regressions, and commit-level differential testing against a pinned OpenXiangShan/NEMU reference.

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
- Chisel unit tests, strict full-core smoke, directed regressions, generated streams and NEMU DiffTest.

## Verified strict smoke

```text
A
PASS: halted after 16 cycles, 7 committed instructions, x3=12, UART="A"
```

## Directed RV64I regressions

The eleven normal Verilator programs cover:

- EX/MEM and MEM/WB forwarding;
- immediate load-use dependency;
- blocking memory transactions under deterministic backpressure;
- taken-branch wrong-path Store suppression;
- JAL/JALR links, target recovery and JALR bit-zero clearing;
- byte, halfword, word and doubleword stores/loads;
- signed and unsigned load extension;
- RV64 W-class immediate and register operations;
- all six signed/unsigned branch predicates;
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

For every normal DUT retirement, the harness checks the pre-instruction PC and all 32 GPRs, executes exactly one NEMU instruction, compares all post-instruction GPRs, and verifies enabled Store bytes in reference memory. It keeps the latest 32 matched retirements for first-failure diagnostics. The adapter never copies DUT state back into NEMU after initialization.

## Checker mismatch probe

A standalone probe deliberately presents the first `forwarding` retirement as `x1=6` instead of the architectural result `x1=7`. CI requires the same adapter to report:

```text
DiffTest mismatch after 0 matched commits:
after reference execution: x1 NEMU=0x0000000000000007 DUT=0x0000000000000006
```

The production Verilator harness contains no state-perturbation mode.

## Deterministic generated DiffTest

Five fixed XorShift64 seeds generate long RV64I streams with ALU, logical, comparison, shift, W-class, Load/Store, load-use, FENCE and x0 cases. Four images run with periodic memory backpressure.

GitHub Actions run `30696527693` produced:

```text
seed_a37e0001: 274 commits, difftest=274
seed_a37e0002: 270 commits, difftest=270, stall-period=3
seed_a37e0003: 263 commits, difftest=263, stall-period=4
seed_a37e0004: 259 commits, difftest=259, stall-period=5
seed_a37e0005: 265 commits, difftest=265, stall-period=7
```

Generated comparisons: **1331**. Directed comparisons: **209**. S0.5 total: **1540 normal retirements**, all matched one-for-one with NEMU.

The seed, generated binary, manifest and logs are retained in the CI artifact so every failure is reproducible.

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
make run-difftest-mismatch-probe
make run-generated-difftest
```

Useful optional trace:

```bash
build/obj/VAetherCoreSimTop build/software/smoke.bin \
  --max-cycles 200 --commit-trace --trace
```

## Development policy

Small changes run unit tests and the strict smoke gate. Feature groups add directed regressions before generated or differential expansion. `main` contains only checkpoints that have passed the complete CI path.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the exact verified state and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the path toward RV64M, privileged architecture, Linux and XC7Z020 FPGA bring-up.
