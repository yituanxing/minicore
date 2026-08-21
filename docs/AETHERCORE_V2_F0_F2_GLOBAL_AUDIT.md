# AetherCore v2 F0-F2 global architecture audit

Status: implementation checkpoint, 2026-08-21

This note records the architecture review performed after the first F0/F1/F2 implementation slices. It is intentionally narrower than the general design notes: its purpose is to distinguish what is already owned correctly from what must remain deferred to F3/F4/F5/F6.

## 1. Checkpoint

The implementation sequence remains:

```text
F0 semantic / identity / execution contracts
  -> F1 Tiny ROB + explicit Commit
  -> F2 dependency readiness / RAT
  -> F3 decoupled execution
  -> F4 branch recovery
  -> F5 CSR / privileged integration
  -> F6 blocking LSU / PMP / VM integration
  -> F7 software parity
```

F1 is frozen at exact head:

```text
17de06255163f2204c8b47f3be11aae6ca940a29
```

with hosted Fast Gate and real RV64 Linux PID1 qualification green.

At this audit, F2 PR #141 is stacked directly on that exact F1 head. The inspected F2 candidate is:

```text
e5f30c0ee320b223da7a552584737fb11ad4b6e2
```

This F2 head is not called frozen until its complete hosted qualification finishes.

## 2. Ownership that remains correct

### Architectural configuration

Keep the existing split:

```text
IsaConfig
  xlen / ISA extensions / privilege / VM / PMP / architectural capabilities

PlatformConfig
  reset vector / physical-address width / bus width / platform memory map

CoreConfig
  composition + implementation capability validation
```

Do not add an `ArchProfile` just to rename or re-wrap the same facts.

### Fixed first microarchitecture geometry

The first Tiny ROB remains a fixed implementation choice, not a public generator axis.

`TinyRobGeometry` is the single package-private owner of the current:

```text
entries          = 4
ROB index bits   = 2
generation bits  = 2
```

F1 and F2 derive ROB-parallel structures from this owner. Do not create a broad `MicroArchConfig` merely to expose these constants.

### Identity ownership

Keep the three concepts type-distinct even while the first implementation allocates numerically matching values:

```text
RobToken    -> instruction lifetime / program order
ProducerTag -> dependency mapping / wakeup
ValueRef    -> value-storage identity
```

F2 producer state is addressed by `ProducerTag`, not by borrowing `RobToken.index` because the numbers currently happen to match.

### Completion authority

The accepted-completion path remains:

```text
raw ExecutionResponse
        -> TinyRob validates RobToken + ProducerTag + ValueRef
        -> acceptedCompletion
        -> dependency wakeup by ProducerTag
```

F2 must never wake directly from an unvalidated external completion.

### Architectural state

`V2Commit` remains the only architectural integer-register write owner. The existing `RegisterFile` remains committed state. Speculative dependency state must not become an alternate architectural register file.

### Memory identity

`AetherMemLink.txnId` is memory-transaction identity, not instruction identity. A future LSU owns the mapping between a live memory uOp and its memory transaction(s). The memory subsystem must not know ROB internals.

## 3. Semantic holes found before F3

The audit compared the new v2 records against the qualified v1 decoder/ALU behavior. Four missing semantics were found. These are contract corrections, not new microarchitectural features.

### 3.1 RV64 W-class operations

`AluOp` is shared by ADD/ADDW, DIV/DIVW, REM/REMW and related operations. The qualified v1 implementation carries an independent `wordOp` bit.

Therefore v2 must preserve:

```text
DecodedInstruction.wordOp
ExecutionRequest.wordOp
```

An execution unit must not re-decode opcode bits to rediscover W-class behavior.

### 3.2 Semantic operand origins

`aluOp + immediate + usesRs1/usesRs2` is not enough to distinguish operations such as:

```text
LUI    = 0  + U-immediate
AUIPC  = PC + U-immediate
ADDI   = rs1 + I-immediate
ADD    = rs1 + rs2
```

The semantic record therefore owns:

```text
OperandSourceKind = Zero / Rs1 / Rs2 / Pc / Immediate
DecodedInstruction.lhsSource
DecodedInstruction.rhsSource
```

This is instruction meaning, not a v1 `OpASel`/`OpBSel` pipeline mux contract.

F3 should materialize concrete `lhs/rhs` once at the issue boundary and execution units should consume those concrete operands.

### 3.3 Instruction length at the branch execution seam

The qualified v1 core computes a jump link as:

```text
pc + instBytes
```

not a fixed `pc + 4`. RV32C therefore requires a possible `pc + 2` link.

`ExecutionRequest.instBytes` is required so the branch unit can produce the architectural link value without examining raw instruction bits.

### 3.4 CSR immediate zimm

CSR immediate instructions encode a zero-extended five-bit `zimm` in the instruction field otherwise named `rs1`.

The semantic record must not force F5 to reinterpret a logical register identifier as an immediate. Preserve:

```text
DecodedSystemOperation.csrUseImmediate
DecodedSystemOperation.csrImmediate
```

For an immediate CSR operation, dependency tracking should not create an rs1 register dependency merely because the encoded field is non-zero.

## 4. F2 remains deliberately small

F2 owns only:

- a 32-entry architectural-register RAT;
- a Tiny-ROB-sized producer scoreboard;
- ROB-parallel source readiness metadata;
- `Ready(value)` versus `Pending(ProducerTag)` resolution;
- completion wakeup and completed-value retention;
- WAW-safe speculative mapping clear.

F2 does not own:

- issue queues;
- reservation stations;
- physical register files;
- free lists;
- oldest-ready selection;
- execution dispatch;
- branch recovery;
- memory ordering;
- caches/MSHRs;
- predictor metadata.

This remains consistent with the initial ordered implementation and future selective-OoO seams.

## 5. F3 hard boundary: decoupled execution without OoO machinery

The first F3 should look conceptually like:

```text
ROB head + F2 ready operands
            |
            v
oldest-only issue owner
  - materialize semantic lhs/rhs
  - remember issued RobToken
  - prevent duplicate issue while the head is outstanding
            |
   +--------+---------+----------+
   |        |         |          |
Integer   Branch     MUL    iterative DIV/REM
   |        |         |          |
   +--------+---------+----------+
            |
     completion arbitration
            |
         TinyRob
```

Important rules:

1. Functional units use decoupled request/response contracts and preserve all execution identities.
2. Units do not know whether issue is in-order or out-of-order.
3. Units do not re-decode raw instructions.
4. The first issue policy remains oldest-only; do not add an issue queue merely because the unit interfaces could support one later.
5. The issue owner, not dependency state, owns the transient fact that the current head has already been issued.
6. Integer/branch/MUL may use short fixed latency initially.
7. DIV/REM must not inherit v1's combinational `/` and `%` latency model. Use an iterative divider with the existing qualified arithmetic results as the semantic oracle.
8. With oldest-only issue, only one head needs to be outstanding in the first implementation, so do not build a wide result-broadcast network yet.

Leaf execution units should receive only the architectural facts they need. Do not pass the full `CoreConfig` into every unit:

- integer/MUL/DIV: XLEN and operation semantics;
- branch: XLEN plus the instruction-alignment rule derived by composition from `IsaConfig.hasC`.

## 6. F4/F5 recovery gates

F2 does not implement speculative recovery. Before branch/trap integration becomes executable, F4/F5 must own:

- redirect and younger ROB invalidation;
- speculative RAT rebuild from committed state plus surviving older ROB entries;
- blocking/killing younger dispatch across a retiring exception/trap boundary;
- rejection of late responses from killed instructions;
- explicit re-review of the current two-bit generation width once killed variable-latency operations can survive long enough to return after slot reuse.

Do not hide these rules inside F2 dependency wakeup.

A particularly important regression is:

```text
trapping producer at head
+ younger consumer of its destination
+ recovery/flush
=> no surviving pending dependency on a value that cannot architecturally commit
```

## 7. F6 memory gates

Before memory architectural effects are enabled in v2:

1. LSU owns live-uOp <-> `AetherMemLink.txnId` association.
2. `AetherMemLink` stays independent from ROB/dependency identities.
3. Stores become externally visible only under Commit ownership.
4. MMIO/device accesses are conservative and serialized initially.
5. `CommitTrace` physical-address and bus-data widths must be constructed from the composed `CoreConfig.platform`; the F1/F2 harness defaults are not the final memory geometry.
6. PMP/VM are adapters reused from qualified v1 semantics, not copied stage logic.

## 8. Things intentionally not fixed now

The following are not F0-F2 blockers:

- exact FENCE predecessor/successor masks: the first memory design may conservatively implement a stronger full drain;
- branch prediction metadata: belongs to F4/backend prediction/recovery records, not `DecodedInstruction`;
- PRF/value-store implementation: `ValueRef` remains a seam, not a demand to build a PRF now;
- branch checkpoints: current preferred small-window recovery remains invalidate-younger + rebuild;
- wider issue/retire or larger ROB: measurement-driven future choices, not current knobs.

## 9. Audit conclusion

F0-F2 have not drifted into a conventional large OoO backend or a renamed copy of the v1 five-stage pipeline.

The main issue found by the audit was the opposite: a few architectural semantics were too thin and would have forced later units to re-decode raw instruction bits. Closing those seams before F3 keeps the architecture simpler:

```text
Decode owns meaning.
ROB owns lifetime/order.
Dependency state owns readiness/wakeup.
Issue owns dispatch policy and issued state.
Execution owns computation/latency.
Commit owns architectural effects.
LSU owns instruction-to-memory transaction state.
Frontend/recovery owns redirection and speculative repair.
```

That ownership map is the reference point for the next implementation slice.