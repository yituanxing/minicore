# AetherCore v2 architecture philosophy and long-term direction

Status: long-lived design record reconstructed and revalidated after F7 software parity.

Frozen architectural reference: `418a4b798410df45c0218f1a0122cdf0199211f6` (F7).

This document records **why AetherCore v2 has the architecture it has**, not merely what the current RTL implements. It consolidates the reasoning that originally came from the v1 five-stage core, the MiniC restructuring method, and repeated comparison with Rocket, CVA6, Bergamot, BOOM and XiangShan.

The post-F7 audit may revise implementation sequencing, but this document is the long-term design philosophy unless an explicit architecture revision replaces it.

---

## 1. The design objective is a Pareto point, not maximum IPC

AetherCore is not trying to maximize benchmark score at any implementation cost.

The objective is:

```text
correctness
+ readability
+ FPGA Fmax
+ useful IPC
+ real Linux capability
+ single-person maintainability
+ bounded verification complexity
```

The important word is **bounded**.

Many mechanisms used by BOOM or XiangShan are valuable and improve performance. AetherCore does not reject them because their performance is poor. It rejects or defers some of them because, **for this project**, their marginal performance gain may not justify their marginal RTL complexity, verification state space, FPGA routing pressure, Fmax loss and long-term maintenance cost.

The target is therefore the strongest point before the project's own complexity cliff, not a deliberately weakened imitation of a larger OoO core.

---

## 2. Where we came from: the v1 five-stage core

AetherCore v1 was built as a classic scalar in-order pipeline:

```text
IF
 |
ID
 |
EX
 |
MEM
 |
WB / architectural commit
```

Its instruction lifetime was strongly tied to stage occupancy:

```text
IfId
IdEx
ExMem
MemWb
```

This was an excellent architecture for qualification. It made hazards, traps, forwarding, memory stalls and commit behavior visible and understandable, and it carried the project through RV32/RV64, privilege, PMP, VM, RTOS and Linux bring-up.

However, the same ownership becomes a barrier to useful latency hiding. If an old DIV, cache miss or other long-latency operation stalls the pipeline, younger independent arithmetic cannot use otherwise-idle execution resources.

Example:

```text
I0: DIV  x1, x2, x3     // long latency
I1: ADD  x4, x5, x6     // independent
I2: XOR  x7, x8, x9     // independent
I3: ADD  x10, x4, x11
```

A strict five-stage implementation tends toward:

```text
DIV ==================================>
                                      ADD
                                          XOR
                                              ADD
```

The key question that started v2 was therefore **not** simply “should we build an OoO CPU?”

It was:

> What is the smallest amount of out-of-order machinery that captures the large, obvious latency-hiding gains without forcing the whole core into a BOOM/XiangShan-class complexity regime?

---

## 3. Do not retrofit a ROB into the old five-stage ownership

The rejected direction was:

```text
old IF/ID/EX/MEM/WB ownership
            +
ROB / rename / issue / recovery
```

That would leave two competing lifetime models:

- stage position as the old identity/ownership;
- ROB/tag state as the new identity/ownership.

This is exactly the kind of mixed architecture that becomes difficult to reason about.

Therefore v2 is a **new instruction-flow microarchitecture**, not a ROB bolted onto v1.

The rule is:

> rewrite instruction-flow ownership; reuse already-qualified architectural semantics where ownership is clean.

v1 remains alive as a readable reference core, qualification vehicle and differential oracle.

---

## 4. What we learned from the reference cores

The final design was not copied from one CPU. Different cores were used to answer different questions.

### Rocket

Useful lessons:

- clean component boundaries around TLB/PTW/cache/system integration;
- parameterized software-visible architecture;
- relatively simple, timing-conscious implementation style;
- PMA/platform ownership rather than scattering address-range rules through execution logic.

What AetherCore intentionally does **not** copy:

- a broad general-purpose generator surface in which arbitrary microarchitecture combinations become product promises.

