# AetherCore S0.3b checkpoint

## Status

The deterministic RV64I regression matrix now covers eight self-checking Verilator programs. S0.3b verifies the existing five-stage core across forwarding, blocking memory behavior, load/store widths, sign extension, W-class arithmetic, all branch predicates, control-flow recovery and register-file corner cases without changing CPU RTL.

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

- Tiny Python RV64 assembler with labels and B/J fixups.
- Load/store width, shift, W-class and all branch-predicate encoders.
- Self-checking binaries with unique exit-MMIO error codes.
- Deterministic memory-ready backpressure through `--stall-period N`.
- Archived generated images, SystemVerilog and CI logs.

## Verified by GitHub Actions

Run `30693594258` completed successfully. The original strict smoke remained green and all eight directed regressions passed.

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

memory_widths:
PASS: self-check exit=0 after 50 cycles, 29 committed instructions, stall-period=4

word_operations:
PASS: self-check exit=0 after 37 cycles, 28 committed instructions

branch_matrix:
PASS: self-check exit=0 after 38 cycles, 17 committed instructions

x0_writeback:
PASS: self-check exit=0 after 21 cycles, 12 committed instructions
```

## Coverage demonstrated

- SB/SH/SW/SD and LB/LH/LW/LD complete correctly;
- LBU/LHU/LWU zero-extend while signed loads sign-extend;
- memory transactions survive periodic `ready=0` without duplication or loss;
- ADDIW/SLLIW/SRLIW/SRAIW and ADDW/SUBW/SLLW/SRLW/SRAW produce sign-extended RV64 results;
- BEQ/BNE/BLT/BGE/BLTU/BGEU are correct in both taken and not-taken cases;
- x0 ignores writes;
- a consumer decoding during the producer's WB cycle receives the just-written value;
- the S0.3a forwarding, load-use, branch-flush and JAL/JALR tests remain green.

## Known non-fatal warnings

- Verilator reports the deliberately cleared JALR low bit as unused.
- Generated reset/randomization helper symbols are unused.
- Shift intermediates are wider than their selected architectural result.

## Next gate: S0.3c

Finish deterministic RV64I coverage before connecting an external reference model:

- XOR/OR/AND, SLT/SLTU and register/immediate 64-bit shifts;
- SUB and immediate arithmetic boundary values;
- LUI/AUIPC/JAL/JALR PC and link-address corner cases;
- FENCE/FENCE.I no-op retirement behavior;
- illegal-instruction and memory-fault retirement records;
- several reproducible memory-ready patterns rather than one fixed period.

After S0.3c, freeze the directed suite and begin commit-level NEMU/Spike DiffTest.
