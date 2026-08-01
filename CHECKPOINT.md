# AetherCore S0.4 checkpoint

## Status

S0.4 connects the frozen RV64I core to a pinned OpenXiangShan/NEMU reference model and performs one architectural reference step for every normal DUT retirement. The complete directed suite now passes both its original self-checking assertions and commit-level DiffTest.

## Core baseline

- IF/ID/EX/MEM/WB five-stage in-order pipeline.
- RV64I decoder and ALU, including W-class arithmetic.
- Integer register file with x0 protection and same-cycle WB bypass.
- EX/MEM and MEM/WB forwarding.
- One-cycle load-use interlock.
- EX-stage branch and jump redirection.
- Blocking data bus and host-backed RAM adapter.
- UART MMIO at `0x10000000` and exit MMIO at `0x10000008`.
- Architectural commit trace and temporary halt-on-exception behavior.
- Precise suppression of younger memory effects while WB retires an exception.
- Chisel tests, strict Verilator smoke, directed regressions and GitHub Actions evidence artifacts.

## NEMU reference

The reference implementation is fixed rather than following a moving branch:

- repository: `OpenXiangShan/NEMU`;
- revision: `ad6bfde6241f2fc1e864b1efb2bed99b3670eb73`;
- configuration: `riscv64-nutshell-ref_defconfig`;
- shared object: `build/riscv64-nemu-interpreter-so`;
- RAM base: `0x80000000`;
- reference RAM size: 64 MiB.

The selected configuration disables FPU, RVV, RVH and multicore state. The adapter synchronizes the exact scalar `regcpy` prefix through the 32 GPRs, machine mode, scalar privileged fields and PC.

## Commit comparison contract

For each normal DUT retirement, the adapter:

1. reads NEMU state and checks the pre-instruction PC;
2. compares all 32 NEMU GPRs against the harness-maintained DUT architectural state;
3. verifies that the retired DUT instruction equals the instruction in the loaded image;
4. executes exactly one NEMU instruction;
5. applies only the DUT retirement destination write to the harness-maintained DUT state;
6. reads NEMU state again and compares all 32 post-instruction GPRs;
7. for normal RAM Stores, reads NEMU memory and compares every enabled byte;
8. retains the latest 32 matched commits for first-mismatch diagnostics.

The adapter never copies DUT register state back into NEMU after initialization. Therefore a passing result cannot be produced by continually resynchronizing the reference to the DUT.

The self-check exit Store terminates the Verilator run from the MEM/MMIO side-effect point before that Store reaches architectural retirement. It is intentionally outside the normal commit stream, while every preceding retirement is compared.

## Verified by GitHub Actions

Run `30695286414` passed the complete S0.4 gate.

Strict smoke:

```text
A
PASS: halted after 16 cycles, 7 committed instructions, x3=12, UART="A"
```

NEMU commit-level results:

```text
forwarding:       8 commits,  difftest=8
load_use:        11 commits,  difftest=11, stall-period=3
branch_flush:     9 commits,  difftest=9
jal_jalr:        11 commits,  difftest=11
memory_widths:   29 commits,  difftest=29, stall-period=4
word_operations: 28 commits,  difftest=28
branch_matrix:   17 commits,  difftest=17
x0_writeback:    12 commits,  difftest=12
alu_logic:       52 commits,  difftest=52
pc_relative:     23 commits,  difftest=23
fence_retire:     9 commits,  difftest=9
```

Total normal retirement comparisons: **209**.

All three separate precise-fault regressions also remained green:

```text
illegal_instruction:
PASS: precise fault pc=0x80000008 inst=0xffffffff after 12 cycles, 3 committed instructions

load_bus_fault:
PASS: precise fault pc=0x8000000c inst=0x0000b103 after 13 cycles, 4 committed instructions, stall-period=3

store_bus_fault:
PASS: precise fault pc=0x80000018 inst=0x0020b023 after 16 cycles, 7 committed instructions, stall-period=4
```

Precise faults are deliberately not sent through normal DiffTest until trap CSRs and post-trap PC behavior are part of AetherCore's architectural contract.

## Current verified boundary

- Python image/reference tests: PASS.
- Chisel compilation and unit tests: PASS.
- CIRCT SystemVerilog generation: PASS.
- Verilator compile/link: PASS.
- strict smoke: PASS.
- eleven normal RV64I whole-core programs: PASS.
- 209 normal retirements compared one-for-one with NEMU: PASS.
- three exact fault-boundary programs: PASS.
- deterministic memory-backpressure paths: PASS.
- pinned NEMU build and GitHub Actions cache: PASS.

## Known non-fatal warnings

- Verilator reports the deliberately cleared JALR low bit as unused.
- Generated reset/randomization helper symbols are unused.
- Shift intermediates are wider than their selected architectural result.
- GitHub currently warns that some upstream Actions target Node.js 20 and are being forced onto Node.js 24.

## Next gate: S0.5 generated differential programs

Keep S0.4 frozen and expand confidence without adding ISA features:

- generate deterministic seeded RV64I instruction streams;
- constrain control flow and memory accesses to valid test regions;
- terminate through the existing self-check MMIO convention;
- run each generated image with periodic memory backpressure variants;
- compare every normal retirement against the pinned NEMU reference;
- preserve the seed, binary and rolling trace on the first mismatch;
- add a deliberate adapter-negative test proving that a perturbed DUT retirement is detected.

Only after generated DiffTest is stable should development move to the M extension or privileged architecture.