### CVA6

CVA6 is especially relevant to the AetherCore complexity/performance target.

Useful lessons:

- scoreboard/tagged in-flight work;
- independent variable-latency functional units;
- results may complete independently;
- architectural state still commits precisely and in order;
- substantial latency hiding is possible without immediately adopting a BOOM-style large PRF backend.

CVA6 is evidence that a small core can gain real parallelism while retaining relatively disciplined state ownership.

### Bergamot

Bergamot demonstrated an attractive middle ground for an educational/FPGA-oriented core:

- small ROB;
- receipt/tag-like dependency identity;
- multiple typed execution pipelines;
- limited result bandwidth;
- branch prediction and caches;
- small selective OoO rather than a massive OoO engine.

This strongly influenced the idea that AetherCore could capture short-distance ILP with a small window instead of choosing between a classic five-stage core and a scaled-down BOOM.

### BOOM

BOOM is used primarily as a correctness and mechanism reference for true OoO design:

- ROB and precise retirement;
- rename/value separation;
- age-based issue;
- branch recovery;
- physical register files;
- load/store queues;
- speculative memory ordering and replay.

AetherCore learns the hazards and seams from BOOM, but does not treat BOOM's feature set as a checklist.

### XiangShan

XiangShan is a reference for what high-performance OoO grows into when pushed much further:

- large ROB/window;
- multiple physical register files;
- many issue queues and execution ports;
- sophisticated branch recovery;
- aggressive load/store machinery;
- speculation, wakeup, replay and large memory queues.

This is valuable not only as inspiration but also as a **complexity boundary reference**. It shows which costs begin to dominate once the design chases substantially more IPC.

---

## 5. The candidate paths and why the hybrid won

### Path A — modular in-order

A clean Rocket/CVA6-like application core:

```text
Frontend
  -> Decode
  -> Issue
  -> Execute
  -> LSU
  -> Commit
```

with scoreboard, predictor, cache/TLB/PTW and explicit Commit.

Advantages:

- simplest Linux bring-up;
- easiest FPGA timing closure;
- lowest verification complexity;
- very readable.

Limitation:

- independent younger work behind long-latency instructions still loses useful cycles.

This remains a valid fallback if measurement ever shows that frontend/cache/Fmax dominate and selective scheduling gives little whole-program value.

### Path B — Bergamot-like small selective OoO

A small ROB and small scheduling window with typed execution pipelines:

```text
ROB ~ 8 (possibly larger only if measured)
small scheduling window
limited wakeup/result bandwidth
Integer / MUL-DIV / Branch can execute out of order
memory remains conservative
retirement remains ordered
```

Intentionally omitted initially:

- large PRF/free-list backend;
- large reservation stations;
- large ROB;
- full speculative OoO LSU;
- memory-dependence prediction;
- memory-violation detection/replay;
- wide common-data-bus network.

### Path C — the selected hybrid

The chosen design combines the bring-up simplicity of Path A with contracts that can mature into Path B.

The first implementation is allowed to behave almost entirely in order:

```text
fetch/decode/dispatch = 1
ROB = 4 or 8
issue = oldest-only
commit = 1
blocking memory
one outstanding request
```

but from the first implementation it already has the final ownership model:

- semantic uOps;
- stable ROB lifetime identity;
- producer/dependency identity;
- value-storage identity;
- explicit in-order Commit;
- decoupled execution request/response;
- internal memory transaction identity;
- replaceable Frontend / Scheduler / Execute / Completion / Memory seams.

The key phrase is:

> **ordered behavior, final ownership**

This avoids debugging a fully OoO Linux core on day one while also avoiding a second architecture rewrite later.

### Path D — fuller OoO only if evidence forces it

Possible future mechanisms:

- PRF and free list;
- larger ROB/window;
- larger issue queues;
- speculative loads past unknown stores;
- LDQ/STQ with violation detection and replay;
- branch checkpoints;
- wider dispatch/issue/retire.

