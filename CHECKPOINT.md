# AetherCore S0.3a checkpoint

## Status

The first directed RV64I pipeline-regression layer is green on top of the verified S0.2 core. S0.3a establishes self-checking binaries, deterministic memory backpressure and CI evidence for forwarding, load-use, branch recovery and JAL/JALR recovery.

## Core baseline inherited from S0.2

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

## Added in S0.3a

- Tiny Python RV64 instruction encoder with labels and B/J fixups.
- Self-checking regression binaries that return unique failure codes through exit MMIO.
- `--self-check-exit` Verilator mode.
- Deterministic `--stall-period N` memory-ready backpressure.
- CI execution and archived logs/images for all directed regressions.

## Verified by GitHub Actions

Run `30693234154` completed successfully with the original S0.2 smoke and all new regressions green.

```text
A
PASS: halted after 16 cycles, 7 committed instructions, x3=12, UART="A"

forwarding:
PASS: self-check exit=0 after 17 cycles, 8 committed instructions

load_use:
PASS: self-check exit=0 after 22 cycles, 11 committed instructions, stall-period=3

branch_flush:
PASS: self-check exit=0 after 21 cycles, 9 committed instructions

jal_jalr:
PASS: self-check exit=0 after 26 cycles, 11 committed instructions
```

Coverage demonstrated:

- dependent ALU instructions consume EX/MEM and MEM/WB forwarded results;
- an immediate load consumer receives the correct value after the load-use bubble;
- a blocking load survives deterministic memory backpressure without duplicate or lost architectural progress;
- a taken branch suppresses a younger wrong-path store;
- JAL writes a link address and JALR returns through it while wrong-path instructions are flushed.

## Known non-fatal warnings

- Verilator reports the deliberately cleared JALR low bit as unused.
- Generated reset/randomization helper symbols are unused.
- Shift intermediates are wider than their selected architectural result.

## Next gate: S0.3b

Continue deterministic RV64I coverage before adding privileged architecture:

- byte/half/word/dword stores and loads;
- signed and unsigned load extension;
- ADDIW/SLLIW/SRLIW/SRAIW and register W-class operations;
- all signed and unsigned branch predicates;
- x0 protection and same-cycle WB/read corner cases;
- broader deterministic memory-ready patterns.
