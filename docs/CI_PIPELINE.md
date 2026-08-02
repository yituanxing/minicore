# Consolidated self-hosted CI pipeline

The self-hosted runner executes one complete verification job instead of thirteen independent jobs.

## Why

A single runner cannot execute jobs in parallel. The previous workflow layout repeated checkout, toolchain verification, Mill startup and deterministic RV32 NEMU construction for every gate. Queue state also hid useful progress behind many pending jobs.

## New structure

The `AetherCore Full Gate` workflow uses one workspace and exposes ordered phases:

1. persistent toolchain and Mill verification;
2. fast Python and Chisel tests;
3. RV64 RTL, pipeline and NEMU differential gates;
4. one optimized and one single-step deterministic RV32 NEMU reference build;
5. RV32 GCC, CSR, trap, MRET and timer architecture gates;
6. RV64 compiler/upstream workloads;
7. RV32 CoreMark, Embench and littlefs workloads;
8. one consolidated evidence upload.

Frozen hashes, exact retirement counts and deliberate mismatch probes are unchanged. The old job display names are emitted as lightweight compatibility checks after the real full gate succeeds, preserving existing branch-protection contexts without repeating verification.

## Targeted local phases

Each phase can be run directly:

```bash
bash tools/ci/full_gate.sh toolchain
bash tools/ci/full_gate.sh python
bash tools/ci/full_gate.sh chisel
bash tools/ci/full_gate.sh rv64-smoke
bash tools/ci/full_gate.sh rv64-difftest
bash tools/ci/full_gate.sh rv32-reference
bash tools/ci/full_gate.sh rv32-csr
bash tools/ci/full_gate.sh rv32-traps
bash tools/ci/full_gate.sh rv32-mret
bash tools/ci/full_gate.sh rv32-timer
```

Later phases reuse products from earlier phases in the same workspace. A complete run should therefore start at `toolchain`; targeted commands are intended for diagnosis in an already prepared workspace.
