# AetherCore v2 kickoff constitution

> Status: kickoff constitution for the first AetherCore v2 implementation.
>
> This document is intentionally narrower and firmer than
> `AETHERCORE_V2_DESIGN_NOTES.md`. The design notes preserve alternatives and
> reasoning history; this document records the rules that should be treated as
> the starting constraints for implementation.
>
> Companion audit: `AETHERCORE_V2_REUSE_AUDIT.md`.

## 1. Mission

AetherCore v2 is a new instruction-flow microarchitecture, not a ROB retrofit of
the v1 five-stage pipeline.

The target is a single-person-maintainable, teaching-readable, FPGA-first
RV32/RV64 application-class core that can run real Linux and grow from a simple
ordered bring-up machine into a small-window selectively out-of-order machine
without another ownership rewrite.

The design objective is a strong Pareto point, not maximum feature count:

```text
correctness
+ readability
+ FPGA Fmax
+ useful IPC
+ real-software capability
+ bounded implementation complexity
```

The governing rule is:

> **Parameterize architectural choices; seam microarchitectural choices.**
>
> **体系结构选择参数化，微架构选择留替换缝。**

Or more operationally:

> **Leave seams, not knobs.**

## 2. What is deliberately parameterized

The following are long-lived architectural axes. They are software-visible and
must remain explicit rather than being duplicated into separate RV32/RV64 or
feature-specific cores.

### 2.1 XLEN

```text
RV32
RV64
```

RV32 and RV64 are one architecture and one v2 microarchitecture lineage.
Register width, integer semantics, word operations, CSR width, address handling
and VM geometry derive from the architectural XLEN.

### 2.2 ISA extension surface

Examples include:

```text
I M A C
Zicsr Zifencei
future F/D/B/V/... only when implemented and qualified
```

This is the primary MiniC/AetherCore contract:

```text
compiler-emitted ISA subset <= CPU-implemented ISA
```

Do not split the CPU into independent extension-specific implementations when
one parameterized semantic implementation is sufficient.

### 2.3 Privilege profile

Privilege support is profile-level architecture, for example:

```text
M-only
M/S/U
```

Do not expose every individual CSR/trap behavior as an independent generator
switch. Supported combinations are named, qualified profiles and unsupported
combinations fail closed.

### 2.4 Virtual-memory profile

Examples:

```text
Bare
Sv32
Sv39
future Sv48 if and when integrated
```

XLEN/VM combinations are validated architecturally. A TLB entry count, PTW
queue depth or cache timing choice is not part of this parameter axis.

### 2.5 Architectural address geometry

Physical-address width and page-table geometry are explicit architectural
constraints because they affect PTE composition, PMP coverage, physical access
legality and the system-visible address domain.

They should normally be selected through named supported profiles rather than
advertised as arbitrary integers with an implied all-combinations guarantee.

### Existing `IsaConfig` rule

Do **not** introduce `ArchProfile` merely as a new name and duplicate ownership.
The existing `IsaConfig` already owns most of the software-visible architecture:
XLEN, standard/Z extensions, privilege, VM and architectural capabilities.
Keep it and refine ownership incrementally.

`CoreConfig`/profile composition may gradually make three categories clearer:

```text
architectural profile
  IsaConfig + architectural address constraints

platform / SoC profile
  reset vector + physical map + transport-facing geometry

current implementation constants
  ROB/IQ/cache/width choices of this v2 implementation
```

PMP/timer/other architecture-visible capabilities remain profile-owned and
fail-closed, but they are not an invitation to create a universal arbitrary
feature-combination generator.

## 3. What is NOT an architectural generator parameter

The following may exist as local Scala constants or tightly scoped constructor
arguments for experiments, but AetherCore does not promise arbitrary
combinations of them:

```text
pipeline depth
ROB depth
issue-queue depth
fetch/decode/dispatch/commit width
execution-unit count/topology
MUL/DIV implementation latency
predictor type/size
TLB size/associativity
I$/D$ size/associativity
MSHR count
store/load queue geometry
rename implementation
issue policy
LSU speculation policy
```

For example, the repository may have one explicit current v2 configuration:

```text
ROB = 8
IQ = 8
commitWidth = 1
MSHR = 0
```

and later revise it to another measured implementation. That does not make the
space between those values a supported CPU-generator product surface.