These remain available architectural directions because v2 leaves the required seams, but they are not promises.

---

## 6. The instruction lifetime was deliberately detached from pipeline stages

v1 effectively identified work by stage location.

v2 defines three independent concepts:

```text
RobToken
  = program-order / lifetime identity

ProducerTag
  = dependency / wakeup identity

ValueRef
  = speculative value-storage identity
```

The first implementation may physically collapse them:

```text
ProducerTag -> ROB-backed identity
ValueRef    -> ROB/producer result storage
```

but interfaces must not rely on their numerical equality.

This is a deliberate future seam.

For example, if a later implementation moves speculative values to a PRF:

```text
ValueRef -> physical register
```

Issue/Commit/Execution should not require an ownership rewrite.

Stable lifetime identity also permits late responses from killed work to be rejected after branch recovery or flush.

---

## 7. Decode semantics are separated from backend implementation

The architectural instruction and backend uOp are different concepts even while the initial mapping is mostly 1:1.

```text
architectural instruction
        |
semantic decode
        |
backend uOp
        |
ROB / dependency / value identities
```

Semantic decode owns facts such as:

- PC and instruction length;
- operation and execution class;
- logical source/destination registers;
- immediate;
- memory semantics;
- branch/control-flow semantics;
- CSR/system semantics;
- ordering class;
- decoded exception metadata.

It must not own:

- pipeline stage;
- bypass mux selector;
- physical register number;
- branch mask;
- LDQ/STQ slot;
- MSHR ID;
- fixed execution port.

This boundary is analogous to the MiniC rule that language semantics should not encode a particular backend's register or pipeline decisions.

---

## 8. Multiple pipelines do not mean immediate wide issue

AetherCore's useful parallelism begins by having **independent execution pipelines**, not by immediately issuing two or four instructions per cycle.

Target shape:

```text
                    Scheduler
                        |
             +----------+----------+
             |          |          |
          Integer    MUL/DIV     Branch
             |          |          |
          short      variable     short
             |          |          |
             +------ Completion ---+
                        |
                       ROB
```

Later the LSU is another independently active pipeline.

The core may still keep:

```text
dispatch width = 1
issue width    = 1
commit width   = 1
```

while several instructions are simultaneously executing or waiting for results.

Example:

```text
cycle 0: issue DIV
cycle 1: issue independent ADD
cycle 2: issue independent MUL
cycle 3: issue another ready ADD
```

Execution overlap:

```text
DIV =================================
    ADD ->
        MUL -------->
            ADD ->
```

This captures a large part of useful long-latency hiding while avoiding the RF ports, multi-select logic, result arbitration, bypass fanout and routing cost of an immediately wide backend.

Rule:

> first exploit cheap temporal overlap; buy width only when measurements show width=1 is structurally saturated.

---

## 9. What may execute out of order

The mature selective-OoO target intentionally distinguishes classes by how expensive it is to make early execution precise.

### Highest-value first: pure computation

These are the first arbitrary-age candidates:

```text
Integer ALU
MUL
DIV/REM
```

Why they are attractive:

- early execution has no external architectural side effect;
- result can stay speculative in ROB/value storage;
- if an older instruction later traps or redirects, the younger value can simply be discarded;
- dependency wakeup is naturally represented by ProducerTag.

This is the highest-return selective OoO mechanism for AetherCore.

### Branch: valuable, but one complexity tier higher

Branch execution is also worth moving out of order because late branch resolution creates large bubbles.

However, an arbitrary-age branch may discover a misprediction while older instructions still exist:

```text
ROB:
A
B
C = branch
D
E
```

If C mispredicts:

```text
keep A/B/C
kill D/E
redirect frontend
repair speculative dependency/rename state
```

Therefore Branch becomes arbitrary-age only after recovery is generalized.

For a ROB4/8-style core, prefer:

