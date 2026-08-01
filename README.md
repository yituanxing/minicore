# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel.

The current verified checkpoint is **S1**: a five-stage in-order RV64IM core with Chisel/CIRCT/Verilator implementation, directed and deterministic generated programs, exact fault-boundary regressions, and commit-level differential testing against a pinned OpenXiangShan/NEMU reference.

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
- blocking instruction/data interfaces with host-backed simulation RAM;
- UART MMIO at `0x10000000` and self-check exit MMIO at `0x10000008`;
- architectural commit trace with PC, instruction, destination write and Store metadata;
- temporary halt-on-exception behavior with precise suppression of younger memory side effects;
- Chisel unit tests, strict full-core smoke, directed regressions, generated streams and NEMU DiffTest.

## RV64M

Supported instructions:

```text
MUL  MULH  MULHSU  MULHU
DIV  DIVU  REM     REMU
MULW DIVW  DIVUW   REMW  REMUW
```

The verification contract covers high-product signedness, normal signed/unsigned division, divide by zero, signed minimum divided by `-1`, W-class sign extension, back-to-back dependencies and Load-to-M interlocks.

The current M implementation is combinational and correctness-first. It is not the intended FPGA timing/area implementation; a later checkpoint will introduce a multi-cycle execution unit while preserving the architectural contract.

## NEMU commit-level DiffTest

The reference is pinned to:

```text
OpenXiangShan/NEMU
commit ad6bfde6241f2fc1e864b1efb2bed99b3670eb73
config riscv64-nutshell-ref_defconfig
```

For every normal DUT retirement, the harness checks the pre-instruction PC and all 32 GPRs, executes exactly one NEMU instruction, compares all post-instruction GPRs, and verifies enabled Store bytes in reference memory. DUT state is never copied back into NEMU after initialization.

A separate mismatch probe deliberately presents `addi x1, x0, 7` as `x1=6`; CI requires the adapter to reject it at zero matched commits.

## Verified S1 matrix

GitHub Actions run `30698820690` passed:

```text
RV64I directed/generated: 1540 comparisons
RV64M directed:           108 comparisons
RV64M generated:         1471 comparisons
------------------------------------------
normal retirement total: 3119 comparisons
```

All 3119 normal retirements matched NEMU one-for-one. The strict smoke, mismatch probe and all three precise fault-boundary regressions also remained green.

Directed RV64M programs:

```text
rv64m_multiply: 25 commits, difftest=25
rv64m_divide:   41 commits, difftest=41
rv64m_word:     42 commits, difftest=42
```

Generated RV64M programs:

```text
mseed_64d10001: 290 commits, difftest=290
mseed_64d10002: 293 commits, difftest=293, stall-period=3
mseed_64d10003: 299 commits, difftest=299, stall-period=4
mseed_64d10004: 297 commits, difftest=297, stall-period=5
mseed_64d10005: 292 commits, difftest=292, stall-period=7
```

Seeds, binaries, manifests and logs are retained in CI artifacts for exact reproduction.

## Pipeline bug found by generated testing

The first generated RV64M run found a pre-existing backpressure/forwarding bug:

```text
DIV x23,...      retires in WB with x23=0
LD  x22,...      stalls in MEM
ADD x16,x23,x13  remains frozen in EX
```

The ADD initially saw the correct WB-forwarded value, but after the stall edge the WB source disappeared and the frozen instruction fell back to its stale decode-time operand. A focused eight-instruction regression reproduced `expected -1181, observed 0x205`.

The stall path now saves the operands already selected by the forwarding network:

```scala
when(memoryStall) {
  memWb.valid := false.B
  idEx.rs1Data := forwardedRs1
  idEx.rs2Data := forwardedRs2
}
```

The previously failing generated seed now completes all `297/297` retirement comparisons.

## Precise fault regressions

The suite checks exact commit PC/instruction/count and suppression of immediately younger effects:

```text
illegal_instruction:
PASS: precise fault pc=0x80000008 inst=0xffffffff after 12 cycles, 3 committed instructions

load_bus_fault:
PASS: precise fault pc=0x8000000c inst=0x0000b103 after 13 cycles, 4 committed instructions, stall-period=3

store_bus_fault:
PASS: precise fault pc=0x80000018 inst=0x0020b023 after 16 cycles, 7 committed instructions, stall-period=4
```

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
make run-rv64m-regressions
make run-generated-rv64m
```

Useful optional trace:

```bash
build/obj/VAetherCoreSimTop build/software/smoke.bin \
  --max-cycles 200 --commit-trace --trace
```

## Development policy

Small changes run unit tests and the strict smoke gate. Feature groups add directed regressions before generated or differential expansion. `main` contains only checkpoints that have passed the complete CI path.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the exact verified state and [`docs/ROADMAP.md`](docs/ROADMAP.md) for compiled workloads, a multi-cycle M unit, privileged architecture, Linux and FPGA bring-up.