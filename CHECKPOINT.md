# AetherCore S0.3c checkpoint

## Status

The deterministic RV64I directed suite now contains eleven self-checking Verilator programs. S0.3c completes the normal, non-trapping RV64I arithmetic/control-flow matrix for the current five-stage core without changing CPU RTL.

## Core baseline

- IF/ID/EX/MEM/WB in-order pipeline.
- RV64I decoder and ALU, including W-class arithmetic.
- Integer register file with x0 protection and same-cycle WB bypass.
- EX/MEM and MEM/WB forwarding.
- One-cycle load-use interlock.
- EX-stage branch and jump redirection.
- Blocking data bus and host-backed RAM adapter.
- UART MMIO at `0x10000000` and exit MMIO at `0x10000008`.
- Architectural commit trace and temporary halt-on-exception behavior.
- Chisel tests, strict Verilator smoke and GitHub Actions artifact pipeline.

## Verification infrastructure

- Tiny Python RV64 assembler with B/J fixups and the encodings required by the directed suite.
- Eleven self-checking binaries with unique exit-MMIO error codes.
- Deterministic memory-ready backpressure through `--stall-period N`.
- Archived generated images, SystemVerilog and CI logs.

## Verified by GitHub Actions

Run `30693939767` completed successfully. The strict smoke and all eight S0.3a/S0.3b programs remained green, then the three completion programs passed:

```text
alu_logic:
PASS: self-check exit=0 after 61 cycles, 52 committed instructions

pc_relative:
PASS: self-check exit=0 after 36 cycles, 23 committed instructions

fence_retire:
PASS: self-check exit=0 after 18 cycles, 9 committed instructions
```

## Coverage demonstrated

- XOR/OR/AND and XORI/ORI/ANDI;
- SUB, SLT/SLTU and SLTI/SLTIU;
- register and immediate 64-bit SLL/SRL/SRA behavior, including shift 63;
- signed 12-bit ADDI boundary values;
- exact AUIPC values;
- exact JAL and JALR link addresses;
- JALR clears target bit zero and flushes the wrong path;
- LUI sign extension;
- FENCE and FENCE.I retire without trapping in the current uncached implementation;
- all earlier forwarding, memory, W-class, branch and register-file regressions remain green.

Together with S0.3a/S0.3b, the normal RV64I execution paths used by the current core now have deterministic whole-core tests.

## Known non-fatal warnings

- Verilator reports the deliberately cleared JALR low bit as unused.
- Generated reset/randomization helper symbols are unused.
- Shift intermediates are wider than their selected architectural result.

## Next gate: S0.3d precise fault boundary

Before external DiffTest, verify the current temporary exception contract:

- illegal instruction retires exactly once with `exception=1`;
- load and store bus faults retire on the faulting instruction;
- younger register writes and stores are suppressed after the fault;
- commit PC/instruction match the faulting architectural instruction;
- reproducible memory-ready schedules do not move or duplicate the fault boundary.

After S0.3d, freeze the directed suite and begin commit-level NEMU/Spike DiffTest.
