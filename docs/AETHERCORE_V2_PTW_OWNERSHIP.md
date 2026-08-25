# AetherCore v2 I/D translation and PTW ownership

## Scope

This record closes the ownership seam between instruction translation, data translation, shared PTW arbitration and implicit page-table physical protection.

The change is deliberately narrow: it removes duplicate PMP ownership for data-side PTE reads in `TinyPagedCore` without changing translation algorithms, TLB contents, page-table permissions, arbitration priority, external PTW protocol or architectural fault class.

## Existing translation owners

### Instruction side

`InstructionFetchAdapter` owns only cancellable frontend translation plumbing around one shared `TranslationUnit`.

It provides:

- fetch request/kill/flush lifetime;
- execute access type;
- forwarding of SATP/root-PPN/MXR context;
- translation response plumbing.

It does **not** own PMP policy. It has no mutable CSR/PMP state and must not grow a private PMP implementation.

### Data side

`TinyBlockingLsu` owns the lifetime of one architectural memory operation and uses `DataPathAdapter` / `TranslationUnit` for address translation.

`TinyMemoryBackend` owns the mutable architectural CSR/PMP context and therefore protects data-side implicit PTE reads before exporting them through its `pte*` seam.

A denied data PTE access:

1. never asserts the backend's external `pteValid`;
2. is consumed locally with ready=true;
3. is returned to the walker as `pteFault`;
4. becomes the existing translation access-fault path.

This local protection is required because `TinyMemoryBackend` is also a valid standalone composition below frontends that do not provide a shared outer PTW guard.

## Shared PTW arbitration

`PtwArbiter` owns only arbitration and response routing.

- data translation has deterministic priority because it corresponds to an older architectural memory operation;
- fetch translation is speculative/cancellable and waits while data requests are present;
- the arbiter does not own PMP, SATP, page-table semantics or fault policy;
- PTE width and address geometry remain driven by `PageTableGeometry`.

## Single-owner PMP rule

Every implicit page-table physical read must pass **exactly one** Supervisor-mode PMP check before leaving the core.

### Data PTE read

Owner: `TinyMemoryBackend`.

By the time `backend.io.pteValid` reaches `TinyPagedCore`, that request is already PMP-qualified. The paged parent must not check it again.

### Fetch PTE read

Owner: `TinyPagedCore` after PTW arbitration.

`InstructionFetchAdapter` intentionally lacks PMP ownership. When the arbiter selects fetch, `TinyPagedCore` checks the selected PTE physical address using the read-only PMP state exported by the backend.

A denied fetch PTE read is consumed locally in the same cycle (`memoryReady=true`, fault returned through the arbiter) and is never emitted on `io.ptw`.

## Why not move all PMP into the parent now?

Doing so would make `TinyMemoryBackend` unsafe or incomplete when used outside `TinyPagedCore`, unless its public contract were simultaneously changed to require a parent guard. That is a larger composition change with unnecessary regression surface.

The current closure instead establishes one owner per request path while preserving every existing standalone contract:

```text
D-side TranslationUnit -> TinyMemoryBackend PTW-PMP -> shared arbiter -> external PTW
I-side TranslationUnit ----------------------------> shared arbiter -> fetch PTW-PMP -> external PTW
```

There is no double protection and no unprotected path.

## Fault-timing invariant

PMP denial for an implicit PTE read remains a local access fault, not an externally visible bus transaction.

For both owners the required behavior is:

- denied request: external PTW valid = false;
- upstream walker ready = true in the denial cycle;
- upstream walker fault = true in the denial cycle;
- PTE data is irrelevant on the denied response;
- allowed request: external ready/data/fault are forwarded normally.

This is deliberately the same timing shape used before this cleanup. The refactor changes ownership, not architectural or handshake timing.

## SFENCE.VMA ownership

This change does not alter SFENCE.VMA semantics. Legal exception-free SFENCE retirement remains the architectural owner of the translation-flush pulse. The same pulse reaches data translation and instruction translation; PTW arbitration does not invent or consume an independent flush policy.

## Non-goals

This closure does not introduce:

- multiple outstanding page walks;
- a walk cache;
- separate I/D page-table walkers;
- a new TLB replacement policy;
- hardware A/D updates;
- ASID-selective SFENCE.VMA;
- speculative data PTW externalization changes;
- non-blocking LSU behavior.

Those would be separate architecture/performance changes.

## Executable source contract

`tests_py/test_v2_ptw_ownership.py` freezes the ownership boundary:

- data PTW PMP remains in `TinyMemoryBackend` before export;
- parent-side PMP applies only when fetch owns the arbiter;
- `PtwArbiter` remains policy-free and data-priority;
- `TranslationUnit` and `InstructionFetchAdapter` remain free of PMP policy.

## Qualification

Because `TinyPagedCore` production RTL changes, this closure requires real exact-head RTL qualification, not source-contract success alone. At minimum:

1. Fast source contracts and focused v2/VM tests;
2. cross-XLEN/shared compatibility;
3. Supervisor/FreeRTOS compatibility;
4. RV64 Linux Minimal;
5. F7 / paged frontend qualification where available.

Any performance run is a correctness guard here, not evidence of a speedup: no performance improvement is claimed.
