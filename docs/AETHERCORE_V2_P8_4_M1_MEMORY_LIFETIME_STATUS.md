# AetherCore v2 P8.4-M1 — memory lifetime status ownership

Status: first conservative-memory-concurrency slice after P8.3 completion-width NO-GO.

Base: #183 frozen exact head `f38639b321f8178299a3d30075945d44a428ed22`.

## Why memory is next

The exact P8 Linux snapshot showed:

```text
cycles=306754737
issue_mem=50437178
lsu_busy=59542603
memory_head=109979781
completion_collision=1450968
completion_backpressure=1450968
```

The current blocking geometry has the exact identity:

```text
memory_head = issue_mem + lsu_busy
109979781 = 50437178 + 59542603
```

Completion-port pressure is only 0.4730% of measured cycles, while memory-head residency is 35.8527% and LSU busy residency is 19.4105%. P8.3 therefore kept the one-completion-per-cycle fabric and moved the complexity budget to conservative memory concurrency.

M1 is intentionally not a Store Queue, load bypass, cache or MSHR implementation. Before changing memory scheduling, the current LSU must expose its own lifetime facts through an explicit read-only contract.

## Ownership

`TinyBlockingLsu` owns the one active memory-uOp lifetime and the one outstanding physical request.

M1 adds `TinyMemoryLifetimeStatus`, exported as `io.lifetimeStatus`. It reports facts only:

- lifetime valid/drained;
- complete `RobToken`;
- memory operation kind, atomic operation and size;
- effective address;
- whether the operation is write-like;
- physical address plus an explicit valid bit;
- PMA attributes plus an explicit valid bit;
- whether the exact store permit currently matches;
- whether this lifetime has crossed the physical-request handshake;
- whether a fresh or held architectural completion is pending.

The status bundle deliberately does **not** contain policy such as:

- `mayIssueYoungerLoad`;
- `canBypass`;
- `canSpeculate`;
- dependence prediction or replay state.

A future ordering owner may combine LSU facts with ROB age and Store Queue entries. M1 does not move ordering policy into the LSU.

## Intake-cycle visibility

The existing P8 LSU flow-through contract is:

```scala
val workingValid = busy || io.request.fire
```

M1 uses this same lifetime view. Therefore a memory uOp accepted while the LSU is idle is visible through `lifetimeStatus` in the intake cycle; the status does not wait for the `active` register one cycle later.

`drained` means the current **blocking LSU lifetime** is empty:

```scala
io.lifetimeStatus.drained := !workingValid
```

This is not a future global memory-system drain signal. When M2 introduces a Store Queue, a global drain condition will need to combine queue emptiness with LSU drain state.

## Physical-request semantics

The old register `physicalIssued` is intentionally retained unchanged. It is not equivalent to “response outstanding”: it remains asserted after the physical response arrives and through a backpressured held completion, until `completion.fire` releases the architectural lifetime.

The exported fact is therefore named `physicalRequestIssued`, not `responseOutstanding`.

It is handshake-accurate on the first issue cycle:

```scala
io.lifetimeStatus.physicalRequestIssued :=
  physicalIssued || io.memoryRequest.fire
```

This prevents a one-cycle observation hole when a hot translation/PMA path accepts the physical request on the same cycle as the LSU intake.

## Completion semantics

`completionPending` is tied to the real Decoupled completion interface:

```scala
io.lifetimeStatus.completionPending := io.completion.valid
```

It therefore covers both:

1. a fresh terminal response in the cycle it becomes architecturally available;
2. a response retained under completion backpressure.

The lifetime remains valid and `physicalRequestIssued` remains true during a held completion. It becomes drained only after `completion.fire`.

## Physical address and PMA validity

M1 does not add persistence registers for resolved physical address or PMA attributes.

```scala
physicalAddressValid := adapter.io.dataValid
attributesValid      := adapter.io.dataValid
```

Consumers must respect those valid bits. A future Store Queue may explicitly capture address/data/PMA facts when M2 requires them to outlive the transient blocking-LSU translation path. M1 does not pre-commit that future queue representation.

## Write-like classification

`writeLike` is the existing `accessNeedsWritePermission` fact:

- ordinary Store: true;
- SC: true;
- AMO writer: true;
- ordinary Load: false;
- LR: false.

`writePermitMatched` is meaningful only for a write-like operation and requires the existing full-generation `RobToken` match.

This does not weaken the store visibility rule. Store/SC/AMO writers still require the live exact-head permit before `io.memoryRequest.valid` can externalize a write-like transaction.

## Behavior preserved in M1

The following remain unchanged:

- memory issue is exact-head-only in `TinyMemoryBackend`;
- one memory uOp is active in the LSU;
- one physical transaction may be outstanding;
- Store/SC/AMO writers externalize only with exact-head `storePermit`;
- MMIO/PMA policy remains outside the LSU;
- selective compute may overlap only under the already-qualified A8 rules;
- one launch per cycle remains asserted;
- completion remains Decoupled and one accepted top-level completion per cycle;
- Commit remains in order and one-wide.

`TinyMemoryBackend` deliberately does not consume `lifetimeStatus` in M1. Any cycle/performance change in this slice is a regression.

## Qualification

The dedicated dynamic checks prove:

1. intake-cycle visibility and write-like classification;
2. exact-generation store-permit matching;
3. physical-request handshake is visible in the same cycle;
4. a returned response held by completion backpressure remains an active externalized LSU lifetime;
5. local alignment faults never claim physical externalization;
6. the status becomes drained only after architectural completion acceptance.

A tracked Python source contract additionally rejects use of `lifetimeStatus` in `TinyMemoryBackend` scheduling during M1 and freezes the old exact-head/one-outstanding rules.

## Next: M2

After exact-head M1 qualification, the next slice is the smallest Store Queue needed to separate store address/data preparation from Commit-time external visibility:

- capture bounded store address/data state;
- keep stores non-visible until Commit permission;
- drain committed stores in order;
- keep ordered/side-effecting MMIO conservative and non-speculative;
- do not yet issue younger loads past unresolved older stores.

Only after M2 owns per-store state should M3 add safe younger-load overlap and forwarding:

- unknown older store address -> wait;
- known non-overlap -> proceed;
- exact full overlap with ready data -> forward;
- partial overlap -> wait initially.

Memory-dependence prediction, violation replay and a large LSQ remain out of scope.