```text
immediate frontend redirect
+ younger-only ROB invalidation
+ small sequential rebuild of speculative mapping from committed state and surviving ROB
```

rather than immediately paying for BOOM-style per-branch rename checkpoints and branch masks.

This deliberately trades a few recovery cycles for much lower always-on state and verification complexity.

---

## 10. What remains ordered even in the selective-OoO core

The architecture is deliberately **not** “everything runs whenever ready.”

The mature policy is closer to:

| Operation / boundary | Intended policy |
| --- | --- |
| Fetch | speculative/predicted |
| Decode | program order |
| Allocate / rename | program order |
| ROB allocation | program order |
| Integer issue | oldest-ready selective OoO |
| MUL/DIV issue | oldest-ready selective OoO |
| Branch issue | oldest-ready after recovery maturation |
| Completion | may be out of order |
| Store address/data calculation | may be early |
| Store external visibility | **strict Commit permission** |
| Safe younger load | may proceed under conservative dependence rules |
| MMIO/device | non-speculative, serialized |
| CSR/system | conservative/serialized initially |
| FENCE/SFENCE | ordered boundary |
| AMO/LRSC | conservative serialized path initially |
| Exception | precise architectural boundary |
| Interrupt | precise architectural boundary |
| Commit/Retire | **strict program order** |

This table is the essence of AetherCore's selective-OoO philosophy.

---

## 11. Completion may be out of order; Commit must remain in order

Execution order and completion order are allowed to differ from program order:

```text
program order:   I0 I1 I2 I3
completion:      I1 I3 I2 I0
commit:          I0 I1 I2 I3
```

Commit remains the only architectural owner of:

- integer architectural RF writes;
- CSR architectural writes;
- precise trap/xRET effects;
- exception/interrupt boundary;
- store visibility permission;
- architectural CommitTrace.

This cleanly separates the performance world from architectural correctness.

A younger ADD can execute and complete early without becoming architectural. If an older instruction later faults, the ADD disappears with no rollback of architectural RF state because it never wrote that state.

---

## 12. Small scheduling window, oldest-ready selection

The core does not need a large collapsing reservation station to gain selective OoO.

For ROB4/8, the preferred first scheduler is a small fixed live window that can answer:

- which uOps are live;
- which operands are ready;
- which execution class they need;
- which older barriers prevent issue;
- which is the oldest eligible ready candidate.

Each cycle:

```text
scan bounded window
 -> filter ready + eligible
 -> choose oldest
 -> issue at most one new uOp
```

Whether this storage is physically a separate IQ is not sacred.

Initial implementation may expose a narrow `SchedulerWindow` view of ROB/dependency state. If Fmax, fanout or larger-window growth later justifies it, scheduling storage can move into a fixed-slot IQ behind the same seam.

Therefore:

> the scheduler contract is architectural to the microarchitecture; a separate IQ is an implementation choice.

---

## 13. Result bandwidth is intentionally bounded

Full OoO cores can spend substantial area and timing budget on wide wakeup/broadcast networks.

AetherCore should not prepay that cost.

Initial policy:

```text
issue <= 1 new uOp / cycle
ROB accepts ~1 completion / cycle initially
source responses are backpressured/buffered
```

A ceiling around two accepted/broadcast results per cycle is a plausible later bound if collision counters show value.

The rule is:

> completion bandwidth grows from measured collision pressure, not from the existence of multiple functional units.

Multiple functional units are useful because they overlap in time, not because every unit must be able to finish into a wide CDB in the same cycle.

---

## 14. Why full memory OoO crosses our complexity cliff

This is the most important deliberate boundary in the design.

Pure compute OoO is relatively cheap to make precise because speculative results have no external side effects before Commit.

Memory is different.

Consider:

```text
S0: store [x1] = x2
L1: load  x3 = [x4]
```

If the older store address is not yet known, allowing L1 to run requires guessing whether the accesses alias.

If the core speculates “no alias” and later learns that they overlap, it now needs:

