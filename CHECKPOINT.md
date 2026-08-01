# AetherCore S0.1 checkpoint

## Current target

Bring up a five-stage in-order RV64I core in Chisel with a deterministic Verilator smoke test and an architectural commit trace suitable for future NEMU/Spike DiffTest.

## Implemented

- IF/ID/EX/MEM/WB pipeline skeleton.
- RV64I decoder and ALU, including W-class arithmetic.
- Integer register file with x0 protection and same-cycle WB bypass.
- EX/MEM and MEM/WB forwarding.
- One-cycle load-use interlock.
- EX-stage branch and jump redirection.
- Blocking data bus and host-backed RAM adapter.
- UART MMIO at `0x10000000`.
- Commit trace and temporary halt-on-exception behavior.
- Python ISA smoke reference.
- Chisel unit/smoke tests and Verilator harness.
- GitHub Actions bring-up pipeline.

## Verified locally

- Python smoke reference: UART `A`, `x3 = 12`, seven commits, halted.
- Python syntax checks.

## Pending real toolchain verification

- Chisel compilation and elaboration.
- SystemVerilog generation.
- Verilator build and RTL smoke execution.

## Immediate gate

Fix all errors from GitHub Actions until Chisel tests, elaboration, and Verilator smoke are green. Do not add ISA features before this gate passes.
