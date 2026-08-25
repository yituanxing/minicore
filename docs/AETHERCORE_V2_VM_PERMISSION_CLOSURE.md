# AetherCore v2 VM Permission Ownership Closure

## Scope

This structural change is stacked on #178 frozen exact head `029b330b1c50e7eabf2385e33da28300f45ef081`.

The initial VM audit found that most translation ownership was already correctly decomposed and geometry-driven:

- `TranslationUnit`: request/response lifetime, bare/M-mode bypass, TLB lookup/refill composition;
- `TranslationTlb`: cached translation ownership;
- `PageTableWalker`: page-table traversal;
- `InstructionFetchAdapter`: instruction-side translation plumbing;
- `DataPathAdapter`: data-side translation plumbing;
- `PtwArbiter`: shared instruction/data PTE-memory arbitration;
- `PageTableGeometry`: Sv32/Sv39/Sv48 architectural shape.

The remaining structural concentration was inside `PageTableWalker`: traversal and architectural PTE permission/validity policy lived in the same module.

## Decision

PTE-format and leaf-access policy now has one explicit owner: `PageTableEntryChecker`.

`PageTableEntryChecker` owns:

- V/R/W/X/U/G/A/D field interpretation;
- invalid W-without-R encoding;
- reserved-high PTE bits for the currently implemented standard modes;
- reserved non-leaf U/A/D encoding;
- leaf classification;
- R/W/X access intent;
- U/S privilege checks;
- SUM and MXR policy;
- Svade A/D fault policy;
- superpage PPN alignment legality;
- PTE PPN/global extraction.

`PageTableWalker` retains:

- request/response state and kill lifetime;
- canonical virtual-address validation;
- VPN-level selection;
- page-table pointer descent;
- inherited-global propagation;
- leaf physical-address composition;
- level-zero non-leaf termination;
- propagation of PTE-memory access faults.

This is a behavior-preserving ownership split. It does not alter TLB policy, SFENCE behavior, PMP/PMA, satp activation, hardware A/D updates or VM performance.

## Why superpage alignment belongs with the entry checker

Superpage alignment is a legality property of using the current PTE as a leaf at the current architectural level. The checker therefore receives `level` and evaluates the low-PPN-zero requirement together with the other leaf-validity rules. The walker still owns the resulting VPN/PPN physical-address composition.

## Qualification

- `PageTableEntryCheckerSpec` directly covers Sv32 and Sv39 PTE policy including SUM, MXR, Svade A/D, W-without-R, non-leaf reserved fields, reserved RV64 PTE high bits and superpage alignment;
- existing `Sv32PageTableWalkerSpec` remains the dynamic end-to-end behavioral regression for walker semantics and is already selected by Fast Gate;
- `tests_py/test_vm_permission_boundary.py` mechanically prevents permission equations from drifting back into the walker and requires all VM layers to remain `PageTableGeometry` driven;
- shared-Scala changes trigger the existing cross-XLEN/VM/privilege compatibility lanes and RV64 Linux Minimal qualification.

## Next VM audit

After this split is qualified, continue with:

1. SFENCE.VMA ownership and flush propagation from semantic decode/retirement to I-side and D-side TLBs;
2. instruction-side vs data-side translation ownership;
3. shared PTW arbitration and PMP ownership;
4. CPU vs physical platform/bus/peripheral boundary.

No non-blocking memory, cache or frontend performance mechanism should be added in this structural PR.
