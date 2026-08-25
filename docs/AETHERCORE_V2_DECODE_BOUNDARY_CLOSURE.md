# AetherCore v2 Decode Boundary Closure

## Scope

This closure is stacked on the frozen ISA semantic ownership split at #168 exact head `6cde3d9d89723cce81ef31573f50ae664c39e1bc`.

It does not introduce a new decoded-instruction representation and does not change production behavior. The audit found that AetherCore v2 already has the desired semantic boundary; this change makes that boundary an explicit architecture contract and adds a static guard against later backend opcode re-decode.

## Canonical path

The single intended semantic path is:

`instruction encoding -> extension semantic owners -> Decoder -> TinySemanticDecode -> v2.DecodedInstruction -> RobDispatch -> BackendUop -> dependency / issue / execute / LSU / system / recovery / commit`

### Ownership

- `core/isa/*Decode`: owns extension-specific architectural encoding semantics and legality composed by `Decoder`.
- `Decoder`: compatibility composition layer shared by RV32/RV64 and existing v1/v2 users.
- `TinySemanticDecode`: the **only v2 ISA bridge**. Legacy pipeline selectors (`OpASel`, `OpBSel`, `WbSel`, `ImmSel`) terminate here and are translated into explicit v2 architectural semantics.
- `v2.DecodedInstruction`: canonical post-decode architectural semantic record. It carries instruction/PC provenance, dependency fields, expanded immediate, typed control-flow, memory, system, ordering and exception facts.
- `RobDispatch`: adds execution-class and value-production dispatch facts.
- `BackendUop`: first point where the architectural record gains backend lifetime/value identities (`RobToken`, `ProducerTag`, `ValueRef`).
- downstream backend stages consume typed semantic fields only.

There must be exactly one canonical v2 `DecodedInstruction`. Do not create a parallel decoded-control record under the legacy `core` package.

## Raw encoding retention is not backend decode

`DecodedInstruction.inst` and `rawInst` remain available after the semantic boundary for architectural provenance. Their valid downstream uses include:

- `CommitTrace.inst/rawInst` observability and differential checking;
- precise illegal-instruction trap values;
- passing the original instruction bits through an already-typed request when an unsupported typed operation must produce an illegal-instruction exception.

These retained bits must **not** be sliced into opcode/funct fields downstream to recover instruction meaning. Load/store/AMO kind, width, signedness, branch kind, ALU operation, CSR/system operation and ordering must come from typed semantic fields produced before backend issue.

The source contract `tests_py/test_v2_decode_boundary.py` enforces two invariants:

1. `TinySemanticDecode.scala` is the only v2 file allowed to instantiate the shared `Decoder`.
2. files after that bridge may retain/forward `inst/rawInst`, but may not bit-slice those fields to re-decode ISA semantics.

## Audit evidence

The current backend satisfies the contract:

- dependency tracking consumes `rs1/rs2`, dependency-use and destination semantics;
- oldest/selective issue consumes `ExecutionClass`, operand-source kinds, ALU/branch types and ordering;
- execution consumes typed execution requests;
- memory issue sends typed `MemoryOperationKind`, `MemSize`, `AtomicOp`, immediate/base/store-data fields to the LSU;
- the LSU derives behavior from those typed fields; its `rawInst` is used only for the precise illegal-instruction trap value of an unsupported already-decoded memory operation;
- privileged/system completion consumes `SystemOperationKind`, CSR/xRET fields and uses `rawInst` only as an illegal-instruction trap value;
- Commit preserves `inst/rawInst` for architectural trace without interpreting them.

## Existing dynamic qualification

The semantic bridge is already covered by `V2F7SemanticDecodeChecks`, including representative RV32/RV64 I/M/A operations, control flow, loads/stores, AMO ordering, CSR, ECALL/EBREAK, WFI/xRET, SFENCE.VMA, FENCE/FENCE.I, fetch-exception precedence and compressed instruction length/provenance.

`V2FoundationTypesSpec` freezes the semantic/lifetime type separation, while the inherited Fast and Linux gates qualify the stacked #168 implementation.

## Consequence for later architecture work

P8.3 completion overlap, P8.4 non-blocking memory, frontend/predictor/cache work may extend scheduling and execution structures, but must not move ISA interpretation into those structures. New ISA extensions need explicit semantic ownership before entering `TinySemanticDecode`; new backend resources consume typed semantics.

With this seam explicit, the next structural closure is the RV32/RV64 common-vs-difference audit, followed by VM/TLB/PTW/permission/SFENCE ownership and frontend/data/shared-PTW composition.