Use Git history and explicit architecture revisions for topology changes rather
than a combinatorial `MicroArchConfig` framework.

## 4. Stable seams that the first implementation must preserve

The exact modules may evolve, but these ownership boundaries should remain
narrow enough that a later implementation can be replaced without redesigning
the rest of the core:

```text
Frontend <-> Backend
Decode semantics <-> backend allocation/rename
Issue <-> Execute
Execute <-> Completion
Backend/LSU <-> Memory subsystem
Core <-> SoC
Predictor <-> Frontend
architectural Commit <-> simulation/reference trace
```

Execution units use tagged request/response contracts and do not know whether
scheduling is ordered or out of order.

The core never speaks AXI/UART/DDR directly. Use a narrow internal memory link
with transaction identity from the start, even when the first implementation
allows only one outstanding transaction.

## 5. Identity must not depend on pipeline location

The v1 identity model is stage occupancy:

```text
IfId / IdEx / ExMem / MemWb
```

v2 replaces this with stable lifetime identities:

```text
RobToken     = ordering/lifetime identity
ProducerTag  = dependency/wakeup identity
ValueRef     = value-storage identity
```

These concepts are separate from day one.

The first physical implementation is deliberately allowed to collapse them:

```text
ProducerTag = ROB-backed RobToken
ValueRef    = ROB result field
```

but downstream interfaces must not rely on numerical equality. A future PRF or
different wakeup/value organization must be possible without rewriting Commit,
Issue and execution contracts.

`RobToken` should carry enough generation/epoch identity to reject stale late
responses after flush/recovery.

## 6. Architectural instruction and backend uOp are different concepts

Do not repeat the v1 mistake of equating concepts merely because the first
implementation maps them 1:1.

Conceptually:

```text
architectural instruction
        |
        v
semantic decode record
        |
        v
backend uOp(s)
        |
        v
ROB / dependency / value identities
```

The first v2 implementation may use:

```text
1 architectural instruction = 1 backend uOp
```

for almost every implemented instruction. The interface must still leave room
for a later instruction to expand into multiple internal actions without
changing architectural retirement identity.

The current `DecodedUop` name in the working notes is not itself sacred. What is
sacred is the semantic boundary: decode describes what the RISC-V instruction
means, not which pipeline stage, bypass mux, ALU port or physical register will
implement it.

Semantic decode may contain:

```text
PC / canonical + raw instruction / instruction length
operation + execution class
logical sources/destination
immediate
memory semantics
control-flow semantics
CSR/system semantics
ordering class
decoded exception metadata
```

It must not contain first-version implementation leakage such as:

```text
pipeline stage
bypass selector
physical-register ID
branch mask
LDQ/STQ slot
MSHR ID
fixed execution-port number
```

## 7. Commit is the architectural owner

Architectural effects become visible at Commit/Retire.

Commit owns at least:

```text
integer architectural register writes
CSR architectural writes
precise trap/xRET state effects
architectural exception/interrupt boundary
store visibility permission
retirement trace
```

Execution may complete out of order; architectural retirement remains in
program order.

Stores may compute address/data early but do not become externally visible
before commit permission. This is a foundational precise-state rule, not a
performance option.

## 8. Final preferred hybrid direction

The mature target is neither a classic five-stage in-order core nor a miniature
BOOM. It is:

> **small-window selective OoO + precise in-order commit + conservative memory ordering**

Desired behavior:

```text
Frontend / Decode / Rename
          |
          +----> ROB: program order
          |
          +----> small fixed-slot issue queues
                         |
                  oldest-ready selection
                  /       |       \
               Integer  Mul/Div  Branch
                         |
                        LSU
                         |
                  Completion
                         |
                        ROB
                         |
                  in-order Commit
```

Integer/branch/MUL-DIV work may execute out of order when dependencies allow.
Retirement remains ordered.

Keep result distribution bounded. A ceiling around two result broadcasts per
cycle is a strong initial design constraint unless measurements justify more.

Use small fixed-slot issue queues rather than importing large collapsing
reservation stations merely because fuller OoO cores use them.

## 9. Memory is conservative by design, not necessarily blocking

The mature memory direction deliberately avoids the most complex speculative
OoO LSU mechanisms.

Favored rules:

1. multiple loads may eventually be outstanding;
2. a younger load never passes an older store whose address is unknown;
3. if all older store addresses are known and non-overlapping, the load may
   proceed;
4. full overlap may forward when older store data is ready;
5. partial overlap may conservatively wait initially;
6. stores become visible only after commit permission and drain in order
   initially;
7. MMIO/device accesses are non-speculative and serialized;
8. AMO/LRSC/FENCE/SFENCE may use a conservative serialized path initially;
9. non-blocking D$ / small MSHR count is allowed without speculative memory
   dependence guessing.

Do not begin with:

```text
load speculation past unknown older stores
memory-dependence predictor
violation detection/replay
large associative LDQ/STQ machinery
```

The important distinction is:

```text
conservative ordering != globally blocking memory
```

Useful MLP can be added through transaction IDs and a small MSHR set while
retaining conservative dependence rules.

## 10. Ordering/system operations use shared classes, not scattered special cases

System behavior should be represented through an ordering/serialization class,
for example:

```text
Normal
SerializeBefore
SerializeAfter
SerializeBoth
MemoryFence
TranslationFence
DeviceOrdered
```

Exact enum names remain implementation details. The rule is that SATP changes,
SFENCE.VMA, FENCE, MMIO, atomics and future system operations reuse shared
ordering/commit machinery rather than growing unrelated global stall/kill
special cases.

## 11. v1 remains alive and v2 reuses machine semantics selectively

Do not rewrite or mutate the v1 pipeline into v2.

v1 remains:

```text
readable correctness/reference core
DiffTest/CommitTrace oracle
architectural qualification vehicle
SoC fallback core
```

v2 rewrites:

```text
instruction lifecycle
stage-based dependency/forwarding ownership
global stall/flush ownership
branch/recovery ownership
atomic scheduling ownership
mature LSU ownership
mature frontend parcel/fetch ownership
```

Reuse or wrap where ownership is already clean:

```text
IsaConfig / ISA legality knowledge
Immediate semantics
RegisterFile initially
CommitTrace
MachineCsrFile architectural state/WARL/trap semantics
SatpRegister
PMP geometry/checking
PageTableGeometry
PageTableWalker
TranslationTlb initially
existing software images and qualification suites
UART/PLIC/timer device semantics
```

Correctness-first VM/data adapters may be reused for early bring-up and then
replaced behind the stable seam when performance work reaches that layer.

## 12. Core / Memory / SoC ownership

Long-term direction:

```text
AetherCore v1 / v2 / future
        |
        v
Frontend + Backend + LSU contracts
        |
        v
Memory subsystem
  translation / TLB / PTW
  I$ / D$
  MSHRs / ordering
        |
        v
AetherMemLink
        |
        v
bus adapter
        |
        v
AetherSoC
  RAM/ROM/UART/timer/PLIC/...
```

Use transaction IDs in the internal memory protocol from the beginning. The
first implementation may use only `txnId=0`; later MSHRs must not force an
interface rewrite.

PMA/memory attributes belong to the memory/system boundary rather than being
scattered as address-range conditionals throughout the LSU.

## 13. Observability and verification are architecture features of the project

`CommitTrace` remains the architectural differential boundary:

```text
AetherCore v1 ----\
                   -> normalized commit comparison -> NEMU / Spike
AetherCore v2 ----/
```

Do not contaminate architectural CommitTrace with v2 scheduling metadata.
Simulation-only tracing may additionally record:

```text
RobToken
fetch / dispatch / issue / complete / retire cycles
queue occupancy
branch recovery
cache/TLB events
MSHR occupancy
stall attribution
```

Hardware PMU remains small and timing-benign.

## 14. First implementation: ordered behavior, final ownership

The first v2 core should deliberately be boring in behavior while already using
the ownership model required by the mature selective-OoO core.

Candidate first implementation:

```text
fetch/decode/dispatch/commit width = 1
ROB = 4 or 8
issue = oldest-only
one simple integer execution path
blocking memory / one outstanding request
no speculative memory
no large PRF/free list
no large predictor
```

Important: these are current implementation choices, **not public architecture
parameters**.

The first goal is to prove the new instruction lifecycle:

```text
fetch
 -> semantic decode
 -> allocate stable instruction identity
 -> dependency representation
 -> execute
 -> complete
 -> ROB ready
 -> in-order Commit
 -> CommitTrace
```

