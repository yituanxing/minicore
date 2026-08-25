# AetherCore v2 cross-XLEN ownership closure

## Status

This decision record closes the RV32/RV64 ownership audit on the architecture-closure stack above the frozen VM permission/traversal point.

The design rule is intentionally stronger than "the same source tree can elaborate two widths": **RV32 and RV64 are one parameterized AetherCore architecture, not two cores that happen to share files.** XLEN-dependent architectural facts may vary; pipeline, backend and lifetime ownership must not fork merely because XLEN changes.

This is a structural contract. It does not add an ISA feature, change scheduling, memory ordering, recovery, retirement, privilege behavior or performance policy.

## Shared implementation ownership

The following are one shared implementation across RV32 and RV64:

- architectural semantic record: `DecodedInstruction`;
- backend uOp/lifetime representation: `BackendUop`;
- ROB allocation and precise in-order retirement;
- `RobToken`, `ProducerTag` and `ValueRef` lifetime/dependency/value identities;
- dependency tracking and scheduling window;
- Integer / Branch / MUL-DIV execution ownership;
- completion arbitration and recovery;
- system/CSR/trap/interrupt retirement ownership;
- memory backend and LSU transaction lifetime;
- translation-unit/TLB/PTW abstraction;
- page-table traversal and PTE permission checker;
- instruction/data translation composition and shared PTW arbitration model;
- PMP checking abstraction;
- physical-memory request/response seam;
- commit trace and architectural observation;
- compressed-instruction parcel/decompression architecture, parameterized by XLEN.

A future change that creates an `Rv32*`/`Rv64*` copy of one of these owners is an architecture change and requires explicit review; it is not an ordinary implementation convenience.

## Allowed XLEN-dependent differences

The following are architectural data/semantic differences and are expected to remain parameterized:

1. **Datapath width** — integer/CSR architectural values are 32 or 64 bits.
2. **RV64 word operations** — `*W` integer/M semantics exist only when `xlen == 64`.
3. **Immediate/shift legality** — XLEN changes architectural shift width and related legal encodings.
4. **Atomic width legality** — AMO.W exists on RV32/RV64; AMO.D is RV64-only.
5. **CSR field/layout width** — XLEN-dependent CSR fields may differ where the RISC-V architecture requires it.
6. **Virtual-memory geometry** — Sv32/Sv39/Sv48 are `PageTableGeometry` data: VA width, levels, VPN partition, PTE width, PPN width, ASID width and SATP mode.
7. **Canonical-address rules** — derived from the selected geometry, not from a second walker implementation.
8. **Architectural physical-address limits** — e.g. current PMP PA limit is 34 bits for RV32 and 56 bits for RV64.
9. **ABI/profile publication** — ILP32 vs LP64 and evidence-backed named profiles are configuration/publication differences, not backend forks.
10. **Explicit bounded capability gaps** — a feature may currently be qualified for only one XLEN (for example the bounded current Sstc implementation), but that limitation belongs in `IsaConfig`/`CoreConfig` fail-closed capability policy rather than a duplicated core pipeline.

The test for an allowed difference is: **can the difference be expressed as architectural geometry, width, legality or capability data while the owning algorithm/state machine remains shared?** If yes, parameterization is appropriate.

## Forbidden XLEN forks

Do not introduce separate RV32/RV64 versions of:

- ROB / dependency / scheduler / commit / recovery;
- backend execution cluster;
- memory backend / LSU policy;
- translation unit, TLB or page-table walker;
- PTE permission policy;
- frontend control flow merely to accommodate width;
- CSR/trap ownership;
- common bus/memory transaction protocol.

In particular, avoid structures equivalent to:

```text
if RV32 -> one pipeline/backend/state machine
if RV64 -> another pipeline/backend/state machine
```

when the architectural difference can instead be represented by `xlen`, `PageTableGeometry`, profile/capability data, or typed widths.

This does **not** ban local `if (xlen == 32/64)` expressions. Such expressions are legitimate when they select an architectural constant, encoding rule, field width, sign/canonicalization rule or supported operation. The forbidden pattern is duplicated ownership/lifetime logic.

## Evidence in the current design

### Configuration vs implementation

`IsaConfig` describes architectural/profile facts (`xlen`, ISA extensions, privilege, VM modes, PMP/timer surfaces). `CoreConfig` then fails closed against implemented AetherCore capabilities. This is the intended place for a profile that can be described architecturally but is not yet a qualified production combination.

### VM geometry

`PageTableGeometry` describes Sv32/Sv39/Sv48 as data. `PageTableWalker(geometry)` owns one traversal algorithm and `PageTableEntryChecker(geometry)` owns one permission/format policy. Width, level count and canonical-address differences are therefore data-driven rather than walker forks.

### Backend

`TinyMemoryBackend`, `TinyBlockingLsu`, dependency/scheduler/execution/completion/recovery and foundation types are parameterized by `isa.xlen` or `geometry.xlen`. The v2 source tree has no RV32/RV64-named backend implementation fork.

### Physical address width is not XLEN

The memory seam intentionally keeps physical-address width independent of XLEN. Sv32 may require a 34-bit physical address while architectural integer values are 32 bits. Cross-XLEN cleanup must not collapse `paddrBits` into `xlen` merely for cosmetic symmetry.

## Executable guard

`tests_py/test_v2_cross_xlen_ownership.py` freezes the structural surface:

- no RV32/RV64-named class/object/trait is allowed in `core/v2`;
- no RV32/RV64-named source file is allowed in `core/v2`;
- foundation/backend/LSU/paged-core ownership remains explicitly XLEN/geometry-parameterized;
- the VM walker remains `PageTableWalker(PageTableGeometry)` with a shared `PageTableEntryChecker`;
- architectural VM mode compatibility remains checked as geometry data;
- capability rejection remains centralized in configuration rather than hidden in a forked backend.

The source contract deliberately does not reject every literal `xlen == 32/64`: that would outlaw correct architectural semantics rather than architecture forks.

## Qualification rule

This audit is complete only when the exact head passes the normal Fast source-contract/cross-XLEN lane and the existing representative software gates remain unchanged. Because the intended change is documentation plus an executable architecture guard, there must be **zero production RTL diff** in this closure PR.

## Re-open criteria

Re-open this decision only if a future architectural requirement genuinely cannot be represented with the shared typed/geometry-based contracts. A proposal to fork RV32/RV64 implementation ownership must document:

1. the architectural requirement that cannot be parameterized;
2. why a shared state machine would be incorrect or materially harmful;
3. the new precise ownership/lifetime boundary;
4. cross-XLEN regression evidence;
5. FPGA area/timing implications if the fork is motivated by implementation cost.

Convenience, shorter local code, or a one-profile benchmark win is not sufficient justification for splitting the architecture.
