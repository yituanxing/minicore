# AetherCore preemptive scheduler checkpoint

## Repository state

Architecture and consolidated CI base:

```text
main: c690bee17fa7563f74541c99f4a667d7b7e8d79e
```

Current software-driven checkpoint:

```text
PR:     #33
branch: agent/rv32im-preemptive-scheduler-main
state:  Draft until the exact-head Full Gate passes
```

This checkpoint is a clean one-commit replay on `main`. It changes scheduler software, documentation and verification only. It changes no CPU RTL and adds no ISA feature.

## Architecture used by the scheduler

- RV32IM and RV64IM five-stage in-order pipeline.
- Zicsr Machine CSR instructions.
- `mstatus`, `misa`, `mie`, `mtvec`, `mscratch`, `mepc`, `mcause`, `mtval`, `mip`.
- Precise synchronous traps and MRET at WB.
- 64-bit memory-mapped `mtime` / `mtimecmp`.
- Machine timer interrupt cause `interrupt-bit | 7`.
- Current WB instruction retires before interrupt entry.
- The oldest younger PC is stored in `mepc` and replayed after MRET.
- Younger Store/MMIO effects are suppressed during trap, interrupt and MRET redirects.
- Same-boundary CSR writes become architectural before interrupt entry.

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

`mscratch` holds the running context pointer. Handler entry uses:

```text
csrrw t0, mscratch, t0
```

This obtains the current frame pointer while preserving interrupted `t0`. The handler saves interrupted `t1`, recovers `t0`, stores x1..x31 and `mepc`, selects the other context, rearms `mtimecmp`, restores all state and executes MRET.

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

task_a:       0x80000140
task_b:       0x800001d0
trap_handler: 0x80000254
```

## Frozen backpressure matrix

```text
stall  cycles  retirements  Zicsr  MRET  IRQ
0       1509       1345        53     8    8
3       1671       1252        53     8    8
4       1639       1263        53     8    8
5       1578       1278        53     8    8
7       1589       1326        53     8    8
11      1553       1334        53     8    8
```

Every run must complete eight context switches and exit with code zero.

## Dual-reference boundary

### Independent local reference

`sim/rv32_reference_shim.cpp` implements an independent NEMU-compatible RV32IM execution ABI. It computes ordinary instruction semantics separately from the DUT.

`mtime` is an asynchronous platform input, so the local gate records and replays only timer-read values for each schedule. Register semantics, branches, RAM accesses and Store bytes remain independently computed.

For `stall=5` the frozen boundary is:

```text
retirements / matched events: 1278 / 1278
Zicsr shadow:                  53
MRET shadow:                    8
interrupt shadow:               8
```

### Final deterministic NEMU reference

The consolidated Full Gate builds the shared RV32 reference once and reuses it for the scheduler:

```text
OpenXiangShan/NEMU revision:
8601834e4889e6bf3b6113eb5f824ba7689126f5

RV32 single-step reference SHA-256:
e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e

ABI:
uint32_t gpr[32]; uint32_t pc
```

The local shim is a second fast reference, not a replacement for the deterministic NEMU authority.

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

`tools/check_rv32im_scheduler_vcd.py` freezes the complete sequence.

## Negative probes

### Reference mismatch

At zero-based event 152, the gate flips x31 and requires:

```text
RV32 timer DiffTest mismatch after 152 matched events
```

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

## Consolidated validation path

The scheduler no longer owns an independent GitHub workflow. `AetherCore Full Gate` performs, in order:

1. fixed toolchain, Python and Chisel gates;
2. RV64 RTL and NEMU matrices;
3. one optimized and one single-step deterministic RV32 NEMU build;
4. RV32 GCC, CSR, traps, MRET and timer gates;
5. the complete scheduler image/reference/matrix/VCD/negative-probe phase;
6. RV64 and RV32 real-program workloads;
7. one consolidated evidence upload.

This avoids rebuilding the same RV32 NEMU reference in a separate scheduler job and makes the current phase visible in one ordered pipeline.

## Completion order

1. Require the Full Gate to pass on the exact PR #33 head.
2. Freeze the workflow run, artifact ID and artifact SHA-256 in the PR description.
3. Confirm zero unresolved review threads and zero RTL changes.
4. Mark PR #33 Ready and squash merge.
5. Start the next architecture checkpoint only from the resulting `main`.

## Next architecture choice

The preferred next checkpoint is **U-mode plus ECALL/syscall return**. The scheduler already proves Machine-mode context switching; U-mode turns that mechanism into privilege isolation rather than merely adding another execution-unit feature.

A later alternative is the A extension for broader RTOS/concurrency workloads.

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