```text
memory-order violation detection
 -> invalidate/replay the load
 -> replay or repair dependent work
```

That naturally pulls in more machinery:

- associative LDQ/STQ state;
- store-address tracking;
- store-data tracking;
- load dependence speculation;
- memory dependence prediction;
- violation detection;
- selective replay;
- dependency descendant repair;
- more recovery interactions.

Store-to-load forwarding adds another dimension.

Full overlap is simple:

```text
older store [0x1000..0x1007]
younger load [0x1000..0x1007]
 -> forward if data ready
```

Partial overlap is not:

```text
older store 4 bytes @ 0x1002
younger load 8 bytes @ 0x1000
```

Now bytes may come from multiple older stores and cache data, requiring byte-level age selection and merge forwarding.

Memory also carries architectural hazards absent from plain ALU work:

- page/access/PMP/alignment faults;
- MMIO side effects;
- atomic reservations;
- acquire/release ordering;
- FENCE/SFENCE boundaries.

BOOM/XiangShan-class memory machinery is valuable for high-performance cores. The AetherCore conclusion is simply that **this is where the marginal complexity rises far faster than the project's expected marginal performance return**.

---

## 15. Conservative ordering does not mean blocking memory

AetherCore therefore draws a deliberate line:

> **do not speculate past unknown memory dependence, but do permit safe memory-level parallelism.**

The favored rules are:

1. a younger load never passes an older store whose address is unknown;
2. once all older store addresses are known, a non-overlapping younger load may proceed;
3. exact/full overlap may forward if the relevant store data is ready;
4. partial overlap may conservatively wait in the first implementation;
5. stores may calculate address/data early but become externally visible only after Commit permission;
6. stores drain in program order initially;
7. MMIO/device accesses are non-speculative and serialized;
8. AMO/LRSC/FENCE/SFENCE use conservative ordering first;
9. multiple cache misses/loads may still be outstanding through transaction IDs and a small MSHR set.

Example of safe memory parallelism:

```text
Load A -> D$ miss -> MSHR0 -----------------> memory
Load B -> independent D$ hit -> completion
Load C -> D$ miss -> MSHR1 -----------------> memory
```

This provides useful memory-level parallelism without guessing load/store dependence.

The crucial distinction is:

```text
conservative ordering != globally blocking memory
```

One of the highest-ROI memory optimizations may therefore be separating ordered dependence policy from a non-blocking cache.

---

## 16. Store execution and store visibility are different concepts

A store should not have to wait until retirement before doing all useful work.

Eventually:

```text
Store uOp
   |
   +-> calculate address
   +-> calculate/capture data
   +-> translation/protection
   |
 small Store Queue
   |
 wait for ROB Commit permission
   |
 become externally visible / drain in order
```

Thus:

```text
store preparation may be early
store visibility remains ordered
```

This keeps precise state while removing unnecessary latency from the retirement critical path.

---

## 17. PRF is a seam, not a first-generation requirement

A small selective window can use:

```text
committed architectural RF
+ ROB/producer-backed speculative values
+ ProducerTag wakeup
```

without immediately introducing:

```text
physical register file
free list
rename map / committed map pair
many PRF ports
checkpointed rename state
```

For ROB4/8 and single dispatch/issue, this is likely a better complexity point.

A PRF becomes worth revisiting only if measurements show pressure from:

- substantially larger ROB/window;
- 2-wide+ dispatch/retire;
- speculative value capacity;
- RF/bypass port pressure;
- value distribution timing.

`ValueRef` exists specifically so this migration remains possible later.

---

## 18. Frontend performance is separate from backend OoO

The mature frontend target is roughly:

```text
Predictor
   |
PC generation
   |
ITLB || VIPT I$
   |
FetchBlock
   |
Align / RVC
   |
FetchQueue
   |
Decode x1 / optional x2
```

Important separations:

- XLEN is not fetch width;
- fetch width is not decode width;
- decode width is not bus width;
- RVC parcel/alignment is a frontend concern, not backend instruction identity;
- predictor is replaceable behind a Frontend seam;
- branch resolution uses typed redirect/recovery rather than global kill wires.

A wider frontend is not a prerequisite for first selective OoO. First hide long latency with a one-wide backend; widen only if measurement shows supply/dispatch saturation.

---

## 19. “Buttons” and “seams” govern configuration

The long-term rule is:

> **Parameterize architectural choices; seam microarchitectural choices.**
>
> **Leave seams, not knobs.**

### Architecture-visible choices are buttons

These affect software, code generation, OS behavior or binary compatibility and therefore remain explicit:

- RV32 / RV64;
- I/M/A/C and qualified future ISA extensions;
- Zicsr/Zifencei and other qualified Z extensions;
- M-only / M+U / M+S+U profiles;
- Bare / Sv32 / Sv39 / qualified future Sv48/Sv57 modes;
- architectural PA geometry;
- PMP/timer capabilities;
- platform reset vector and system-visible memory map.

### Microarchitecture choices are seams or a few named implementation presets

Do not promise arbitrary combinations of:

- ROB depth;
- scheduler/IQ depth;
- pipeline depth;
- issue width;
- commit width;
- FU topology;
- cache geometry;
- MSHR count;
- predictor type;
- rename implementation;
- LSU speculation policy.

These may change over time or exist as a few named FPGA/PPA experiment profiles, but they are not the public software-visible architecture.

The project qualifies **named supported profiles**, not the Cartesian product of every possible switch.

---

## 20. RV32 and RV64 are one core lineage

The goal was never two copied CPUs.

Shared mechanisms include, wherever the ISA permits:

- instruction-flow ownership;
- ROB/dependency/Commit;
- ALU/branch/MUL-DIV structure;
- privilege framework;
- CSR framework;
- interrupt/trap framework;
- TLB/PTW structure;
- cache and internal memory contracts;
- SoC/bus boundaries;
- CommitTrace and verification.

True XLEN-specific facts remain parameterized or selected semantically:

- register width;
- RV64 word operations;
- shift semantics;
- CSR field width;
- address canonicalization;
- AMO width;
- RV32C/RV64C encoding differences;
- page-table format and geometry.

The desired long-lived system-level qualification lines are conceptually:

```text
L32:
RV32IMA(+qualified C) + Zicsr/Zifencei
M/S/U + Sv32
OpenSBI + Linux + userspace

L64:
RV64IMA(+qualified C) + Zicsr/Zifencei
M/S/U + Sv39
OpenSBI + Linux + userspace
```

Both come from the same architecture and implementation lineage.

---

## 21. VM modes are geometry variants, not separate MMUs

The intended model is:

```text
VirtualMemoryMode
  Bare
  Sv32
  Sv39
  Sv48
  future Sv57 when justified
       |
PageTableGeometry
  levels
  pteBytes
  vaBits
  vpnBitsPerLevel
  ppnBits
       |
shared PTW / TLB / permission / fault machinery
```

Common logic includes:

- PTE validity;
- R/W/X/U permission;
- SUM/MXR;
- A/D handling;
- superpage alignment;
- page faults;
- PTW access faults;
- TLB refill/cancellation;
- SFENCE semantics.

The architecture model may describe valid RISC-V modes before every mode is implemented, while implementation capability gates fail closed for unqualified combinations.

---

## 22. CPU configuration follows the same method as MiniC target design

A recurring design method was deliberately shared between MiniC and AetherCore:

```text
model can describe a valid target
        !=
current implementation can necessarily produce/run it
```

For the compiler:

```text
LanguageOptions / TargetInfo / DataLayout / ABI
        !=
current backend capability
```

For the CPU:

```text
IsaConfig / architectural description
        !=
current AetherCore implementation capability
```

Unsupported implementation combinations fail closed rather than being erased from the architecture vocabulary.

