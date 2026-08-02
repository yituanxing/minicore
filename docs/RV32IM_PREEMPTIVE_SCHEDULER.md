# RV32IM timer-preemptive scheduler checkpoint

This stacked checkpoint uses the Machine timer interrupt and MRET path from PR #28 to run two independent Machine-mode tasks under deterministic preemption.

It intentionally adds no RTL and no ISA feature. The purpose is to determine whether the existing interrupt boundary, register file, Load/Store path, CSR ordering and MRET return are already reliable enough to support real context switching.

## Execution model

```text
task A
  -> Machine timer interrupt
  -> save x1..x31 and mepc into context A
  -> select context B
  -> restore x1..x31 and mepc
  -> MRET to task B
  -> repeat for eight ticks
```

The workload uses:

```text
context A: 0x80001000
context B: 0x80001100
stack A:   0x80002000
stack B:   0x80003000
shared:    0x80004000
```

Each context contains one 32-bit slot for every integer register x1..x31 and one slot for `mepc`.

## mscratch handoff

`mscratch` always holds the running task's context pointer. Interrupt entry starts with:

```text
csrrw t0, mscratch, t0
```

After this instruction:

- `t0` points to the interrupted task frame;
- `mscratch` preserves the interrupted value of `t0`;
- the handler stores `t1` before using it;
- the handler reads the original `t0` from `mscratch`;
- every x1..x31 value and `mepc` is saved.

Before MRET, the selected frame pointer is written back to `mscratch`. All registers are restored, with `t1` and `t0` loaded last so the frame base remains available through the final memory access.

## Task identity checks

Task A and task B have different:

- stack pointers;
- s0/s1 values;
- s2..s11 values;
- shared progress counters.

Every loop validates its complete persistent signature before updating its own counter. A corrupted or cross-contaminated context exits with a nonzero failure code.

## Scheduling checks

The handler records the selected task on every interrupt. The required eight-entry log is:

```text
B A B A B A B A
1 0 1 0 1 0 1 0
```

At tick eight the handler disables `mtimecmp` and restores task A. Task A then verifies:

- exactly eight timer interrupts occurred;
- both task counters are nonzero;
- the alternation log is exact;
- task A's stack and all persistent registers survived four complete save/restore cycles;
- exit code is zero.

## Deterministic image

The generated image is frozen at:

```text
bytes:   1432
words:   358
SHA-256: d62b691fe2ae85770418b3292c14e41f4a5ff16f2c9814483287b7a0cefd3eab
```

Key labels:

```text
task_a:       0x80000140
task_b:       0x800001d0
trap_handler: 0x80000254
```

The image is produced without an external RISC-V compiler by the deterministic encoder in `tools/make_rv32im_preemptive_scheduler.py`. The final frozen NEMU workflow remains an independent execution reference rather than sharing that encoder's instruction semantics.

## Backpressure matrix

The workload was executed against the validated PR #28 RTL simulator under six memory-backpressure schedules:

```text
stall  cycles  retirements
0       1509       1345
3       1671       1252
4       1639       1263
5       1578       1278
7       1589       1326
11      1553       1334
```

Every run completed eight preemptions and exited with code zero.

## Fast independent RV32IM reference

`sim/rv32_reference_shim.cpp` implements an independent NEMU-compatible RV32IM execution ABI for fast local checking. Ordinary instructions execute in the shim; Zicsr, MRET and timer interrupt transitions continue through the explicit shadows in `nemu_difftest_rv32_timer.cpp`.

`mtime` is an external asynchronous input, not CPU state. The local gate therefore records the eight timer-read values for each backpressure schedule and replays only those values into the independent reference. Register operations, branches, RAM loads/stores and passive MMIO stores are still computed independently.

All six schedules matched continuously:

```text
stall  matched events  Zicsr  MRET  timer IRQ
0               1345      53     8          8
3               1252      53     8          8
4               1263      53     8          8
5               1278      53     8          8
7               1326      53     8          8
11              1334      53     8          8
```

