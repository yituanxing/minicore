# AetherCore v2 F1 — Tiny ROB + Explicit Commit

F1 is the first behavioral slice after the F0 ownership contracts. Its purpose is not to make AetherCore out-of-order. Its purpose is to make instruction lifetime independent from pipeline-stage occupancy and to establish one precise retirement owner.

## Scope

F1 implements exactly this path:

```text
unallocated dispatch semantics
        |
        v
Tiny ROB allocation
  - allocate RobToken
  - allocate ProducerTag
  - allocate ValueRef
        |
        v
BackendUop
        |
        v
synthetic / narrow completion path
        |
        v
ROB head
        |
        v
existing CommitTrace
        |
        v
existing architectural RegisterFile
```

The first implementation is intentionally ordered and one-wide.

## Fixed first implementation

These are implementation choices for F1, not public generator knobs:

- ROB entries: 4
- allocation width: 1
- completion bandwidth: 1
- retirement width: 1
- issue policy: ordered / oldest-only when an execution harness is attached
- result storage: ROB-backed
- no speculative execution
- no memory execution
- no CSR/MMU integration
- no branch recovery
- no PRF or free list

F1 may use a small explicit generation width as a local implementation constant. That number is not an architectural contract and must be revisited when later slices introduce longer-lived or recoverable outstanding work.

## Ownership

### Before allocation

The object crossing into the allocator has instruction/backend semantics but no valid lifetime identity yet.

It must not claim a `RobToken`, `ProducerTag`, or `ValueRef` that the ROB later overwrites.

### At allocation

Allocation is the single point that creates the first implementation's three identities:

```text
RobToken      = program-order / lifetime identity
ProducerTag   = dependency / wakeup identity
ValueRef      = value-storage identity
```

F1 may numerically map all three to the same ROB slot and generation. They remain different types and different ownership concepts.

### ROB

The ROB owns:

- program order;
- slot validity;
- the allocated `RobToken`;
- completion state;
- F1 ROB-backed result storage;
- instruction information required to construct the architectural retirement record.

The ROB does not become the permanent owner of rename policy, execution-port selection, branch masks, physical-register allocation, load/store queues or future dependency scheduling policy.

### Completion

A completion identifies the instruction lifetime with `RobToken` and carries the already-defined execution identities. Execution latency is not encoded in the type.

F1 accepts at most one completion per cycle.

A completion for an invalid or stale lifetime must not mutate a live ROB entry.

### Commit

Only the ROB head may retire.

Commit is in order even if later slices allow younger instructions to complete first.

F1 reuses `aethercore.common.CommitTrace` as the architectural retirement record. It must not introduce `V2CommitTrace` or a second architectural truth surface.

For the first integer slice:

- `CommitTrace.valid` means a completed head instruction retires;
- exception instructions still retire with `valid = true`;
- an exception suppresses architectural `rdWrite`;
- x0 suppresses architectural `rdWrite`;
- non-exception integer writes update the existing `RegisterFile` only at Commit;
- memory trace fields are false/zero in this slice;
- interrupt fields are false/zero in this slice.

This intentionally matches the existing v1 retirement semantics rather than inventing a v2 interpretation.

## Reuse

F1 reuses without redesign:

- `CommitTrace` — shared v1/v2/reference retirement oracle;
- `RegisterFile` — committed architectural integer register state;
- F0 `DecodedInstruction`;
- F0 `BackendUop`;
- F0 `RobToken`, `ProducerTag`, `ValueRef`;
- F0 `ExecutionRequest` / `ExecutionResponse` where appropriate.

F1 does not modify `core/AetherCore.scala` in place. The qualified v1 implementation remains the correctness reference.

## Minimum behavioral tests

F1 is not complete until real Chisel simulation demonstrates at least:

1. one allocate -> complete -> retire sequence;
2. four-entry fill and full backpressure;
3. head-only retirement;
4. younger completion cannot bypass an incomplete older head;
5. one retirement frees one slot for a new allocation;
6. circular slot reuse changes generation identity;
7. a stale completion cannot corrupt a reused slot;
8. x0 retirement does not write architectural state;
9. a normal rd write appears in `CommitTrace` and the committed `RegisterFile` together;
10. an exception retires in `CommitTrace` but does not write rd;
11. RV32 and RV64 elaborate and execute the same ownership behavior.

Where useful, tests should deliberately make `RobToken`, `ProducerTag`, and `ValueRef` numerically distinguishable so accidental type coupling remains observable.

## Qualification boundary

F1 qualification is deliberately smaller than full Linux bring-up:

```text
source contracts
  -> RV32/RV64 Chisel F1 tests
  -> existing cross-XLEN gate
  -> existing focused v1 regression gates
```

Linux, OpenSBI, VM and privileged integration become mandatory again when v2 is connected to those architectural paths. F1 itself must not fake that integration merely to claim a larger milestone.

## Explicit non-goals

F1 does not add:

- arbitrary ROB-size configuration;
- arbitrary issue width;
- physical register files;
- free lists;
- branch masks;
- branch prediction;
- recovery checkpoints;
- LDQ/STQ;
- speculative loads;
- MSHRs or cache policy;
- CSR/trap state ownership changes;
- a new memory bus;
- AXI/TileLink coupling;
- a second CommitTrace format.

Those mechanisms enter only when the next concrete behavior requires them.

## Exit condition

F1 is frozen when a four-entry, one-wide backend can allocate identities exactly once, accept completions without stale-slot corruption, retire strictly in order, and update the existing architectural retirement/register interfaces with v1-compatible semantics at both XLENs.
