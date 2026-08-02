# Consolidated self-hosted CI pipeline

The self-hosted runner executes one complete verification job instead of many independent jobs.

## Why

A single runner cannot execute jobs in parallel. The previous workflow layout repeated checkout, toolchain verification, Mill startup and deterministic RV32 NEMU construction for every gate. Queue state also hid useful progress behind many pending jobs.

## Structure

The `AetherCore Full Gate` workflow uses one workspace and exposes ordered phases:

1. persistent toolchain and Mill verification;
2. fast Python and Chisel tests;
3. RV64 RTL, pipeline and NEMU differential gates;
4. one optimized and one single-step deterministic RV32 NEMU reference build;
5. RV32 GCC, CSR, trap, MRET and timer architecture gates;
6. a timer-preemptive two-task scheduler using the shared RV32 reference;
7. RV64 compiler/upstream workloads;
8. RV32 CoreMark, Embench and littlefs workloads;
9. one consolidated evidence upload.

The scheduler phase freezes two complete integer contexts, independent stacks, eight timer-driven switches, six memory-backpressure schedules, the exact interrupt sequence, context-corruption rejection and a first-interrupt DiffTest mismatch probe.

Frozen hashes, exact retirement counts and deliberate mismatch probes are unchanged. The workflow has exactly one self-hosted job; obsolete historical-name mirror jobs were removed after repository merge policy proved they were not required contexts.

## Targeted local phases

Most phases can be run directly:

```bash
bash tools/ci/full_gate.sh toolchain
bash tools/ci/full_gate.sh python
bash tools/ci/full_gate.sh chisel
bash tools/ci/full_gate.sh rv64-smoke
bash tools/ci/full_gate.sh rv64-difftest
bash tools/ci/build_rv32_references.sh
bash tools/ci/full_gate.sh rv32-csr
bash tools/ci/full_gate.sh rv32-traps
bash tools/ci/full_gate.sh rv32-mret
bash tools/ci/full_gate.sh rv32-timer
bash tools/ci/full_gate_scheduler.sh
```

Later phases reuse products from earlier phases in the same workspace. A complete run should therefore start at `toolchain`; targeted commands are intended for diagnosis in an already prepared workspace. The scheduler command requires `build/ci/rv32-nemu-so.txt` from `build_rv32_references.sh`.