The exact timer-input replays were:

```text
stall 0:  0x46,0xe8,0x18b,0x22d,0x2d0,0x372,0x415,0x4b7
stall 3:  0x59,0x10c,0x1bd,0x271,0x322,0x3d3,0x484,0x535
stall 4:  0x4a,0xfa,0x1ab,0x25a,0x30b,0x3ba,0x46b,0x51a
stall 5:  0x4d,0xf7,0x1a2,0x24b,0x2f6,0x39f,0x44a,0x4f3
stall 7:  0x4c,0xf6,0x19e,0x246,0x2ee,0x396,0x43e,0x4e6
stall 11: 0x48,0xed,0x193,0x238,0x2de,0x383,0x429,0x4ce
```

This local reference does not replace the fixed OpenXiangShan/NEMU revision. It provides a second, dependency-light reference while the final workflow still requires the exact frozen NEMU shared object.

## Precise interrupt sequence

For `stall-period=5`, VCD inspection found 1,278 retirement events and exactly eight interrupt events:

```text
IRQ  event  retiring PC  instruction  cause       resume PC
0      152  0x80000140   0x800022b7   0x80000007  0x80000144
1      288  0x80000250   0xf81ff06f   0x80000007  0x800001d0
2      426  0x800001c8   0x24737663   0x80000007  0x800001cc
3      562  0x80000250   0xf81ff06f   0x80000007  0x800001d0
4      698  0x800001b8   0x00130313   0x80000007  0x800001bc
5      834  0x80000250   0xf81ff06f   0x80000007  0x800001d0
6      972  0x800001b0   0x00028293   0x80000007  0x800001b4
7     1108  0x80000250   0xf81ff06f   0x80000007  0x800001d0
```

The interrupt lands on different task-A instructions and repeatedly at the task-B loop boundary. Each event retires its current instruction, captures the exact oldest younger PC, enters the handler and later resumes through MRET.

`tools/check_rv32im_scheduler_vcd.py` requires the complete sequence above, not only the interrupt count.

## Negative probes

### Reference mismatch

The first interrupt is zero-based event 152. Both the local shim and final NEMU workflow deliberately flip x31 at that event and require rejection after exactly 152 matched events:

```text
RV32 timer DiffTest mismatch after 152 matched events
x31 reference=0x00000000 DUT=0x00000001
```

### Context restore corruption

The frozen image restores x8 with:

```text
word 234: lw x8, 32(x5) = 0x0202a403
```

`tools/corrupt_rv32im_scheduler_context.py` replaces this instruction with NOP. The corrupted workload deterministically fails on the first restored task-B signature check:

```text
process status: 12
FAIL: self-check program returned code 12
```

This proves the workload actively checks register restoration rather than only interrupt count or eventual control flow.

## Final reference boundary

The strict workflow requires both:

1. the fast independent RV32IM shim across all six backpressure schedules;
2. the exact frozen OpenXiangShan/NEMU RV32 single-step shared object across the same schedules.

The pinned NEMU boundary remains:

```text
revision: 8601834e4889e6bf3b6113eb5f824ba7689126f5
SHA-256:  1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
ABI:      uint32_t gpr[32]; uint32_t pc
```

GitHub Actions is currently rejecting private-repository jobs before checkout with zero steps and no logs. The PR must remain Draft until that account-level execution block is removed and the strict dual-reference workflow completes normally.

## Scope boundary

This checkpoint does not add:

- runtime U-mode or S-mode;
- process isolation;
- syscalls;
- virtual memory;
- atomic instructions;
- external interrupts;
- a general-purpose RTOS API.

After this scheduler is frozen, the next architectural choice should be based on real software demand: U-mode plus ECALL for isolated tasks, or the A extension for a larger RTOS/concurrency workload.
