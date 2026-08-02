# RV32IM precise machine synchronous trap checkpoint

This checkpoint converts the existing precise fault boundary from a simulator stop condition into architectural Machine-mode trap entry.

The five-stage pipeline remains the same. A synchronous fault is carried as structured metadata to the WB boundary, where the core updates machine CSRs, suppresses the faulting instruction's side effects, flushes every younger stage and redirects instruction fetch to direct-mode `mtvec`.

`mret`, interrupts, delegation, S/U privilege transitions and virtual memory remain outside this checkpoint.

## Implemented synchronous exceptions

```text
cause  exception
1      instruction access fault
2      illegal instruction
3      breakpoint
5      load access fault
7      store/AMO access fault
11     environment call from M-mode
```

The current `mtval` policy is deterministic:

```text
instruction access fault  faulting instruction address
illegal instruction       zero-extended instruction bits
breakpoint                breakpoint PC
load access fault         effective load address
store access fault        effective store address
M-mode ECALL              zero
```

## Precise retirement boundary

A trap event is reported at the same architectural boundary used by normal retirement. The faulting instruction:

- does not write a GPR;
- does not write a CSR through the ordinary Zicsr path;
- does not expose a committed Load/Store event;
- cannot allow a younger Store or MMIO request to escape.

On the trap-entry edge the core:

```text
mepc        <- faulting PC, WARL aligned
mcause      <- synchronous exception cause
mtval       <- exception-specific value
mstatus.MPIE <- previous mstatus.MIE
mstatus.MIE  <- 0
mstatus.MPP  <- M
PC           <- direct-mode mtvec base
```

The IF/ID, ID/EX, EX/MEM and MEM/WB valid state is cleared. Fetch resumes from the trap handler after the edge.

Trap entry has priority over an ordinary CSR write in the same retirement cycle.

## Simulation boundary

The legacy `AetherCoreSimTop` retains a non-architectural `stopOnTrap` option so existing smoke and fault-boundary tools can stop after observing a trap event.

The new `AetherCoreRV32IMTrapSimTop` disables that debugger behavior. It allows the core to enter and execute the real trap handler, and is used by this checkpoint's six software workloads.

## Real software workloads

Six independent freestanding RV32IM binaries are compiled with GCC 13.2.0:

```text
-march=rv32im_zicsr
-mabi=ilp32
-O2
```

Each process:

1. writes `mtvec`;
2. enables `mstatus.MIE`;
3. initializes a sentinel word;
4. triggers exactly one selected synchronous exception;
5. enters a common handler without using `mret`;
6. reads `mcause`, `mepc`, `mtval` and `mstatus`;
7. validates the expected values;
8. verifies that a younger Store did not change the sentinel;
9. exits through the platform MMIO exit address.

The six frozen binaries are:

```text
case         bytes  words  SHA-256
ecall          256     64  0b7d27beeb7f1b515dcbfdc1de0cc0a4efc07d6e4e41b83e51f4d64dd186b127
ebreak         260     65  24fd161632ab8b734326ba912628a84eaa5ca9c9a48e12a30918e7dd7416d5d5
illegal        256     64  7ba0bd080db3689597603ec0ad2bf287eedcb55141bb0a111f984ebaeeb291d8
load-fault     260     65  54d93e5f79730171dd938f6be9a4b780c293595530783cf6a43dbeb2b7b8abb2
store-fault    260     65  f5d23a68afc0c7c29b594ce52cf1130356a4e476c877edaafd7418b3b4fc7189
fetch-fault    256     64  84c81e012b857ead633f6063743014294790f969b0e3b13f52c00125ed892122
```

## Independent reference boundary

The exact frozen RV32 NEMU reference remains unchanged:

```text
revision: 8601834e4889e6bf3b6113eb5f824ba7689126f5
SHA-256:  1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
ABI:      uint32_t gpr[32]; uint32_t pc
```

The continuous reference stream uses three independent transition sources:

- ordinary RV32IM instructions execute in the frozen NEMU reference;
- Zicsr instructions execute in the repository-owned machine-CSR shadow model;
- trap events execute in a separate precise-trap shadow transition.

For a trap event the reference independently validates the cause and `mtval`. For load and store faults it decodes the instruction and recomputes the effective address from the pre-trap GPR state. It then updates the shadow machine CSRs, computes the next PC from `mtvec`, copies GPR/PC back into NEMU and continues with ordinary execution inside the handler.

The trap shadow is not reused DUT logic. It is a C++ retirement-level specification model separate from the Chisel implementation.

## Frozen execution results

```text
case         cycles  events  NEMU steps  Zicsr steps  trap steps
ecall            61      41          34             6           1
ebreak           62      42          35             6           1
illegal          61      41          34             6           1
load-fault       62      42          35             6           1
store-fault      62      42          35             6           1
fetch-fault      64      42          35             6           1
------------------------------------------------------------------
total           372     250         208            36           6
```

Every handler exits with code zero. Every workload contains exactly one trap event.

The negative probe targets event index 18 of the ECALL workload, which is the ECALL trap event itself. It corrupts x31 and must be rejected after exactly 18 matched events.

## Preserved gates

This checkpoint must leave the established gates unchanged:

```text
RV64 frozen normal-retirement gate:    944,407
RV32I GCC DiffTest:                         585
RV32IM CoreMark DiffTest:               646,301
RV32IM Embench batch 1:                 184,185
RV32IM Embench batch 2:               1,144,895
RV32IM littlefs basic:                4,819,485
RV32IM machine CSR:                         65
```

## Next checkpoint

The next step is trap return. `MRET` will restore the previous interrupt-enable state and privilege mode, redirect PC to `mepc`, and allow one program to trigger multiple exceptions, advance `mepc` past a handled instruction and continue normal execution.
