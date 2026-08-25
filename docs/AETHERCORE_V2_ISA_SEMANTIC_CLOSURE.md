# AetherCore v2 ISA Semantic Closure

## Purpose

This structural closure keeps the existing architectural behavior while making ISA semantic ownership explicit before later completion, memory, frontend, predictor, cache, and selective-OoO work increases coupling.

The frozen architecture base is #166 exact head `e6f907be8f2660197f8562d83c19c9da956c905d`.

## Invariants

- RV32 and RV64 remain one parameterized AetherCore implementation.
- `Decoder` keeps its existing IO and `ControlSignals` contract.
- Decode semantics and execution microarchitecture remain separate concerns.
- C decompression remains a frontend concern; this closure does not move compressed-instruction semantics into the 32-bit execution decoder.
- Optional architectural features are enabled by `IsaConfig`; production capability rejection remains owned by `CoreConfig` / `AetherCoreCapabilities`.
- This phase is structural only: no scheduling, recovery, execution, retirement, privilege, memory-ordering, or performance behavior changes are authorized.

## Semantic ownership

The top-level `Decoder` is a composition layer. Extension-specific semantic owners are:

- `BaseIDecode`: RV32I/RV64I integer semantics, including RV64 word operations and base FENCE.
- `MExtensionDecode`: M-extension operations sharing OP / OP-32 encodings.
- `AExtensionDecode`: LR/SC and AMO semantic decode with RV32/RV64 width legality.
- `SystemDecode`: Zifencei, Zicsr, WFI/xRET and SYSTEM semantic decode.

The shared OP / OP-32 opcode spaces are intentionally composed from disjoint semantic owners rather than duplicating a monolithic switch.

## Qualification contract

Before this closure is treated as frozen:

1. Existing `DecoderSpec` must remain green across RV32/RV64, M/A, Zicsr, FENCE.I, WFI/MRET/SRET and illegal-profile boundaries.
2. Fast regression must remain green.
3. Cross-XLEN/profile qualification must remain green.
4. Existing real-software/OS gates must remain behaviorally unchanged.
5. Any future extension must gain an explicit semantic owner rather than being appended directly into the top-level `Decoder` switch.

## Follow-up structural audits

After ISA semantic ownership is frozen, continue architecture closure in separate changes:

1. freeze the semantic decode -> unified decoded-control/uOp boundary;
2. audit RV32/RV64 common-vs-difference ownership;
3. audit VM decomposition: translation, TLB, PTW, permissions and SFENCE.VMA;
4. audit frontend translation, data translation and shared PTW arbitration;
5. audit CPU vs physical platform/bus/peripheral boundaries.

Only after these structural seams are clear should P8.3 completion overlap and P8.4 non-blocking memory add new coupling.
