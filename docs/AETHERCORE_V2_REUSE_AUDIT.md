# AetherCore v2 reuse audit

> Status: working migration audit, not a frozen v2 implementation specification.
>
> Audited functional baseline: `explore/rv64-linux-initramfs-v1` exact head
> `34f460d6639361df9cdb23c8f2070084a888159b` (PR #135).
>
> Companion architecture notebook: `AETHERCORE_V2_DESIGN_NOTES.md`.

## 1. Purpose

AetherCore v1 is now deep enough to serve as a correctness/reference implementation: the same core has been exercised across RV32/RV64, M/S/U privilege, PMP, Sv32/Sv39, atomics, OpenSBI and unchanged Linux. The next generation should therefore not spend its first months re-deriving architectural semantics that v1 already qualified.

The migration rule is:

**rewrite instruction-flow ownership; reuse architecture/machine semantics where their current ownership is already clean.**

This audit classifies the current implementation into:

- **KEEP** — reusable essentially as-is;
- **WRAP / ADAPT** — semantics are valuable, but the v2 interface or timing ownership must change;
- **REWRITE** — current implementation is inherently tied to the v1 five-stage microarchitecture;
- **EARLY-ONLY** — useful to bring up v2 safely, but should not become the mature performance path.

The goal is not a repository-wide package shuffle. Components move or receive new interfaces only when a v2 slice actually consumes them.

## 2. Architecture configuration: KEEP, clarify ownership

### `IsaConfig`

**KEEP.**

The existing `IsaConfig` already describes the software-visible architectural contract:

- XLEN 32/64;
- standard instruction extensions;
- Z extensions;
- privilege modes;
- paged VM modes;
- PMP count;
- timer/CSR architectural capabilities.

This matches the long-term AetherCore/MiniC boundary. It is not a microarchitecture generator configuration.

The v2 rule should remain:

```text
MiniC target profile
        |
        |  RV32/RV64 + ISA/ABI contract
        v
AetherCore IsaConfig
        |
        v
one explicit current microarchitecture
```

Do not add ROB depth, pipeline stage count, issue width, FU topology, cache policy, rename strategy or LSU speculation policy to `IsaConfig`.

### `CoreConfig`

**WRAP / SPLIT GRADUALLY.**

The current `CoreConfig` combines `IsaConfig` with `PlatformConfig`. That is acceptable for v1, but v2 should make the distinction explicit:

```text
ArchitecturalProfile
  ISA / XLEN / privilege / VM / architectural PA constraints

Implementation constants
  current v2 ROB/IQ/cache sizes and widths

Platform / SoC profile
  reset vector / memory map / bus-adapter-facing widths
```

Do not build a universal `MicroArchConfig` API. Implementation constants may still be Scala constructor constants where useful, but only named supported configurations are qualification targets.

### `PageTableGeometry`

**KEEP.**

This is already the right abstraction: specification-level Sv32/Sv39/Sv48 geometry, intentionally separated from claims about implemented production modes.

## 3. v1 top-level pipeline: REWRITE

### `AetherCore.scala`

**REWRITE instruction-flow ownership for v2; preserve v1 unchanged as reference.**

The current top-level explicitly owns:

```text
IfId -> IdEx -> ExMem -> MemWb
```

and combines:

- PC/fetch state;
- RVC parcel control;
- decode;
- register-file reads and forwarding;
- load-use bubbles;
- branch redirect;
- CSR legality and forwarding;
- trap/interrupt selection;
- PMP;
- instruction/data translation;
- PTW arbitration;
- LR/SC reservation;
- AMO read/write phase state;
- global memory stall;
- VM-context serialization;
- architectural commit.

The implementation is coherent for a five-stage correctness core, but its instruction identity is the pipeline stage. v2 needs instruction identity independent of location:

```text
RobToken     = ordering/lifetime identity
ProducerTag  = dependency identity
ValueRef     = storage identity
```

Do not copy `AetherCore.scala` and gradually insert ROB/rename/issue machinery around `IfId/IdEx/ExMem/MemWb`.

### `IfId`, `IdEx`, `ExMem`, `MemWb`

**REWRITE / v1-only.**

They are useful documentation of v1 timing but must not become shared contracts.

### forwarding and load-use hazard network

**REWRITE.**

Current dependency ownership is stage matching:

```text
EX/MEM.rd -> ID/EX.rs
MEM/WB.rd -> ID/EX.rs
load-use -> bubble
```

v2 replaces this with source readiness/tag state:

```text
source = Ready(value) | Pending(ProducerTag)
completion -> wakeup
issue policy chooses a ready uOp
```

Do not run both forwarding and tag dependency models in the mature v2 backend.

### global stall / flush control

**REWRITE.**

Current `frontendAdvance` is a global conjunction over trap, interrupt, xret, SFENCE, WFI, memory stall, atomic phase, redirect and load-use hazard. This is exactly the ownership v2 is meant to remove.

Use local ready/valid/queue backpressure and typed redirect/serialization effects instead.

### VM-context CSR serialization

**KEEP SEMANTICS, REWRITE MECHANISM.**

The Linux-proven rule is valuable: retiring `satp`/relevant `mstatus`/`sstatus` changes must not allow younger work to execute under stale translation/permission context.

v2 should express this as a uOp ordering/commit effect, not explicit invalidation of four stage registers.

## 4. Decode and internal instruction representation

### `Decoder.scala`

**KEEP ISA legality/tables; WRAP output.**

The current decoder is already parameterized by `IsaConfig` and contains qualified RV32/RV64/M/A/Zicsr/Zifencei legality rules. Rewriting that knowledge from scratch would be unnecessary risk.

Its current `ControlSignals` output is pipeline-oriented (`opASel`, `wbSel`, `memRead`, etc.). v2 should introduce a semantic `DecodedUop` boundary.

Candidate first record:

```text
DecodedUop
  pc
  canonicalInst / rawInst / instBytes
  operation / execution class
  logical rs1 / rs2 / rd
  immediate
  memory operation + size/sign
  branch/jump semantics
  CSR/system operation
  ordering class
  decoded exception metadata
```

Do not place ROB IDs, physical-register IDs, branch masks or LDQ/STQ indices in `DecodedUop`; those are later microarchitectural records.

### `ControlSignals`

**v1-only as a backend contract.**

Individual enums such as ALU op, memory size, branch type and atomic operation may remain useful semantic vocabulary, but the aggregate v1 control bundle should not cross v2 module boundaries.

### `Immediate`

**KEEP.**

Pure instruction semantics, no v1 stage ownership.

## 5. Execution units

### `ALU.scala`

**SPLIT / WRAP.**

The integer operation semantics are reusable, but the current module also implements MUL/DIV/REM in one combinational unit, including direct `/` and `%` operators. That is a correctness-friendly v1 implementation but a likely FPGA Fmax hazard.

v2 direction:

```text
IntegerUnit
  ADD/SUB/logic/shift/compare

MulUnit
  decoupled, implementation chosen for FPGA PPA

DivUnit
  decoupled, iterative/variable latency
```

All execution units use a tagged request/response contract and do not know whether scheduling is ordered or OoO.

### branch unit

**REWRITE as an explicit FU.**

Current branch comparison/target/redirect is embedded in `AetherCore.scala`. v2 should make branch resolution a typed execution result carrying `RobToken` and redirect feedback.

### atomic execution

**REWRITE ownership into LSU/Memory End.**

Current global `atomicWritePhase`/`atomicOldData` state is v1-specific. Backend should see an atomic memory uOp; the LSU owns serialized read-modify-write sequencing, reservation state and eventual completion.

## 6. Register state and commit

### `RegisterFile.scala`

**KEEP / WRAP initially.**

The simple 32 x XLEN committed architectural RF is a good first-v2 component. With ROB-backed speculative values, v2 can continue to write it only at Commit.

Future PRF research must not be forced into the first implementation. The `ValueRef` abstraction is the seam that allows value storage to move later.

### `CommitTrace`

**KEEP AND STRENGTHEN.**

This is one of the most valuable migration assets. It already records:

- PC;
- canonical/raw instruction and length;
- integer register effect;
- memory effect;
- synchronous exception;
- interrupt.

Use it as the common oracle:

```text
AetherCore v1 ----\
                   -> normalized commit comparison -> NEMU/Spike
AetherCore v2 ----/
```

Add v2-only simulation metadata separately; do not pollute architectural `CommitTrace` with issue/ROB timing.

## 7. CSR / privilege / traps

### `MachineCsrFile.scala`

**KEEP architectural state and WARL/trap semantics; WRAP interface.**

This module contains expensive qualified behavior:

- M/S/U state;
- `mstatus`/`sstatus` transitions;
- delegation;
- interrupt qualification;
- `misa`;
- PMP CSRs;
- `satp`;
- time/Sstc support;
- trap entry;
- xRET.

Do not rewrite this state machine merely because v2 has a different backend.

Preferred v2-facing concepts:

```text
CsrReadRequest/Response
CsrCommitEffect
TrapCommitEffect
XretCommitEffect
ArchitecturalContext
```

Only Commit may mutate architectural CSR/privilege state. Decode/issue may read required context, but speculative execution must not directly perform CSR side effects.

### `SatpRegister.scala`

**KEEP.**

It already separates satp field/state semantics from the translation engine and fails closed on unsupported MODE writes.

## 8. PMP

### `PmpGeometry`, `PmpCsrFile`, `PmpChecker`

**KEEP.**

`PmpChecker` is already a stand-alone combinational physical access checker with no five-stage dependency. It is exactly the kind of architecture/machine component v2 should reuse.

PMP should remain downstream of translation and upstream of actual physical side effects. Do not duplicate PMP logic inside caches, LSU or PTW.

## 9. VM / MMU

### `PageTableWalker.scala`

**KEEP.**

This is one of the cleanest current components. It explicitly owns only geometry-driven traversal, PTE legality, permissions, canonical VA checks, superpage alignment and final PA composition. It deliberately does not own satp activation, TLB policy, PMP/PMA or A/D hardware update.

This ownership should survive v2.

### `TranslationTlb.scala`

**KEEP initial implementation / allow future replacement.**

The geometry-driven fully-associative TLB is conservative and easy to audit. It is a good bring-up TLB. Future performance work may change indexing, permission storage, ports or parallel lookup timing without changing page-table semantics.

### `TranslationUnit.scala`

**EARLY-ONLY reusable composition.**

It has clean semantic composition, but it is intentionally single-request and state-machine based:

```text
idle -> walking / bypassResponse / tlbResponse
```

That is ideal for correctness bring-up and can back the first v2 blocking path.

The mature frontend/LSU may need a different timing composition for:

- ITLB/D$/I$ parallel lookup;
- multiple in-flight translations;
- non-blocking cache misses;
- decoupled PTW traffic.

Do not prematurely rewrite it before v2 reaches the VM integration slice.

### `InstructionFetchAdapter.scala`

**EARLY-ONLY.**

Useful for the first v2 VM bring-up. Mature Frontend should own ITLB/VIPT I$/FetchBlock/Align/Decompress/FetchQueue timing directly behind a stable frontend-memory contract.

### `DataPathAdapter.scala`

**EARLY-ONLY, then REPLACE performance path.**

Its own documentation calls it a serial correctness-first data-side adapter. That makes it excellent for initial v2 Linux parity, but not the final non-blocking LSU/cache architecture.

### `PtwArbiter.scala`

**KEEP concept, likely WRAP later.**

Current deterministic data-over-fetch arbitration is correct for the single-outstanding v1 composition. A future Memory End may move to request IDs/credits or a shared PTW request queue, but frontend/backend should not know the arbitration detail.

## 10. Compressed frontend

### `Rv32CDecompressor.scala`

**KEEP useful decode knowledge; GENERALIZE for v2.**

The module is intentionally RV32C-only. v2 architecture wants the ISA/XLEN contract to support RV32 and RV64 without duplicating the core. Build a common RVC semantic decompressor that is parameterized by architectural XLEN and explicitly covers the different RV32C/RV64C encodings.

Do not copy the entire v1 parcel pipeline.

### `Rv32CParcelController.scala`

**REWRITE frontend ownership.**

The mature v2 frontend should operate on fetch blocks + alignment/decompression + FetchQueue. Parcel ownership must not be embedded in global PC generation.

## 11. Current memory/bus interfaces

### `InstructionBusIO`, `DataBusIO`, `PageTableReadBusIO`

**KEEP only as v1 compatibility surfaces.**

Current data request/response has no transaction identity and assumes a request stays associated with the eventual `ready/rdata` response. This cannot represent mature v2 multiple outstanding cache misses.

Introduce a narrow internal physical-memory protocol early:

```text
AetherMemRequest
  txnId
  op
  paddr
  size
  mask
  data
  attributes

AetherMemResponse
  txnId
  data
  fault/status
  last
```

The first v2 configuration may allow only one outstanding request and use `txnId=0`; the interface must not require a later topology rewrite when MSHRs appear.

Core should not speak AXI directly.

## 12. Simulation platform / SoC

### `AetherCoreOpenSbiSimTop.scala`

**KEEP platform profile and software environment.**

This is already shared across RV32 and RV64 by selecting a different architectural profile rather than duplicating the platform. That is exactly the desired direction.

### `AetherCoreSimTop.scala`

**KEEP device models and qualification semantics; REFACTOR transport ownership later.**

The current simulation top directly decodes `core.io.dmem` into:

- RAM forwarding;
- UART/ns16550;
- exit MMIO;
- `mtime/mtimecmp`;
- PLIC/interrupt transport.

That is tightly coupled to the v1 single-request `DataBusIO`, so it should not become the long-term AetherSoC fabric.

However, the device behavior itself is valuable and already pressured by OpenSBI/Linux. The migration should be:

```text
v1 DataBus adapter ----\
                       -> AetherMemLink / simple fabric -> device models / RAM
v2 Memory End --------/
```

Do not rewrite UART/PLIC/timer semantics while simultaneously bringing up v2.

## 13. Proposed package evolution

Do not perform this as one mechanical PR. It is a target ownership map:

```text
aethercore/
  arch/
    ISA/decode semantics
    CSR/privilege/trap definitions
    VM geometry

  core/
    v1/                  # current reference ownership, moved only if useful
    v2/
      DecodedUop
      identity
      frontend/
      backend/
      execute/
      lsu/
      commit/

  memory/
    translation/
    cache/
    AetherMemLink

  platform/
    device models / interrupts / timer

  sim/
    v1/v2 adapters + qualification tops
```

The first v2 PR does **not** need this complete directory tree.

## 14. First v2 foundation slices

### V2-F0 — semantic records only

Add without connecting to Linux:

```text
DecodedUop
RobToken { index, generation }
ProducerTag
ValueRef
OrderingClass
ExecutionRequest / ExecutionResponse
CommitEffect
```

Rules:

- parameterized by architectural XLEN/ISA where appropriate;
- no pipeline-stage names;
- no PRF/branch-mask/LDQ/STQ fields;
- unit tests for identity wrap/generation and uOp construction.

### V2-F1 — ROB + explicit Commit, one wide

Build a tiny backend with:

```text
ROB = 4
allocate one/cycle
complete one/cycle
retire one/cycle
```

No OoO scheduling yet. Values may live in the ROB. Commit writes the existing architectural RF.

Run small integer programs and compare v1/v2/NEMU commit traces.

### V2-F2 — tag/readiness dependency model

Introduce rename/speculative map and source readiness. Keep issue policy `oldest-only` so behavior remains ordered while dependency ownership is already the final style.

### V2-F3 — decoupled execution

Split:

- integer ALU;
- branch unit;
- MUL;
- iterative DIV/REM.

Allow variable-latency completion while keeping retirement precise.

### V2-F4 — branch recovery

Use `RobToken` validity/generation, invalidate younger work, redirect frontend immediately, rebuild speculative map from committed state + surviving ROB for the first small-window design.

### V2-F5 — CSR/trap/privilege adapter

Wrap `MachineCsrFile` behind commit-owned architectural effects. Reuse existing CSR state and real privilege tests.

### V2-F6 — blocking memory parity

Connect a simple LSU through existing translation/PMP machinery and one-outstanding memory adapter. Do not add MSHRs yet.

### V2-F7 — software parity

Progressively run:

```text
ISA executable
M/S/U
PMP
VM
OpenSBI
Linux banner
PID1/userspace
```

Only after this baseline is stable should the performance sequence begin.

## 15. Performance sequence after parity

Candidate measured sequence:

```text
predictor
  -> ready-select integer issue
  -> 2-wide frontend/commit where useful
  -> store queue
  -> non-blocking D$ + 2 MSHRs
  -> VIPT/translation timing work
  -> larger ROB/IQ only if occupancy/profile proves need
```

Every step records:

```text
IPC
cycles / instret
Fmax
IPC * Fmax
LUT / FF / BRAM / DSP
real Linux workload change
```

## 16. Hard non-goals for the first refactor

Do not start with:

- arbitrary pipeline-stage generator;
- arbitrary execution-unit plugin graph;
- BOOM-size PRF/free-list backend;
- large ROB;
- speculative load past unknown stores;
- memory-dependence predictor/replay;
- AXI wired directly into core;
- a repository-wide file move;
- simultaneous CSR/MMU/device semantic rewrite.

## 17. Reuse summary

| Area | Classification | v2 action |
|---|---|---|
| `IsaConfig` | KEEP | software-visible architecture contract |
| `CoreConfig` | WRAP | separate arch/platform/implementation ownership gradually |
| `PageTableGeometry` | KEEP | shared VM geometry |
| `AetherCore.scala` | REWRITE | new instruction lifecycle |
| stage bundles | REWRITE | v1 only |
| forwarding/load-use | REWRITE | tags/readiness |
| `Decoder` | WRAP | retain legality, emit `DecodedUop` |
| `Immediate` | KEEP | shared semantic helper |
| `ALU` | SPLIT | simple ALU + decoupled MUL/DIV |
| `RegisterFile` | KEEP/WRAP | committed RF initially |
| `CommitTrace` | KEEP+ | v1/v2/reference oracle |
| `MachineCsrFile` | WRAP | retain state/WARL, commit-oriented interface |
| `SatpRegister` | KEEP | shared architecture state |
| PMP | KEEP | shared physical protection |
| `PageTableWalker` | KEEP | shared traversal semantics |
| `TranslationTlb` | KEEP initially | replace only for measured timing need |
| `TranslationUnit` | EARLY-ONLY | blocking bring-up composition |
| instruction/data VM adapters | EARLY-ONLY | replace mature cache/LSU timing path |
| `PtwArbiter` | WRAP later | Memory End-owned arbitration |
| RV32C decompressor | GENERALIZE | shared RV32/RV64 C semantic decoder |
| parcel controller | REWRITE | FetchBlock/Align/Decompress/Queue |
| v1 bus IO | COMPAT ONLY | adapt to `AetherMemLink` |
| OpenSBI/Linux sim platform | KEEP environment | share device semantics |
| `AetherCoreSimTop` transport | REFACTOR later | fabric/adapters, not v1 dmem decode |
| existing software/CI | KEEP | primary regression/qualification asset |

## 18. Definition of readiness to start v2

Start the first v2 foundation implementation when:

1. the public repository has a functioning GitHub-hosted Fast Gate;
2. the deepest RV64 PID1 checkpoint can execute without a personal runner, or any remaining failure is conclusively a functional #135 issue rather than missing infrastructure;
3. this reuse audit and the architecture notebook remain Draft/working documents, not falsely frozen specs;
4. v1 remains intact and runnable as the reference core.

At that point, create a new v2 foundation branch rather than modifying `AetherCore.scala` in place.