The primary cross-project contract is:

```text
compiler-emitted ISA subset <= CPU-implemented ISA
```

Long term, MiniC target selection and AetherCore supported profiles should be mechanically comparable at least on:

- XLEN;
- ISA extension set;
- required Z extensions;
- ABI-relevant target facts;
- environment/profile assumptions where applicable.

This does not mean the compiler imports CPU RTL configuration code. It means both sides express compatible target facts.

---

## 23. The MiniC refactoring method also shaped the CPU architecture method

AetherCore repeatedly reused a lesson learned while restructuring MiniC:

> real blockers should leave behind a concept that remains valid after the immediate test passes.

and:

> abstract only when a real frontier shows that the current seam is insufficient; do not prebuild a giant framework for hypothetical futures.

Examples in the CPU project:

- PMP/VM failures drove ownership and geometry cleanup instead of Linux-specific patches;
- stage forwarding was replaced by `Ready/Pending(ProducerTag)` rather than another bypass special case;
- Commit became the sole architecture-visible owner rather than distributing precise-state effects through execution stages;
- transaction identity was added to internal memory before multiple outstanding requests were enabled;
- `ValueRef` exists before a PRF exists;
- scheduler/issue is a seam before full OoO scheduling is enabled.

This is **frontier-driven architecture**: leave future roads open, but do not pay for unused roads in advance.

---

## 24. Verification and observability are part of the architecture of the project

AetherCore is designed to remain debuggable while it becomes less ordered internally.

Architectural truth remains `CommitTrace`:

```text
v1 ----\
        -> normalized architectural commit comparison -> NEMU / Spike
v2 ----/
```

CommitTrace must not contain scheduler-specific metadata.

Separate simulation/debug observability may record:

- RobToken;
- dispatch/issue/complete/retire cycles;
- scheduler/ROB occupancy;
- wakeup;
- branch recovery;
- cache/TLB/MSHR events;
- stall attribution;
- completion collisions.

Performance counters are used to decide whether another mechanism is worth its complexity, not to justify a feature after it has already been built.

---

## 25. The original evolution sequence and what F0-F7 proved

The architecture-migration sequence deliberately kept behavior simple while replacing ownership.

### F0 — contracts

- semantic decoded operation;
- backend-uOp boundary;
- RobToken;
- ProducerTag;
- ValueRef;
- OrderingClass;
- Execution request/response;
- internal memory transaction identity.

### F1 — ROB + explicit Commit

- small ROB;
- one allocation/completion/retire per cycle;
- ordered behavior;
- ROB-backed speculative values.

### F2 — dependency model

- `Ready(value)` / `Pending(ProducerTag)`;
- wakeup by accepted completion;
- remove stage-forwarding ownership.

### F3 — decoupled execution pipelines

- integer;
- branch;
- MUL;
- iterative DIV/REM;
- tagged request/response.

### F4 — recovery ownership

- typed branch redirect/recovery;
- initially head-specialized because issue is oldest-only.

### F5 — privileged/CSR integration

- commit-owned architectural privilege/CSR effects.

### F6 — correctness-first LSU + PMP/VM

- blocking/one-outstanding memory;
- exact store Commit permission;
- reuse qualified translation/PMP semantics.

### F7 — software parity

- real fetch/decode path;
- privilege/VM/PMP;
- interrupts/WFI;
- atomics;
- OpenSBI/Linux/PID1/userspace qualification.

F7 therefore marks the end of **architecture migration**, not the end of the mature microarchitecture.

The major result is that the ordered core now uses ownership compatible with later selective OoO.

---

## 26. Post-F7 maturation sequence

The preferred order after the architecture-closure audit is:

