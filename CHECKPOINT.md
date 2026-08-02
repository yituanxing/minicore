# AetherCore preemptive scheduler checkpoint

## Stack state

Stable `main`:

```text
14cce6cb633f3140fd57dd34fa811bb0e36d7f5b
```

Architecture dependency:

```text
PR #28
branch: agent/rv32im-machine-timer-interrupt
head:   430a32b63d232227893e1e89c4a09748f4eece3c
state:  Draft
```

Current software-driven checkpoint:

```text
PR #30
branch: agent/rv32im-preemptive-scheduler
state:  Draft, stacked on PR #28
```

PR #30 must not be merged or retargeted to `main` until PR #28 passes its final all-green run and merges.

## Architecture available from PR #28

- RV32IM and RV64IM five-stage in-order pipeline.
- Zicsr Machine CSR instructions.
- `mstatus`, `misa`, `mie`, `mtvec`, `mscratch`, `mepc`, `mcause`, `mtval`, `mip`.
- Precise synchronous traps at WB.
- Precise MRET at WB.
- 64-bit memory-mapped `mtime` / `mtimecmp`.
- Machine timer interrupt cause `interrupt-bit | 7`.
- Current WB instruction retires before interrupt entry.
- Oldest younger PC is stored in `mepc` and replayed after MRET.
- Younger Store/MMIO effects are suppressed in trap, interrupt and MRET redirect cycles.
- Same-boundary CSR writes are observed before interrupt entry.

PR #30 changes no RTL and adds no ISA feature.

## Scheduler execution model

Two independent Machine-mode tasks are preempted for eight timer ticks:

```text
task A
  -> timer interrupt
  -> save x1..x31 + mepc to context A
  -> restore context B
  -> MRET to task B
  -> repeat with A/B alternation
```

Memory layout:

```text
context A: 0x80001000
context B: 0x80001100
stack A:   0x80002000
stack B:   0x80003000
shared:    0x80004000
```

`mscratch` always holds the running context pointer. Handler entry uses:

```text
csrrw t0, mscratch, t0
```

This obtains the current frame pointer while preserving the interrupted `t0`. The handler saves the interrupted `t1` before using it, recovers `t0`, stores every x1..x31 value and `mepc`, selects the other context, rearms `mtimecmp`, restores all state and executes MRET.

The selected-task log must be exactly:

```text
B A B A B A B A
1 0 1 0 1 0 1 0
```

## Frozen image

```text
bytes:   1432
words:   358
SHA-256: d62b691fe2ae85770418b3292c14e41f4a5ff16f2c9814483287b7a0cefd3eab
```

Labels:

```text
task_a:       0x80000140
task_b:       0x800001d0
trap_handler: 0x80000254
```

## Verified backpressure matrix

```text
stall  cycles  retirements  Zicsr  MRET  IRQ
0       1509       1345        53     8    8
3       1671       1252        53     8    8
4       1639       1263        53     8    8
5       1578       1278        53     8    8
7       1589       1326        53     8    8
11      1553       1334        53     8    8
```

Every run completed eight context switches and exited with code zero.

## Dual reference boundary

### Fast local reference

`sim/rv32_reference_shim.cpp` implements an independent NEMU-compatible RV32IM execution ABI. It executes ordinary instructions separately from the DUT.

`mtime` is an asynchronous platform input, so the local gate records and replays only the timer-read values for each schedule. Register semantics, branches, RAM accesses and Store bytes are independently computed.

All six schedules matched continuously. At `stall=5`:

```text
retirements / matched events: 1278 / 1278
Zicsr shadow:                  53
MRET shadow:                    8
interrupt shadow:               8
```

### Final frozen NEMU reference

CI still requires:

```text
OpenXiangShan/NEMU revision:
8601834e4889e6bf3b6113eb5f824ba7689126f5

RV32 single-step reference SHA-256:
1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577

ABI:
uint32_t gpr[32]; uint32_t pc
```

The local shim is a second fast reference, not a replacement for the frozen NEMU gate.

## Exact interrupt sequence

For `stall=5`:

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

`tools/check_rv32im_scheduler_vcd.py` requires the complete sequence.

## Negative probes

### Reference mismatch

At zero-based event 152, CI flips x31 and requires:

```text
RV32 timer DiffTest mismatch after 152 matched events
```

This is verified locally against the independent reference shim.

### Context corruption

The restore instruction:

```text
word 234: lw x8, 32(x5) = 0x0202a403
```

is replaced with NOP. The workload must fail with:

```text
status 12
FAIL: self-check program returned code 12
```

This proves register restoration is actively checked.

## Current GitHub Actions blocker

Private-repository jobs currently fail before checkout with zero steps and no logs. The same failure affects historical workflows, PR #28 and PR #30.

Do not modify CPU RTL to address a zero-step Actions failure. Check repository/account Actions budget and billing, then rerun the unchanged heads.

## Correct completion order

1. Restore Actions execution.
2. Rerun PR #28 from head `430a32b63d232227893e1e89c4a09748f4eece3c`.
3. Require all historical architecture/software gates plus Machine Timer green.
4. Mark PR #28 Ready and squash merge.
5. Rebase or retarget PR #30 onto the new `main`.
6. Run the PR #30 fast-shim and frozen-NEMU scheduler matrices.
7. Require VCD and both negative probes.
8. Mark PR #30 Ready and squash merge.

## Next architecture choice after PR #30

Do not add features before the scheduler checkpoint is frozen.

Then choose based on real software demand:

- **U-mode + ECALL/syscall return** for isolated user tasks; or
- **A extension** for a larger RTOS/concurrency workload.

The preferred next step is U-mode because the scheduler already demonstrates Machine-mode context switching; U-mode would turn that mechanism into actual privilege isolation rather than adding another execution-unit feature.

## Known limitations

- Runtime privilege is Machine mode only.
- No U-mode or S-mode execution.
- No delegation.
- No software or external interrupts.
- No WFI.
- Single hart.
- No A extension.
- No MMU, page tables or caches.
- Timer is simulation-platform logic rather than a reusable production CLINT/ACLINT block.
