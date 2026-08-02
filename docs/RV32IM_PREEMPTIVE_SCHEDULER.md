# RV32IM timer-preemptive scheduler checkpoint

This checkpoint uses the merged Machine timer interrupt and MRET path to run two independent Machine-mode tasks under deterministic preemption.

It adds no RTL and no ISA feature. Its purpose is to prove that the existing interrupt boundary, register file, Load/Store path, CSR ordering and MRET return are reliable enough for complete software context switching.

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

Memory layout:

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

This obtains the interrupted frame pointer while preserving the interrupted `t0`. The handler stores `t1` before using it, recovers `t0` from `mscratch`, saves x1..x31 and `mepc`, selects the other frame, rearms `mtimecmp`, restores all state and executes MRET.

## Scheduling checks

The required eight-entry task-selection log is:

```text
B A B A B A B A
1 0 1 0 1 0 1 0
```

At tick eight the handler disables `mtimecmp` and restores task A. Task A then verifies eight interrupts, both progress counters, the exact alternation log, its stack and every persistent register before exiting with code zero.

## Frozen image

```text
bytes:   1432
words:   358
SHA-256: d62b691fe2ae85770418b3292c14e41f4a5ff16f2c9814483287b7a0cefd3eab

task_a:       0x80000140
task_b:       0x800001d0
trap_handler: 0x80000254
```

The deterministic encoder is `tools/make_rv32im_preemptive_scheduler.py`. Reference instruction semantics do not share that encoder.

## Backpressure matrix

```text
stall  cycles  retirements  Zicsr  MRET  IRQ
0       1509       1345        53     8    8
3       1671       1252        53     8    8
4       1639       1263        53     8    8
5       1578       1278        53     8    8
7       1589       1326        53     8    8
11      1553       1334        53     8    8
```

Every run must complete eight preemptions and exit with code zero.

## Dual-reference boundary

### Independent RV32IM shim

`sim/rv32_reference_shim.cpp` provides a dependency-light independent ordinary-instruction reference. The local gate records and replays only the eight `mtime` values observed for each stall schedule because a free-running timer is an asynchronous platform input, not predictable CPU state.

Register operations, branches, RAM loads/stores, passive MMIO stores, Zicsr shadows, MRET shadows and interrupt boundaries remain independently checked.

Frozen timer inputs:

```text
stall 0:  0x46,0xe8,0x18b,0x22d,0x2d0,0x372,0x415,0x4b7
stall 3:  0x59,0x10c,0x1bd,0x271,0x322,0x3d3,0x484,0x535
stall 4:  0x4a,0xfa,0x1ab,0x25a,0x30b,0x3ba,0x46b,0x51a
stall 5:  0x4d,0xf7,0x1a2,0x24b,0x2f6,0x39f,0x44a,0x4f3
stall 7:  0x4c,0xf6,0x19e,0x246,0x2ee,0x396,0x43e,0x4e6
stall 11: 0x48,0xed,0x193,0x238,0x2de,0x383,0x429,0x4ce
```

### Deterministic NEMU authority

The consolidated Full Gate builds and reuses:

```text
OpenXiangShan/NEMU revision:
8601834e4889e6bf3b6113eb5f824ba7689126f5

RV32 single-step reference SHA-256:
e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e

ABI:
uint32_t gpr[32]; uint32_t pc
```

NEMU's MMIO page is passive, so it cannot independently know the DUT's current free-running `mtime`. `tools/make_rv32im_scheduler_difftest.py` generates the scheduler adapter from the shared timer adapter and, immediately before NEMU executes an `mtime` load, copies only the DUT-observed load bytes into that passive mapping. NEMU then executes the load and remains authoritative for the destination register and every following ordinary instruction.

No register result is copied into NEMU after execution, and ordinary RAM, CSR, MRET, interrupt and Store semantics remain independently verified. The generator requires exactly one execution hook and one timer-method insertion point, so source drift fails immediately rather than silently weakening the reference.

## Precise interrupt sequence

For `stall-period=5`, VCD inspection must find 1,278 retirements and exactly eight interrupt events:

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

`tools/check_rv32im_scheduler_vcd.py` requires the complete sequence, not only the count.

## Negative probes

At zero-based event 152 the reference path flips x31 and must reject the stream after exactly 152 matched events.

The frozen image restores x8 with:

```text
word 234: lw x8, 32(x5) = 0x0202a403
```

`tools/corrupt_rv32im_scheduler_context.py` replaces it with NOP. The corrupted workload must exit with status 12 and report:

```text
FAIL: self-check program returned code 12
```

## Consolidated CI

The scheduler is one visible phase in `AetherCore Full Gate`. It reuses the fixed Verilator toolchain and the already-built single-step RV32 NEMU reference, then runs:

1. image and label freezing;
2. six independent-shim schedules;
3. six deterministic-NEMU schedules;
4. context-corruption rejection;
5. eight-boundary VCD freezing;
6. first-interrupt mismatch rejection.

The workflow contains exactly one self-hosted job, so no scheduler-specific or historical-name jobs remain queued after the real verification finishes.

## Scope boundary

This checkpoint does not add runtime U/S mode, process isolation, syscalls, virtual memory, atomic instructions, external interrupts or a general RTOS API.

The preferred next architecture checkpoint is U-mode plus ECALL/syscall return, turning the proven Machine-mode scheduler mechanism into privilege isolation.
