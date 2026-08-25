# AetherCore v2 PTW and SFENCE ownership closure

## Scope

This record closes the ownership seam between instruction translation, data translation, shared PTW arbitration, implicit page-table PMP protection, and SFENCE.VMA translation invalidation.

The production RTL change is deliberately narrow: remove duplicate parent-side PMP qualification for data-side PTE reads. Translation algorithms, TLB contents, arbitration priority, external PTW protocol, fault class and SFENCE semantics remain unchanged.

## Translation owners

### Instruction side

`InstructionFetchAdapter` owns cancellable frontend translation plumbing around one geometry-driven `TranslationUnit`. It owns request/kill/flush forwarding and response plumbing, but no PMP policy.

### Data side

`TinyBlockingLsu` owns the current architectural memory-operation lifetime. `TinyMemoryBackend` owns mutable CSR/PMP context and therefore qualifies data-side implicit PTE reads before exporting its `pte*` seam.

A denied data PTE access is consumed locally: it does not assert external `pteValid`, presents ready to the walker in the denial cycle, and returns `pteFault` in that cycle.

## Shared PTW arbitration

`PtwArbiter` owns only arbitration and response routing. Data translation has deterministic priority because it belongs to an older architectural memory operation; fetch translation is speculative and waits while data is present. The arbiter owns no PMP, SATP, PTE-permission or SFENCE policy.

## Single-owner PMP rule

Every implicit page-table physical read crosses exactly one Supervisor-mode PMP check before leaving the core.

- Data PTE owner: `TinyMemoryBackend`, before `backend.io.pteValid` is exported.
- Fetch PTE owner: `TinyPagedCore`, after the shared arbiter selects fetch.

The qualified path is therefore:

```text
D TranslationUnit -> TinyMemoryBackend PTW-PMP -> PtwArbiter -> external PTW
I TranslationUnit ----------------------------> PtwArbiter -> fetch PTW-PMP -> external PTW
```

A denied fetch PTE read is also consumed locally: external `io.ptw.valid` remains false while the arbiter receives ready=true and fault=true for the selected fetch lifetime.

## Why data PMP stays in the backend

`TinyMemoryBackend` is a valid composition owner below `TinyPagedCore`. Moving all implicit-PTE PMP policy to the paged parent would weaken the backend contract or require a wider interface redesign. The structural closure instead removes only the duplicate second data check while retaining standalone safety.

## SFENCE.VMA single-origin rule

SFENCE.VMA invalidation originates only from precise retirement.

`TinyMemoryBackend` derives `sfenceAtRetire` only when the retiring entry:

- is a System uOp;
- has no exception;
- has semantic kind `SystemOperationKind.SfenceVma`.

That one retirement pulse fans out to both translation domains:

- data: `lsu.io.translationFlush := sfenceAtRetire`;
- instruction: `io.translationFence := sfenceAtRetire`, then `TinyPagedCore -> InstructionFetchAdapter.flush -> TranslationUnit.flush`.

`TranslationUnit.flush` both invalidates the TLB and aborts an in-flight translation lifetime. Compressed-fetch parcel state is killed at the same architectural boundary, preventing a half instruction from crossing SFENCE.VMA.

No PTW arbiter, walker or frontend predictor is allowed to invent a second architectural SFENCE owner.

## Fault-timing invariant

For PMP-denied implicit PTE reads on either side:

- no external PTW request is emitted;
- the requesting walker observes ready in the denial cycle;
- the requesting walker observes PTE fault in the denial cycle;
- allowed requests forward normal external ready/data/fault.

This PR changes ownership, not handshake timing.

## Non-goals

This closure does not add multiple outstanding walks, walk caches, separate I/D walkers, ASID-selective SFENCE, hardware A/D updates, a new TLB replacement policy, speculative data-walk externalization, or non-blocking LSU behavior.

## Executable guard

`tests_py/test_v2_ptw_sfence_ownership.py` mechanically freezes:

- data-side PTW PMP before backend export;
- fetch-only parent-side PTW PMP;
- policy-free data-priority `PtwArbiter`;
- no PMP ownership in `TranslationUnit`/`InstructionFetchAdapter`;
- retirement-only SFENCE origin and propagation to both I and D translation.

## Qualification

Because `TinyPagedCore` production RTL changes, this head requires exact-head Fast/VM/cross-XLEN/Supervisor/FreeRTOS qualification plus RV64 Linux Minimal. Any performance workflow is only a correctness/identity guard; no performance gain is claimed.