```text
A8 / architecture closure
  lifetime identity safety
  architectural profile matrix
  RV32C/RV64C common contract
  SchedulerWindow contract
  Completion contract
        |
P8.0 measurement
        |
P8.1 selective compute issue
  Integer + MUL/DIV oldest-ready
  issue width still 1
        |
P8.2 generalized branch recovery
  younger-only squash
  rebuild speculative state
  then Branch oldest-ready
        |
P8.3 completion overlap
  buffered/backpressured completion sources
        |
P8.4 conservative non-blocking memory
  early store address/data
  small store queue
  safe load disambiguation/forwarding
  ~2 MSHRs initially
        |
P8.5 mature frontend
  common RVC alignment/decompression
  FetchQueue
  predictor
  I$
        |
P8.6 FPGA-driven geometry search
  ROB/window sizing
  possible width=2 only if measured
  cache/predictor/MSHR geometry by IPC x Fmax x resource
```

This sequence is not a checklist that must be completed regardless of evidence. Each step must justify itself with measured stalls and whole-program benefit.

---

## 27. Performance decision rule

Before adding a major mechanism, answer:

1. What measured bottleneck does it attack?
2. How many whole-program cycles can it plausibly remove?
3. What LUT/BRAM/DSP and routing cost does it add?
4. What does it do to FPGA Fmax?
5. How much new verification state space does it create?
6. Does it introduce a new owner, or can it fit an existing seam?
7. If it becomes unnecessary later, can it be replaced locally?

A useful mental model is:

```text
value ~= whole-program IPC/Fmax improvement
         ---------------------------------
         implementation + verification complexity
```

not simply raw IPC improvement.

---

## 28. The long-term AetherCore v2 definition

The mature target is best summarized as:

> **A parameterized RV32/RV64 application-class RISC-V core, FPGA-first and single-person maintainable, using a small tagged speculative window, selective out-of-order execution for high-return classes, precise in-order Commit, and conservative but non-blocking memory ordering.**

A useful conceptual diagram is:

```text
Predictor / Frontend
        |
      Decode
        |
 in-order allocate / dependency map
        |
   +----+----------------+
   |                     |
  ROB               SchedulerWindow
program order          oldest-ready
   |                /      |       \
   |             Integer MUL/DIV Branch
   |                \      |       /
   |                 Completion
   |                     |
   +---------------------+
        |
 precise in-order Commit
        |
  architectural RF/CSR/trap
        |
 conservative LSU
  early store preparation
  safe load disambiguation
  small SQ / small MSHRs
        |
    I$ / D$ / TLB / PTW
        |
      AetherMem
        |
     bus adapter
        |
      AetherSoC
```

The architecture intentionally keeps the following statement true:

```text
execution may be out of order
completion may be out of order
memory may overlap when dependence is proven safe
architectural retirement remains in program order
externally visible stores remain Commit-controlled
```

That is the selected AetherCore balance between the v1 five-stage core and a full BOOM/XiangShan-class OoO machine.

---

## 29. What must not be forgotten in future refactors

1. Do not equate “more OoO” with automatically better AetherCore design.
2. Do not reject high-performance mechanisms as bad; judge whether they are worth their project-specific complexity cost.
3. Preserve precise in-order Commit unless the entire architecture goal is explicitly changed.
4. Compute OoO is the first high-ROI region; full speculative memory OoO is the major complexity cliff.
5. Conservative memory ordering must not accidentally remain globally blocking forever.
6. Multiple execution pipelines are valuable even at single issue.
7. Width should follow measured saturation, not precede it.
8. PRF, large IQ, large ROB and wide result networks are options behind seams, not mandatory maturity badges.
9. RV32/RV64/ISA/VM are architecture buttons; ROB/IQ/cache/predictor geometry are not public architecture buttons.
10. Qualify named supported profiles, not every configuration cross-product.
11. Reuse qualified architectural semantics; rewrite ownership only where the old owner is wrong.
12. Keep v1 and architectural CommitTrace as understandable correctness references while v2 becomes more dynamic internally.
13. Keep Linux and real software as architecture pressure, not as sources of one-off hardware special cases.
14. Every significant feature must remain explainable end to end by one maintainer.
