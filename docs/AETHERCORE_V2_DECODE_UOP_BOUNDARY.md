# AetherCore v2 decode -> backend uOp boundary

## Status

Architecture structural closure record. This change is behavior-preserving and stacked on the ISA semantic ownership split.

## Frozen boundary

The v2 instruction path is explicitly layered as:

```text
instruction encoding / profile legality
        |
        v
TinyArchitecturalSemanticDecode
        |
        v
DecodedInstruction          <- architectural semantics only
        |
        | selected semantic facts only
        v
TinyBackendClassifier       <- encoding-blind by interface
        |
        v
ExecutionClass + producesValue
        |
        v
TinySemanticDecode composition -> RobDispatch
        |
        v
ROB allocation / dependency / scheduling / execution
```

`DecodedInstruction` is the architectural semantic contract. It may retain canonical/raw instruction bits for commit trace and precise trap evidence, but downstream backend policy must not re-interpret encoding fields to recover meaning that decode should have supplied.

`TinyBackendClassifier` is the first microarchitectural owner. It derives only:

- `ExecutionClass`;
- whether the instruction produces an architectural register value.

Its hardware interface is deliberately narrower than `DecodedInstruction`. It receives only `aluOp`, system/memory/control-flow semantic kind, destination-write facts and exception validity. It has no instruction/raw-instruction input and therefore cannot re-decode the encoding by construction.

## Ownership rules

### Architectural semantic decode owns

- ISA/profile legality;
- immediate meaning;
- architectural source/destination registers and dependency facts;
- ALU/word semantic operation;
- branch/jump semantic kind;
- load/store/atomic semantic kind and width;
- AMO aq/rl ordering annotations;
- CSR/system operation meaning;
- FENCE/FENCE.I/SFENCE.VMA semantic identity;
- precise decode/fetch exception facts;
- instruction length and canonical/raw instruction evidence.

### Backend classification owns

- Integer / Branch / MulDiv / Memory / System class;
- `producesValue` for dependency/value lifetime allocation.

### Forbidden coupling

Backend classification must not receive instruction encoding merely to choose execution policy. Raw/canonical instruction evidence may remain inside `DecodedInstruction` for CommitTrace and precise architectural trap values, but is not part of the classifier interface.

If a future extension needs new backend behavior, add an explicit architectural semantic field above this boundary and classify that field below it; do not re-open an encoding side channel.

## Compatibility

`TinySemanticDecode` remains as a thin compatibility composition wrapper. It wires architectural semantic facts into `TinyBackendClassifier`, then builds the existing `RobDispatch` output. Existing core integration therefore does not change in this closure PR.

## Qualification

- F7 `V2F7SemanticDecodeSpec` exercises the compatibility wrapper and checks RV32/RV64 I/M/A/control/memory/system/fence/exception semantics plus Integer/MulDiv/Branch/Memory/System classification;
- `V2DecodeUopBoundarySpec` directly freezes classifier priority and value-production rules;
- `tests_py/test_v2_decode_uop_boundary.py` is automatically executed by the Fast source-contract lane and freezes the encoding-blind hardware interface plus composition ownership;
- normal Fast, F7 and real-software gates remain required.

## Future rule

New ISA extensions add semantic fields/owners above this boundary first. New execution resources consume or classify those semantics below it. Neither side may bypass the boundary merely to avoid adding an explicit semantic representation.
