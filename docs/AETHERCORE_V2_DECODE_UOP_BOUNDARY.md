# AetherCore v2 Decode → Backend Boundary

## Status

Architecture structural closure decision. This change is stacked on the qualified #168 ISA-semantic decomposition and is intended to be behavior preserving.

## Frozen pipeline boundary

The decode/backend ownership sequence is:

```
canonical instruction encoding
        |
        v
ISA legality / field decode
        |
        v
TinySemanticDecode
        |
        v
DecodedInstruction          architectural semantics only
        |
        v
TinyDispatchClassify        first backend-owned classification
        |
        v
RobDispatch                 decoded + executionClass + producesValue
        |
        v
TinyRob allocation
        |
        v
BackendUop                  adds RobToken / ProducerTag / ValueRef
        |
        v
dependency / issue / execute / completion / commit
```

## Ownership rules

### `DecodedInstruction`

`DecodedInstruction` is the stable architectural semantic seam. It may contain facts that are defined by the ISA or by precise frontend fault handling, including:

- canonical/raw instruction and architectural instruction length;
- source/destination register semantics and immediate value;
- ALU and RV64 word semantics;
- control-flow kind and branch condition;
- load/store/atomic semantics, width, acquire/release;
- CSR/system/fence/xRET semantics;
- ordering class;
- precise pre-dispatch architectural exception.

It must not contain ROB identity, producer/value identity, execution-port selection, queue identity, predictor state, bypass topology, physical-register identity, or backend issue policy.

### `TinyDispatchClassify`

This is the first backend-owned seam. It consumes only `DecodedInstruction`; it must not inspect raw opcode/funct fields or reconstruct ISA semantics.

Its current responsibilities are intentionally narrow:

- derive broad `ExecutionClass` (`Integer`, `Branch`, `MulDiv`, `Memory`, `System`);
- derive whether the instruction produces an architectural integer value (`producesValue`);
- package those facts with the unchanged `DecodedInstruction` as `RobDispatch`.

Execution class describes work, not a fixed execution port or pipeline stage.

### `TinyRob`

The ROB remains the order/lifetime owner. Only allocation adds:

- `RobToken` for program order/lifetime;
- `ProducerTag` for dependency/wakeup identity;
- `ValueRef` for value-storage identity.

Those identities remain distinct even if the current small implementation allocates them from the same physical ROB slot.

## Why this boundary exists

Before this closure, `TinySemanticDecode` created both architectural semantics and backend classification in one module. The resulting data was already structurally sound, but ownership was ambiguous: a future scheduler/LSU/frontend change could have tempted ISA decode to absorb backend policy, or backend code to re-decode raw instruction bits.

Separating the two layers makes the intended dependency direction explicit:

- ISA semantics do not depend on backend topology;
- backend classification depends on decoded semantics;
- later execution/issue logic consumes semantic records and never needs opcode re-decode.

This is especially important before completion-overlap, non-blocking-memory, predictor and cache work increase backend coupling.

## Behavior-preservation contract

This structural change does not authorize changes to:

- ISA legality or instruction semantics;
- exception priority;
- SFENCE.VMA, CSR, xRET, WFI or fence semantics;
- RV32/RV64 or compressed instruction behavior;
- scheduling or issue policy;
- branch recovery;
- memory ordering;
- retirement ownership;
- performance behavior.

The Bare and Paged frontends both use the same sequence:

```
TinySemanticDecode -> TinyDispatchClassify -> backend.dispatch
```

Frontend PC/serialization decisions consume `DecodedInstruction` directly rather than reaching through `RobDispatch`.

## Qualification

The structural boundary must retain:

1. the existing RV32/RV64 semantic-decode coverage for I/M/A/control/memory/system/CSR/fence/SFENCE/WFI/xRET and precise exceptions;
2. backend classification parity for Integer/Branch/MulDiv/Memory/System and value production;
3. existing v2 stage-local/Fast regression;
4. real RV64 Linux minimal qualification on the exact head.

A future change that wants to add a new backend classification may modify `TinyDispatchClassify`; a future ISA extension should add architectural meaning before this seam. Neither is permission to reintroduce raw opcode decoding downstream.