## 15. Evolution sequence

### F0 — semantic/identity contracts

Establish only the narrow records needed by later slices:

```text
semantic decoded operation
backend-uOp boundary
RobToken
ProducerTag
ValueRef
OrderingClass
ExecutionRequest / ExecutionResponse
Commit effect/record
internal memory transaction identity
```

No Linux, no cache architecture and no microarchitecture framework in F0.

### F1 — ROB + explicit Commit

```text
ROB 4/8
allocate one/cycle
complete one/cycle
retire one/cycle
oldest-only behavior
ROB-backed speculative values
```

Run tiny RV32/RV64 integer programs and compare commit traces against v1 and
NEMU/Spike.

### F2 — readiness/tag dependency model

Replace stage-style forwarding assumptions with:

```text
Ready(value)
Pending(ProducerTag)
completion -> wakeup
```

Keep issue oldest-only so functionality remains easy to debug.

### F3 — decoupled functional units

Split simple integer ALU, branch, MUL and iterative DIV/REM behind tagged
request/response interfaces. Variable latency may now exist while retirement
remains precise.

### F4 — branch recovery

Resolve branch early, redirect Frontend, invalidate younger ROB entries and
rebuild small speculative state from committed state + surviving ROB rather
than introducing heavy branch checkpoints immediately.

### F5 — privileged/CSR integration

Wrap the existing qualified machine/CSR semantics behind commit-owned effects.
Only Commit mutates architectural CSR/privilege state.

### F6 — correctness-first LSU + PMP/VM

Bring up blocking/one-outstanding memory first using existing translation/PMP
semantics behind adapters. Do not add MSHRs merely to reach Linux parity.

### F7 — software parity

Progressively regain:

```text
ISA tests
M/S/U
PMP
VM
OpenSBI
Linux
PID1/userspace
```

This is the end of the architecture-migration phase.

### Performance phase — selective OoO

Only after correctness parity:

```text
oldest-only -> oldest-ready
small fixed-slot IQ
variable-latency overlap
simple predictor
store queue
non-blocking D$ + ~2 MSHRs
known-store disambiguation / full-overlap forwarding
2-wide frontend/dispatch/retire only when measured worthwhile
ROB/IQ growth only from occupancy/profile evidence
```

A PRF, speculative load/replay, wider issue or larger structures remain future
options, not promises.

## 16. Performance decision rule

Every significant performance feature must answer:

1. Which measured stall/resource bottleneck does it address?
2. What whole-program speedup is plausible?
3. What does it cost in FPGA Fmax, LUT, FF, BRAM or DSP?
4. Can a simpler mechanism capture most of the gain?

Always evaluate at least:

```text
cycles
instret
IPC / CPI
Fmax
IPC * Fmax
LUT / FF / BRAM / DSP
real Linux workload behavior
```

A feature with positive IPC but negative `IPC × Fmax` is not automatically a
win. The project is allowed to delete performance features that fail ROI.

## 17. Hard non-goals

Do not start v2 by building:

```text
arbitrary pipeline-stage generator
universal MicroArchConfig
plugin framework for arbitrary execution topology
BOOM-size PRF/free-list backend
large ROB/reservation stations
speculative load/replay machinery
wide result-broadcast network
AXI inside the core
repository-wide package migration
simultaneous CSR/MMU/device semantic rewrite
```

Do not copy BOOM, Rocket, NaxRiscv, Bergamot, CVA6, BlackParrot or XiangShan as
a feature checklist. They are references for ownership, correctness problems,
FPGA tradeoffs and design audits.

## 18. Definition of a healthy v2 start

A v2 slice is healthy when:

- v1 remains unchanged and runnable as the reference core;
- the change has one clear ownership purpose;
- new contracts contain no five-stage names or current bypass/stall details;
- architecture parameters stay limited to the explicit software-visible axes;
- microarchitecture alternatives are represented by narrow seams, not public
  combinatorial knobs;
- unsupported architectural profiles fail closed;
- the smallest relevant directed/commit-diff gate passes before adding another
  mechanism;
- real software and FPGA profiling are introduced progressively rather than
  used to justify premature complexity.

If a first slice needs a large framework before it can retire a handful of
integer instructions, the slice is too abstract.
