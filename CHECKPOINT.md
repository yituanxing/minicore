# AetherCore privileged architecture checkpoint

## Branch and PR state

Stable `main` before this checkpoint:

```text
14cce6cb633f3140fd57dd34fa811bb0e36d7f5b
```

Current architecture PR:

```text
branch: agent/rv32im-machine-timer-interrupt
PR:     #28 — add precise RV32IM machine timer interrupts
state:  Draft
```

PR #28 must remain Draft until GitHub Actions can execute again and the final head receives one complete all-green run.

## CPU baseline

- Chisel five-stage in-order pipeline: IF, ID, EX, MEM, WB.
- RV32I, RV32IM/Zicsr and RV64IM/Zicsr elaboration-time profiles.
- EX/MEM and MEM/WB forwarding.
- Load-use interlock and branch/jump recovery.
- Forwarded operands preserved across data-memory backpressure.
- Precise architectural commit trace.
- Younger GPR, Store and MMIO effects suppressed at architectural redirects.
- Multiply/divide remains combinational and correctness-first.

## Privileged architecture already merged on main

### Machine CSRs and Zicsr

Implemented:

```text
mstatus   0x300
misa      0x301
mtvec     0x305
mscratch  0x340
mepc      0x341
mcause    0x342
mtval     0x343
```

Supported instructions:

```text
CSRRW / CSRRS / CSRRC
CSRRWI / CSRRSI / CSRRCI
```

WARL handling is implemented for `mstatus`, `mtvec` and `mepc`.

### Precise synchronous traps

Implemented causes:

- instruction access fault;
- illegal instruction;
- breakpoint;
- Load access fault;
- Store access fault;
- Machine ECALL.

Trap entry occurs only at WB. The faulting instruction exposes no forbidden GPR or memory side effect, and every younger stage is flushed.

### MRET

`MRET` retires as a normal non-exception event at WB:

```text
mstatus.MIE  <- mstatus.MPIE
mstatus.MPIE <- 1
mstatus.MPP  <- least supported privilege
PC           <- mepc
```

The current M-only software profile observes:

```text
handler mstatus = 0x00001880
returned status = 0x00001888
```

The merged MRET checkpoint covers ECALL, EBREAK with rewritten `mepc`, Load-fault recovery and two complete trap/return loops:

```text
cycles:       368
retirements:  259
Zicsr shadow: 59
trap shadow:  5
MRET shadow:  5
```

## Machine timer architecture in PR #28

Added CSRs:

```text
mie.MTIE  bit 7, writable
mip.MTIP  bit 7, read-only platform input
```

Platform timer:

```text
mtimecmp  0x02004000
mtime     0x0200bff8
```

The timer is 64-bit. RV32 uses low/high 32-bit accesses; RV64 uses full-width accesses. `mtimecmp` resets to all ones, so all historical programs remain timer-inert unless software explicitly enables and programs it.

A Machine timer interrupt is eligible only when:

```text
mtime >= mtimecmp
mstatus.MIE == 1
mie.MTIE == 1
```

Cause values:

```text
RV32 mcause = 0x80000007
RV64 mcause = 0x8000000000000007
mtval       = 0
```

## Precise asynchronous boundary

The current WB instruction retires normally. Interrupt acceptance then:

1. preserves that instruction's GPR, Store or CSR effect;
2. selects the oldest younger PC from EX/MEM, ID/EX, IF/ID or current fetch;
3. stores that PC in `mepc`;
4. blocks a younger MEM/MMIO request combinationally;
5. flushes all younger pipeline state;
6. redirects to `mtvec`.

A same-boundary retiring write to `mstatus`, `mie`, `mtvec` or `mscratch` is applied before interrupt entry. Synchronous traps have higher priority than interrupts, and MRET is not interrupted at its own retirement boundary.

## Real timer workload evidence

Functional head:

```text
6eec8ff6c7d37941f92458e8afee0d998c3620a5
```

Successful workflow and artifact:

```text
workflow:     RV32IM Machine Timer 30737277090
artifact:     rv32im-machine-timer-30737277090
artifact ID:  8830057359
ZIP SHA-256:  79e9c0717b0f14e27e416a8561005299dda4df2b5627cb911331733b82902528
```

Frozen results:

```text
case          cycles  events  Zicsr  MRET  timer IRQ
basic            311     150      16     1          1
global-mask      622     341      17     1          1
source-mask      622     341      17     1          1
double           600     341      23     2          2
----------------------------------------------------
total          2,155   1,173      73     5          5
```

The workloads prove:

- ordinary enabled timer delivery;
- pending MTIP while global `mstatus.MIE` is clear;
- pending MTIP while source `mie.MTIE` is clear;
- comparator rearming in the handler;
- two complete asynchronous interrupt/MRET loops.

First interrupt:

```text
zero-based event: 94
retiring PC:      0x80000074
cause:            0x80000007
resume PC:        0x80000074
```

The equal retiring/resume address is two consecutive dynamic iterations of the same wait-loop branch: one retires, the next is flushed and replayed.

Full details are in `docs/RV32IM_MACHINE_TIMER.md`.

## Frozen normal-retirement gates

RV64:

```text
directed/generated architecture:       3,119
compiled real programs:               204,218
CoreMark:                             737,070
---------------------------------------------
RV64 exact total:                    944,407
```

RV32 real software:

```text
RV32I GCC DiffTest:                     585
RV32IM CoreMark:                    646,301
RV32IM Embench batch 1:             184,185
RV32IM Embench batch 2:           1,144,895
RV32IM littlefs basic:             4,819,485
---------------------------------------------
RV32 exact total:                  6,795,451
```

Do not merge RV32 and RV64 totals without explicitly labelling a cross-profile aggregate.

## Reference boundary

Normal instruction references remain pinned to OpenXiangShan/NEMU:

```text
revision: 8601834e4889e6bf3b6113eb5f824ba7689126f5
RV32 reference SHA-256:
1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
ABI: uint32_t gpr[32]; uint32_t pc
```

Reference partitioning:

```text
ordinary RV32IM/RV64IM instructions -> frozen NEMU
Zicsr instructions                  -> independent CSR shadow
synchronous trap entry              -> independent trap shadow
MRET                                -> independent return shadow
Machine timer acceptance            -> independent interrupt shadow
```

No privileged event is silently skipped.

## Current blocker

All private-repository GitHub Actions jobs currently fail before checkout with:

```text
steps: []
no job log
rerun fails identically
```

This affects historical workflows as well as PR #28 and PR #30. Do not modify RTL in response to a zero-step runner failure. Check the repository/account Actions budget and billing state, then rerun the unchanged final head.

## Stacked next checkpoint

PR #30 is stacked on PR #28:

```text
branch: agent/rv32im-preemptive-scheduler
PR:     #30 — run a timer-preemptive two-task scheduler
```

It adds no RTL. It uses MTIP/MRET to save and restore x1..x31 plus `mepc` across two independent task stacks for eight timer-driven preemptions.

Correct completion order:

1. restore GitHub Actions execution;
2. rerun PR #28 and require every historical plus timer workflow green;
3. mark PR #28 Ready and squash merge;
4. retarget/rebase PR #30 onto the new `main`;
5. run PR #30's dual-reference scheduler matrix;
6. only then consider U-mode/ECALL or the A extension.

## Known limitations

- Runtime privilege is still Machine mode only.
- No U-mode or S-mode execution.
- No delegation or supervisor timer interrupt.
- No software/external interrupt controller.
- No WFI.
- Single hart only.
- No A extension.
- No MMU, page tables or caches.
- Timer peripheral is simulation-platform logic, not yet a reusable production CLINT/ACLINT block.
