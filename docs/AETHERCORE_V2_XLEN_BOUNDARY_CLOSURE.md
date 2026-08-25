# AetherCore v2 RV32/RV64 Boundary Closure

## Scope

This audit is stacked on the frozen semantic decode boundary at #177 exact head `d8f0ecb61969e7634676157485dc72376702887c`.

The goal is not to remove every XLEN-dependent expression. RV32 and RV64 have real architectural width differences. The goal is to keep one production core/backend/frontend architecture and confine those differences to explicit architectural semantics, geometry and width calculations rather than module forks.

## Decision

AetherCore v2 remains one parameterized RV32/RV64 implementation. The audit found no separate RV32 and RV64 v2 core/backend source trees and no top-level composition that selects a different pipeline module solely because XLEN is 32 or 64.

The stable ownership rule is:

- pipeline/backend ownership is shared: decode bridge, ROB, dependency tracking, scheduler, execution-cluster composition, recovery, commit, privileged composition and memory/backend composition;
- architectural width is carried by `IsaConfig.xlen` and typed bundle widths;
- instruction-set differences are semantic capabilities, e.g. RV64 word operations via `hasWordOps`;
- VM differences are described by `PageTableGeometry`, e.g. Sv32 vs Sv39 PTE/VPN/PA geometry;
- physical platform widths live in `PlatformConfig`, not in separate CPU implementations;
- production capability validation remains in `CoreConfig` / `AetherCoreCapabilities` rather than being encoded as a forked implementation.

## Allowed RV32/RV64 differences

The following are architectural or implementation-geometry differences and are expected to depend on XLEN:

1. register, immediate and CSR data widths;
2. RV64-only `*W` integer/M operations and their 32-bit sign-extension rules;
3. shift-amount widths and RV32/RV64 immediate legality;
4. A-extension width legality (`.W` on RV32/RV64, `.D` on RV64);
5. load/store width legality and RV64 `LWU`/doubleword behavior;
6. page-table geometry: Sv32 uses two 10-bit VPN levels and 32-bit PTEs; Sv39 uses three 9-bit levels and 64-bit PTEs;
7. architectural virtual/physical address canonicalization and PMP address-domain widths;
8. ABI/profile strings such as ILP32 vs LP64;
9. explicitly qualified temporary capability gaps.

These differences may change arithmetic, legality, widths or geometry. They must not create independent pipeline ownership.

## Current qualified capability gap: Sstc

`IsaConfig` currently states that the bounded Sstc implementation is RV32-only. This is an implementation/capability limitation of the current qualified core, not a claim that the RISC-V architecture restricts Sstc to RV32 and not a justification for an RV32-specific pipeline.

If RV64 Sstc is implemented later, it should extend the shared CSR/timer ownership and remove/relax the capability gate after qualification. It must not create an RV64-specialized core branch.

## Current platform coupling

The current v2 F6/F7 slice requires `busDataBits == XLEN`. This is a bounded integration contract, not an architectural identity. A future width-adapting bus may relax it without changing RV32/RV64 pipeline ownership.

Likewise, named `CoreProfiles` may use different platform physical-address widths because Sv32, Sv39 and PMP have different qualified PA domains. Profiles are configuration/publication artifacts, not CPU forks.

## Mechanical guard

`tests_py/test_v2_xlen_boundary.py` freezes the structural rule:

- no RV32/RV64-specific production file names may appear under `core/v2`;
- top-level v2 composition may not select different modules solely from XLEN;
- `IsaConfig`, `AetherCoreCapabilities` and `PageTableGeometry` must remain explicit description/capability seams;
- RV64 word semantics remain a capability (`hasWordOps`) rather than a separate implementation.

The guard deliberately does not reject local `if (xlen == 64)` expressions inside shared execution/memory logic where the architecture genuinely requires different widths or sign extension.

## Audit examples

- `TinyPagedCore` builds one `TinyMemoryBackend`, `TinySemanticDecode`, `InstructionFetchAdapter`, PMP path and PTW arbiter for either XLEN; optional C/A/interrupt features are selected by capabilities, not by an RV32/RV64 core fork.
- `TinyExecution` uses XLEN locally for word-operation/sign-extension arithmetic while retaining common issue/response ownership.
- `TinyBlockingLsu` uses one LSU and one translation adapter with XLEN/geometry-dependent legal sizes.
- `PageTableGeometry` owns Sv32/Sv39 shape and checks that a selected VM mode is compatible with the profile XLEN.

## Consequence

Future work must preserve one shared architecture. If a feature appears to need separate RV32/RV64 modules, first ask whether the real difference can be represented as semantic capability, width, VM geometry or platform configuration. A fork requires an explicit architecture review and new qualification evidence.

The next structural closure is VM ownership: TranslationUnit/TLB/PTW/permissions/SFENCE.VMA, then instruction-side/data-side/shared-PTW composition and finally CPU/platform/bus/peripheral boundaries before P8.3/P8.4.
