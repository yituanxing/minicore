# AetherCore S0.5 checkpoint

## Status

S0.5 proves that the external checker rejects an intentionally unequal retirement, then expands the frozen RV64I core from directed examples to deterministic generated instruction streams. The complete suite passes self-checking Verilator execution and one-for-one commit comparison against a pinned OpenXiangShan/NEMU reference.

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
- Chisel tests, strict Verilator smoke, directed regressions, generated regressions and GitHub Actions evidence artifacts.

No CPU RTL changed between S0.4 and S0.5.

## NEMU reference

The reference implementation remains fixed:

- repository: `OpenXiangShan/NEMU`;
- revision: `ad6bfde6241f2fc1e864b1efb2bed99b3670eb73`;
- configuration: `riscv64-nutshell-ref_defconfig`;
- shared object: `build/riscv64-nemu-interpreter-so`;
- RAM base: `0x80000000`;
- reference RAM size: 64 MiB.

The selected configuration disables FPU, RVV, RVH and multicore state. The adapter synchronizes the scalar `regcpy` prefix through the 32 GPRs, machine mode, scalar privileged fields and PC.

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

The adapter never copies DUT register state back into NEMU after initialization.

The self-check exit Store terminates Verilator from the MEM/MMIO side-effect point before that Store reaches architectural retirement. Every preceding retirement is compared; the exit Store and following EBREAK are the final two image words and are intentionally outside the compared stream.

## Checker mismatch probe

A separate C++ probe uses the same `NemuDifftest` implementation and the real first instruction of `forwarding.bin`:

```text
addi x1, x0, 7
```

It deliberately presents `x1=6`. The test passes only when NEMU identifies the first post-execution GPR mismatch and the matched-commit counter remains zero.

GitHub Actions evidence:

```text
PASS: deliberate first-commit x1 mismatch was detected
DiffTest mismatch after 0 matched commits:
after reference execution: x1 NEMU=0x0000000000000007 DUT=0x0000000000000006
```

The production Verilator harness contains no perturbation switch; the probe is an independent test executable.

## Deterministic generated streams

The generator uses an in-repository XorShift64 implementation rather than Python's `random` module, so a seed maps to a stable instruction image independent of Python's random implementation.

Each program contains:

- initialization of x1-x27;
- a dedicated data base in x28 at reset PC + `0x780`;
- initialized aligned RAM slots;
- 192 deterministic generation steps;
- RV64 register and immediate arithmetic;
- logical, comparison and 64-bit shift operations;
- RV64 W-class operations;
- byte, halfword, word and doubleword Loads/Stores;
- immediate load-use dependency pairs;
- FENCE/FENCE.I;
- x0 write-suppression cases;
- an MMIO exit sequence.

The generator rejects any image whose code reaches the data scratch at `0x780`.

## Verified by GitHub Actions

Run `30696527693` passed the complete S0.5 gate.

Generated matrix:

```text
name           seed                bytes  words  stall  cycles  commits  difftest
seed_a37e0001  0x00000000a37e0001  1104   276    0      305     274      274
seed_a37e0002  0x00000000a37e0002  1088   272    3      326     270      270
seed_a37e0003  0x00000000a37e0003  1060   265    4      312     263      263
seed_a37e0004  0x00000000a37e0004  1044   261    5      300     259      259
seed_a37e0005  0x00000000a37e0005  1068   267    7      305     265      265
```

Generated retirement comparisons: **1331**.

The existing directed matrix remains **209** comparisons. Total normal one-for-one comparisons in the S0.5 gate: **1540**.

Generated binary SHA-256 values:

```text
e19da2e45b903808b1dac0c3006a1b9f26708ca4c5aadaa133e83f2d2d1fa37d  seed_a37e0001.bin
1c04e8cb39a914db8c2aff7861ca9000b4a379165ff62d18feebae50599ff3e1  seed_a37e0002.bin
405be5f779a07b97839c4dfe88ffa973da4084a2fa37bd5782db98acf7da7d83  seed_a37e0003.bin
702049816a45815a2a5d4ae160d44e47a32ef01e0053f2947a6a76f46ffff585  seed_a37e0004.bin
b47bca5a619fb26bcbb101f26c4c58b7b17b373f9a37f33fae2ef1f1b3aaf54a  seed_a37e0005.bin
```

Strict smoke, all eleven directed normal programs and all three precise-fault programs also remained green.

## Current verified boundary

- Python image/reference tests: PASS.
- deterministic generator reproducibility and opcode-family tests: PASS.
- Chisel compilation and unit tests: PASS.
- CIRCT SystemVerilog generation: PASS.
- Verilator compile/link: PASS.
- strict smoke: PASS.
- eleven directed normal RV64I programs: PASS.
- five deterministic generated RV64I programs: PASS.
- 1540 normal retirements compared one-for-one with NEMU: PASS.
- explicit checker mismatch probe: PASS.
- three exact fault-boundary programs: PASS.
- deterministic memory-backpressure periods 3, 4, 5 and 7: PASS.
- pinned NEMU build and GitHub Actions cache: PASS.

## Known non-fatal warnings

- Verilator reports the deliberately cleared JALR low bit as unused.
- Generated reset/randomization helper symbols are unused.
- Shift intermediates are wider than their selected architectural result.
- GitHub currently warns that some upstream Actions target Node.js 20 and are being forced onto Node.js 24.

## Next gate: S1 RV64M

Freeze S0.5 and add the M extension test-first:

- add decoder and ALU unit tests for MUL/DIV/REM and W-class variants;
- define signed, unsigned, divide-by-zero and overflow behavior explicitly;
- add directed whole-core programs before modifying RTL;
- extend deterministic generation to RV64M only after directed cases pass;
- continue comparing every normal retirement against the same pinned NEMU reference;
- preserve S0.5 as the rollback baseline.

Privileged architecture, trap CSRs and post-trap execution remain outside S1.