# AetherCore v2 F8 — performance baseline contract

Frozen base: `418a4b798410df45c0218f1a0122cdf0199211f6` (F7 software parity).

F8 begins performance work without reopening F7 architectural ownership. The first step is measurement, not a scheduler rewrite.

## Non-negotiable invariants

The following remain unchanged unless a later, separately qualified milestone explicitly says otherwise:

- precise in-order Commit remains the sole architectural RF/CSR/trap/store-visibility owner;
- `RobToken`, `ProducerTag`, `ValueRef`, and physical memory transaction identity remain distinct;
- stores and atomic writers externalize only with exact Commit-derived head permission;
- exceptions, interrupts and xRET remain precise architectural boundaries;
- unchanged OpenSBI/Linux/RTOS workloads and existing exact-head qualification gates remain the correctness reference;
- F7 frozen head never moves.

## Baseline questions

Before changing issue policy, measure where cycles are lost:

1. frontend cannot supply/accept a new instruction;
2. ROB full / dispatch blocked;
3. live ROB head is waiting on source dependencies;
4. head is ready but its execution resource cannot accept it;
5. memory head is blocked by the one-outstanding LSU;
6. PTW / address translation is active or blocking progress;
7. system/privileged serialization blocks younger work;
8. completed work waits at Commit;
9. asynchronous interrupt/WFI clean-boundary drain time;
10. productive issue/retire cycles and achieved IPC.

Counters are diagnostic/simulation observability only. They must not affect architectural behavior or timing decisions.

## Required first measurements

At minimum report:

- cycles;
- commits;
- IPC;
- dispatch accepted / dispatch blocked;
- ROB occupancy histogram (0..4);
- issue fires by class: integer/branch, mul-div, memory, system;
- head-not-ready cycles;
- head-ready-but-not-issued cycles;
- LSU busy cycles and memory request/response counts;
- PTW-active / translation-stall cycles where observable;
- commit-idle with non-empty ROB cycles;
- privileged/interrupt serialization cycles.

Each stall category must have an explicit predicate and should avoid double-counting where practical. A hierarchical attribution is preferred over a misleading sum of overlapping counters.

## First expected optimization

If measurement confirms head-of-line dependency blocking is material, the first bounded microarchitectural change is **oldest-ready single issue** over the existing small live window, not wide issue.

Initial safety policy:

- preserve single issue per cycle;
- preserve in-order Commit;
- allow younger execution only for classes proven side-effect-free before Commit;
- keep stores, atomics, CSR/system operations, fences and other externally visible/serializing operations conservative/head-ordered initially;
- branch/recovery semantics must retain exact lifetime ownership;
- no PRF/free-list, LDQ/STQ, speculative load replay, caches/MSHRs, multi-issue or multi-retire in this first step.

The goal is to isolate the value of selective issue before adding larger structures.

## Evaluation sequence

1. freeze baseline counters and counter-contract tests;
2. collect focused microbenchmark and real OpenSBI/Linux samples;
3. identify dominant stalls by percentage of cycles;
4. implement one bounded mechanism;
5. compare cycles/commits/stall distribution against the exact baseline;
6. run all correctness gates on the candidate exact head;
7. keep the mechanism only if measured benefit justifies its complexity.

## Diagnostic acceleration

The separate checkpoint/forkserver path may be used for repeated Linux measurements and short experiments. It is diagnostic acceleration only; final correctness qualification remains a cold boot from reset through unchanged software.