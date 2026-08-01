# AetherCore S0.2 checkpoint

## Status

The first real Chisel-to-Verilator execution path is green. S0.2 is the stable bring-up checkpoint for the five-stage RV64I core and its architectural commit interface.

## Implemented

- IF/ID/EX/MEM/WB in-order pipeline.
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
- GitHub Actions build and artifact pipeline.

## Verified by GitHub Actions

Run `30692781114` completed successfully on Ubuntu 24.04 with Java 21, Mill 1.1.2, Chisel 7.7.0 and Verilator 5.020.

Passed gates:

- Python ISA reference test.
- Chisel compilation.
- ALU unit test.
- Decoder unit test.
- Chisel full-core smoke test.
- CIRCT SystemVerilog generation.
- Verilator C++ compilation and link.
- RTL execution with strict architectural assertions.

Strict RTL smoke result:

```text
A
PASS: halted after 16 cycles, 7 committed instructions, x3=12, UART="A"
```

The harness samples commit and MMIO events before the accepting rising edge. This removed the earlier incorrect eight-instruction count and aligned the RTL result with the ISA reference model.

## Known non-fatal warnings

- Verilator reports the deliberately cleared JALR low bit as unused.
- Generated reset/randomization helper symbols are unused.
- Shift intermediates are wider than their selected architectural result.

These warnings do not affect S0.2 correctness but should be cleaned up before tightening lint to fatal warnings.

## Next gate: S0.3

Do not add privileged architecture yet. First expand deterministic RV64I regression coverage for:

- ALU dependency forwarding.
- load-use stalls.
- taken/not-taken branches and wrong-path store suppression.
- JAL/JALR recovery.
- byte/half/word/dword loads and stores.
- signed and unsigned loads.
- W-class sign extension.
- randomized memory-ready backpressure.
- x0 and same-cycle writeback corner cases.
