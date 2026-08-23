# AetherCore v2 P8.2 — Generalized branch recovery

Status: architecture workstream, independent from P8 optimization experiments.

Base architecture: post-A8 selective-compute ownership with precise in-order Commit.

## Objective

Advance the architecture from F4/F7 head-only normal branch recovery to arbitrary-age branch recovery suitable for later selective Branch issue.

Current head-only behavior is intentionally insufficient for P8.2:

- normal branch recovery is accepted only when the branch completion names the ROB head;
- recovery squashes every other live ROB entry;
- speculative dependency/rename state retains only the surviving head producer.

P8.2 must instead support:

```text
ROB age order:
A B C(branch) D E

C redirects:
keep A B C
kill D E
redirect frontend immediately
rebuild speculative dependency/rename state for A B C
```

Architectural state remains untouched until in-order Commit.

## Invariants

1. `RobToken` remains program-order/lifetime identity.
2. `ProducerTag` remains dependency/wakeup identity.
3. `ValueRef` remains speculative value identity.
4. A branch completion may redirect only when its exact lifetime identities match a live ROB entry.
5. Recovery never removes an instruction older than the recovering branch.
6. The recovering branch itself survives and may retain its link value.
7. Every killed slot receives a fresh generation immediately so late completions are rejected.
8. Commit remains strictly in order; no architectural RF/CSR/store side effect is rolled back.
9. Memory/system ordering remains conservative while generalized branch recovery is introduced.
10. Branch selective issue is not enabled until generalized recovery and rebuild tests are green.

## Implementation sequence

### R1 — ROB younger-only recovery authority

- identify the recovering branch at arbitrary live ROB age;
- validate exact `RobToken` / `ProducerTag` / `ValueRef` lifetime;
- derive survivor count = branch age + 1;
- invalidate only younger slots;
- advance generations for killed slots;
- set tail to first slot after recovering branch;
- preserve head and all older entries;
- expose recovery identity/target plus survivor boundary.

Branch issue remains head-only during R1.

### R2 — dependency/rename rebuild contract

Replace the current "clear all except head" recovery behavior with a bounded rebuild from committed state plus surviving ROB entries.

Preferred implementation for ROB4/8:

- recovery starts a small rebuild state machine;
- clear speculative rename mappings;
- replay surviving ROB entries oldest-to-youngest into rename/dependency ownership;
- retain already-completed producer values where available;
- block new dispatch/selective issue while rebuild is active;
- finish in at most the bounded survivor-window length.

This intentionally avoids per-branch rename checkpoints and branch masks.

### R3 — frontend redirect + late-response rejection

- redirect immediately after validated branch recovery;
- ensure killed execution responses cannot wake survivors or complete reused slots;
- verify fetch/dispatch remain blocked across rebuild as required;
- verify older outstanding work remains live.

### R4 — enable arbitrary-age Branch selection

Only after R1-R3 are qualified:

- extend oldest-ready scheduler policy to Branch;
- Branch remains single-issue and age-ordered among eligible candidates;
- Memory/System barriers retain conservative semantics;
- no speculative memory reordering is introduced by this phase.

## Qualification

Focused proofs must include at minimum:

- branch at age 0 retains existing F4 behavior;
- branch at middle age preserves older incomplete/complete entries;
- only younger entries are invalidated;
- killed slot generations change immediately;
- late completion from killed work is rejected after slot reuse;
- surviving dependency chains remain correct after rebuild;
- branch link value survives recovery;
- older exception still retires precisely before the branch;
- younger exception is discarded with the squash;
- recovery colliding with dispatch blocks the speculative dispatch;
- recovery colliding with completion obeys deterministic ownership;
- repeated recoveries across circular ROB reuse do not alias lifetimes;
- RV32/RV64 and compressed-link semantics remain intact.

Linux/RTOS gates follow focused qualification, but P8.2 is an architecture milestone rather than a performance experiment: correctness and ownership closure are the promotion criteria.

## Explicit scope boundary

The active TLB-hit and ROB geometry A/B experiments are the final optimization batch before P8.2 architecture work. New independent micro-optimization experiments must not delay R1-R4. Performance measurement may evaluate P8.2 after correctness closure, but does not gate starting or completing the architecture milestone.
