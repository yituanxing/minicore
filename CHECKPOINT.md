# AetherCore S0.3d checkpoint

## Status

The normal RV64I directed suite is frozen at eleven self-checking programs, and the temporary exception contract now has three strict whole-core fault regressions. S0.3d found and fixed a real precise-exception bug: a younger Store could issue from MEM in the same cycle an older exception retired from WB.

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

## Precise-fault infrastructure

The Verilator harness can assert:

- exact exception PC and instruction;
- exact total retirement count;
- exactly one exception retirement;
- absence of a forbidden younger destination-register write;
- absence of younger UART/exit-MMIO effects;
- an expected 64-bit memory value after halt;
- deterministic memory backpressure through `--stall-period N`.

Fault cases:

- illegal instruction with an immediately younger exit-MMIO Store;
- faulting LD under periodic backpressure with an immediately younger exit-MMIO Store;
- faulting SD under periodic backpressure with an immediately younger valid-RAM Store and sentinel check.

## Defect found

Initial strict run `30694337230` kept all normal tests green but failed the first fault case:

```text
FAIL: younger MMIO side effect escaped past the fault
```

Root cause:

- the faulting instruction reached WB with `memWb.exception=1`;
- an immediately younger Store had already reached MEM;
- `io.dmem.valid` was still asserted during the exception-retirement cycle;
- the Store/MMIO side effect became visible before `haltedReg` took effect on the clock edge.

## RTL fix

The data-bus request is now suppressed combinationally while WB is retiring an exception:

```scala
val retiringException = memWb.valid && memWb.exception
io.dmem.valid := exMem.valid &&
  (exMem.ctrl.memRead || exMem.ctrl.memWrite) &&
  !exMem.exception &&
  !retiringException
```

This blocks all younger MEM requests in the precise exception boundary cycle while leaving normal memory behavior unchanged.

## Verified by GitHub Actions

Run `30694514557` passed the complete suite after the RTL fix.

Strict normal smoke:

```text
A
PASS: halted after 16 cycles, 7 committed instructions, x3=12, UART="A"
```

All eleven normal RV64I directed programs remained green.

Precise-fault results:

```text
illegal_instruction:
PASS: precise fault pc=0x80000008 inst=0xffffffff after 12 cycles, 3 committed instructions

load_bus_fault:
PASS: precise fault pc=0x8000000c inst=0x0000b103 after 13 cycles, 4 committed instructions, stall-period=3

store_bus_fault:
PASS: precise fault pc=0x80000018 inst=0x0020b023 after 16 cycles, 7 committed instructions, stall-period=4
```

The store-fault sentinel at `0x80000200` remained zero, proving the immediately younger valid-RAM Store was suppressed.

## Current verified boundary

- Chisel compilation and unit tests: PASS.
- CIRCT SystemVerilog generation: PASS.
- Verilator compile/link: PASS.
- strict smoke: PASS.
- eleven normal RV64I whole-core programs: PASS.
- three exact fault-boundary programs: PASS.
- deterministic memory-backpressure paths: PASS.

## Known non-fatal warnings

- Verilator reports the deliberately cleared JALR low bit as unused.
- Generated reset/randomization helper symbols are unused.
- Shift intermediates are wider than their selected architectural result.

## Next gate: S0.4 DiffTest foundation

Freeze the current directed suite and connect an external ISA reference model for normal, non-trapping RV64I commits:

- load NEMU or Spike as the reference implementation;
- initialize reference memory from the same binary image;
- execute exactly one reference instruction for each DUT commit;
- compare committed PC, instruction, destination write and memory side effects;
- preserve a rolling trace and stop at the first architectural mismatch;
- keep the precise-fault suite separate until the reference exception contract is wired explicitly.
