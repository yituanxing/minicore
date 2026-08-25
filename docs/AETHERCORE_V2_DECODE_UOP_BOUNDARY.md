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
        v
TinyBackendClassifier
        |
        v
RobDispatch                 <- first backend-owned classification
        |
        v
ROB allocation / dependency / scheduling / execution
```

`DecodedInstruction` is the architectural semantic contract. It may retain canonical/raw instruction bits for commit trace and precise trap evidence, but downstream backend policy must not re-interpret opcode/funct fields to recover meaning that decode should have supplied.

`TinyBackendClassifier` is the first microarchitectural owner. It derives only:

- `ExecutionClass`;
- whether the instruction produces an architectural register value.

The classifier consumes semantic fields (`system.kind`, `memory.kind`, `controlFlow.kind`, `aluOp`, destination/exception facts). It must not decide ISA legality or reconstruct operation semantics from instruction encoding bits.

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

Backend stages must not use opcode/funct/raw instruction bits to choose execution policy when an equivalent semantic field exists. Raw instruction evidence may still be carried for CommitTrace and architectural trap values.

## Compatibility

`TinySemanticDecode` remains as a thin compatibility wrapper composing the two layers and preserving its existing `RobDispatch` output. Existing core integration therefore does not change in this closure PR.

## Qualification

- existing `V2F7SemanticDecodeChecks` continue to validate RV32/RV64 I/M/A/control/memory/system/fence/exception behavior through the compatibility wrapper;
- `V2DecodeUopBoundarySpec` directly freezes classifier priority and proves that changing canonical/raw instruction evidence cannot change backend class when semantic inputs are unchanged;
- normal Fast and real-software gates remain required.

## Future rule

New ISA extensions add semantic fields/owners above this boundary first. New execution resources consume or classify those semantics below it. Neither side may bypass the boundary merely to avoid adding an explicit semantic representation.
