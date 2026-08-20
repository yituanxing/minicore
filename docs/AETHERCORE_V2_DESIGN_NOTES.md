# AetherCore v2 architecture design notes

> Status: working design notes, not a frozen specification.
>
> This document intentionally preserves multiple candidate directions. The goal is to keep the design history and the reasoning behind each option so later profiling, FPGA results, Linux workloads, or new reference designs can change the final choice without losing earlier ideas.
>
> Last major update: 2026-08-20.

## 1. Why this document exists

AetherCore v1 has become more valuable as a correctness/reference core than as the place to force the next microarchitecture into existence. The v2 effort should therefore be treated as a new microarchitecture generation, while reusing verified architectural semantics, MMU/PMP machinery, SoC infrastructure, software images and qualification flows where ownership is already clean.

The main design goal is not to maximize benchmark score or feature count. It is to find a strong Pareto point for a single-person, teaching-oriented, FPGA-first RV32/RV64 application-class core that can run real Linux and remain understandable end to end.

The design must stay open to change. In particular, v2 is not required to be permanently in-order or permanently out-of-order. The architecture should permit a simple bring-up configuration and progressively enable more aggressive scheduling only when measurements justify the complexity.

## 2. Design constitution

AetherCore v2 should be a parameterized RV32/RV64 application-class RISC-V core with clear Frontend / Backend / Memory ownership, in-order architectural retirement, decoupled execution interfaces, and a microarchitecture that can scale from a simple ordered configuration to a small-window selectively out-of-order configuration.

The following principles are currently considered high confidence:

- RV32 and RV64 remain one parameterized architecture, not separate cores.
- Pipeline depth is an implementation detail, not the architecture. Avoid making `IfId`, `IdEx`, `ExMem`, `MemWb` style stage names the public module contracts.
- Decode should produce a stable internal uOp representation rather than pipeline-specific control bundles.
- Architectural effects happen at Commit/Retire: integer register writes, CSR writes, traps, interrupts and store visibility are commit-owned.
- Functional units use decoupled request/response interfaces so latency can change without redesigning the pipeline.
- The CPU core must not directly own AXI/UART/DDR details. Core, memory subsystem and SoC remain separate layers.
- Performance features must justify themselves with `IPC × Fmax`, resource cost and real workload measurements, not by feature checklist.
- A simple v1 reference core should remain alive alongside v2 and continue to run the same architectural qualification suites.

## 3. Current v1 role

The current core is a classic in-order pipeline with explicit IF/ID/EX/MEM/WB-style state and top-level ownership of forwarding, load-use hazards, memory stalls, branch redirects, CSR hazards, VM adapters and many trap/interrupt boundaries.

That design has been extremely useful for architectural correctness and real-software qualification. It should be preserved as:

- a readable reference implementation;
- a differential oracle for v2 commit traces;
- a vehicle for architectural feature qualification;
- a fallback core for SoC bring-up.

The next core should not be created by copying `AetherCore.scala` and gradually inserting rename/ROB/issue logic into the old stage ownership. That path would retain the old pipeline assumptions and create mixed ownership.

Preferred rule: **rewrite the instruction-flow microarchitecture, reuse verified machine-level components on demand.**

## 4. Candidate architecture paths

### 4.1 Path A: simple modular in-order core

A clean application core with:

- Frontend / Decode / Issue / Execute / LSU / Commit boundaries;
- single issue, single retire;
- scoreboard for variable-latency units;
- branch prediction;
- I/D caches, TLBs and shared PTW;
- store buffer;
- explicit commit.

This path is closest to the design philosophy learned from CVA6/Rocket: strong ownership, predictable FPGA timing, low verification complexity.

Advantages:

- easiest Linux bring-up;
- easiest timing closure;
- very readable;
- good reference point for later measurements.

Limit:

- long-latency instructions and independent work behind them can still underutilize execution resources.

This remains a valid final architecture if measurements show memory hierarchy, predictor and Fmax dominate performance.

### 4.2 Path B: Bergamot-like small selective OoO

Small-window design inspired by Bergamot rather than a scaled-down BOOM:

- 2-wide-capable frontend;
- small ROB, initially around 8 entries, possibly 16 later;
- tag/receipt-style dependency tracking;
- small integer/branch/MUL-DIV issue queues;
- limited result broadcast, preferably no more than two results per cycle;
- ALU/branch/MUL-DIV may issue out of order;
- memory remains conservative;
- retirement remains in order, potentially 2-wide.

Intentionally omitted at first:

- large physical-register-file backend;
- large ROB;
- large reservation stations;
- full speculative OoO LSU;
- memory-dependence predictor;
- memory violation detection/replay;
- wide result broadcast network.

This is attractive because it captures useful instruction-level parallelism while avoiding the most difficult memory-ordering machinery.

### 4.3 Path C: current preferred hybrid

Current preferred direction is not simply Path A or Path B. It is a microarchitecture whose contracts are compatible with Path B, but whose first implementation can behave like Path A.

Bring-up configuration may be:

```text
fetchWidth    = 1
decodeWidth   = 1
dispatchWidth = 1
commitWidth   = 1
ROB           = 4 or 8
IssuePolicy   = oldest-only
D$            = blocking
MSHR          = 0
```

The architecture still contains:

- uOps;
- ROB identity;
- producer tags;
- explicit Commit ownership;
- decoupled functional units;
- Frontend/Backend/Memory interfaces.

Then progressively enable:

1. variable-latency completion;
2. small ready-select integer scheduling;
3. ROB 8/16 only if useful;
4. 2-wide frontend/dispatch/retire;
5. non-blocking D$ with 2 MSHRs;
6. more aggressive prediction only if measured.

This avoids debugging a fully OoO Linux core on day one while also avoiding a later architecture rewrite.

### 4.4 Path D: fuller OoO only if profiling proves it

Future-only path, not part of the initial v2 commitment:

- physical register file and free list;
- larger ROB;
- larger issue queues;
- speculative loads past unknown stores;
- LDQ/STQ with violation detection and replay;
- branch checkpoints / more aggressive recovery;
- wider issue/retire.

This path should only be entered when a mature v2 profile demonstrates that the simpler mechanisms are leaving substantial whole-program performance on the table.

## 5. Stable identities: RobToken, ProducerTag and ValueRef

A previous idea was to use the ROB slot directly as the producer receipt/tag, similar to Bergamot. This remains a useful first implementation, but the architecture should not equate three different concepts:

```text
RobToken    = instruction ordering identity
ProducerTag = dependency/wakeup identity
ValueRef    = where the value is stored
```

Initial implementation may use:

```text
ProducerTag = RobToken
ValueRef    = ROB result field
```

but downstream modules should not rely on numerical equality between them.

Reason: mature designs show that dependency identity and physical value storage can evolve independently. A future design may keep ROB-ID wakeup while moving values into a physical register file, or use another mapping strategy without rewriting Issue/Wakeup/Execution interfaces.

A `RobToken` should likely include a generation/epoch component, not just a circular index, so stale late responses can be rejected after flush/recovery.

## 6. Internal uOp boundary

Decode should produce a stable `DecodedUop` analogous to a compiler IR. Suggested semantic fields:

```text
DecodedUop
  pc / instruction / instruction length
  opClass / operation
  logical src/dst registers
  immediate
  memory operation
  branch metadata
  CSR/system operation
  exception metadata
  ordering class
  architectural attributes
```

Do not pre-fill it with future-only fields such as PRF IDs, branch masks, LDQ/STQ IDs, etc. Those belong to later microarchitectural records if/when they exist.

The decoder may reuse existing ISA tables and semantics, but the v1 pipeline-specific decode output should not become the v2 contract.

## 7. Frontend ownership

Preferred Frontend structure:

```text
Fast Predictor (small BTB/BHT/RAS)
          |
          v
       PC generation
          |
       ITLB || VIPT I$
          |
       FetchBlock
          |
      Align / RVC decompress
          |
       FetchQueue
          |
      Decode x1/x2
```

Important rules:

- fetch data width, decode width, bus width and XLEN are independent parameters;
- avoid embedding RVC parcel mechanics into the architectural fetch contract;
- predictor should be replaceable without changing Backend;
- leave room for a fast next-line predictor and an optional more accurate backing predictor later;
- branch resolution returns through a typed resolution/redirect channel rather than global `fetchKill` wires.

## 8. Branch recovery options

### Simple retirement recovery

Bergamot-style recovery at retirement is easy to understand but can produce large mispredict penalties.

### Preferred small-window recovery candidate

For ROB 8/16, avoid heavy BOOM-style per-branch rename checkpoints initially.

Possible flow:

```text
Branch FU detects mispredict
        |
        +--> redirect Frontend immediately
        |
        +--> invalidate younger ROB entries
        +--> rebuild speculative rename state from committed map + surviving ROB entries
```

Frontend can fetch the correct path while Backend rebuilds rename state. New instructions may wait in FetchQueue until rename restarts.

Use `RobToken` generation/validity to drop late results from killed instructions. Slow functional units do not necessarily need a kill path in the first version; stale responses can complete and be discarded.

This trades a few recovery cycles for much lower checkpoint/branch-mask complexity.

## 9. Issue queue and wakeup

Do not automatically copy BOOM's collapsing issue queues. For a small FPGA-oriented window, a fixed-slot queue is attractive:

```text
Integer IQ ~= 4-8 entries initially

entry:
  valid
  RobToken / age
  src1 ready + tag/value
  src2 ready + tag/value
  uOp
  destination
```

Every cycle, scan ready entries and choose the oldest ready candidate for the available execution port(s).

For a small queue this keeps behavior understandable and avoids large shifting/compaction networks. The selection policy should be replaceable later.

Wakeup should operate on a stable `ProducerTag` abstraction. Result bandwidth should be deliberately limited; <=2 result broadcasts/cycle is a reasonable initial ceiling.

## 10. Execution units

Execution units themselves should not know whether the core is in-order or OoO.

Use an execution wrapper:

```text
request:
  operation
  operands
  RobToken
  ProducerTag
  metadata

       -> plain ALU / MUL / DIV / FPU ->

response:
  RobToken
  ProducerTag
  result
  exception/status
```

This makes v1-tested ALU/MUL/DIV implementations candidates for reuse as low-level components while keeping scheduling/recovery in the v2 Backend.

## 11. Memory architecture: conservative non-blocking LSU

The current preferred memory path intentionally stops short of a full speculative OoO LSU.

### Core idea

Do not require memory operations to be fully blocking just because they remain conservative in ordering.

A candidate flow:

```text
Memory uOp
   -> AGU
   -> translation/PMA
   -> older-store disambiguation
       unknown older store address -> wait
       overlap + data ready         -> forward
       overlap + data not ready     -> wait
       no overlap                   -> D$
   -> non-blocking D$
   -> 2-4 outstanding loads / small MSHR set
   -> responses may return out of order
   -> ROB retires in order
```

This provides useful memory-level parallelism without speculative dependency guessing.

### Rules currently favored

1. Address generation can remain ordered in the first implementation.
2. Multiple loads may be outstanding.
3. A younger load may not pass an older store whose address is unknown.
4. If all older store addresses are known and do not overlap, the load may access D$.
5. Exact/full store-to-load overlap may forward when store data is ready.
6. Partial overlap may conservatively wait instead of implementing byte-merge forwarding initially.
7. Stores do not become architecturally visible until commit.
8. Stores drain in program order initially.
9. MMIO/device accesses are non-speculative and serialized.
10. AMO/LRSC/FENCE/SFENCE can be conservatively serialized in the first version.

This intentionally avoids:

- load speculation past unknown stores;
- memory dependence prediction;
- memory-order violation detection;
- load replay;
- large LDQ/STQ associative machinery.

### Why this is interesting

The largest low-complexity gain may come from separating `ordered issue` from `blocking cache`. A load miss can allocate an MSHR while later independent loads continue to probe the cache. This yields MLP without requiring full speculative LSU behavior.

## 12. Cache / translation path

A prior conceptual drawing placed DTLB strictly before D$. That should not be treated as the final performance pipeline.

For L1 latency, consider VIPT-style parallel work:

```text
Virtual address
   |             
   |              -> D$ index/data read
   -> DTLB
        |
     physical page
        |
        +-> physical tag compare
```

A 4 KiB page has 12 page-offset bits. A configuration such as 16 KiB, 4-way, 64-byte lines gives 64 sets, so 6 index bits + 6 line-offset bits fit exactly inside the 12-bit page offset. This is one reason such a first L1 geometry is attractive.

Candidate initial L1 direction:

- 16 KiB I$;
- 16 KiB D$;
- 4-way;
- 64 B cache line;
- blocking cache for bring-up;
- non-blocking D$ with ~2 MSHRs as a later measured upgrade.

Final values remain open until FPGA BRAM/timing work.

## 13. PMA / memory attributes

Do not scatter address-range checks for MMIO and cacheability throughout LSU code.

Introduce an explicit memory-attribute abstraction, informed by Rocket-style PMA ownership:

```text
MemoryAttributes
  cacheable
  idempotent
  sideEffecting
  ordered
  executable
  supportsAtomic
  supportsPartial
  ...
```

Examples:

```text
DRAM   -> cacheable, normal, multiple outstanding allowed
ROM    -> read-only/executable
MMIO   -> side-effecting, uncached, serialized
```

This lets SoC memory-map changes avoid changing core ordering logic.

## 14. Transaction identity and internal memory link

The memory interface should support transaction identity from the beginning even if the first configuration has only one outstanding request.

Candidate abstraction:

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

With one outstanding request, `txnId` may always be zero. With multiple MSHRs, responses can be routed correctly without changing the interface.

Core/Cache/PTW/AXI adapters should not require direct knowledge of each other's internal state.

## 15. Memory draining / fences

Commit should not know about the exact number of MSHRs, store-buffer entries or AXI transactions.

Expose a narrow memory-ordering status, for example:

```text
MemoryOrderingStatus
  drained
  storesPending
  uncachedPending
```

Then FENCE/system instructions can ask whether the memory subsystem is drained without depending on its implementation.

## 16. Serializing/system operations

Avoid scattered special-case logic for every CSR or privileged instruction.

Give uOps an ordering/serialization class, for example:

```text
Normal
SerializeBefore
SerializeAfter
SerializeBoth
MemoryFence
TranslationFence
DeviceOrdered
```

Examples:

- normal ADD -> `Normal`;
- SATP write -> serializing/context-changing;
- SFENCE.VMA -> `TranslationFence`;
- MMIO -> `DeviceOrdered`;
- AMO first implementation -> memory-serializing.

This allows new ISA extensions to use shared scheduling/commit rules rather than editing several pipeline stages.

## 17. Core / memory / SoC split

Long-term conceptual ownership:

```text
AetherCore v1 / v2 / future v3
        |
        | typed instruction/data memory contracts
        v
Memory subsystem
  ITLB / DTLB
  I$ / D$
  shared PTW
  MSHRs / ordering
        |
        v
AetherMemLink
        |
Bus adapter (AXI first)
        |
AetherSoC
  RAM / ROM
  UART
  timer / interrupts
  storage / network / display / DMA later
```

Core pipeline logic must not directly speak AXI.

## 18. Observability architecture

Observability should be designed early, but must not become a timing burden.

### Hardware PMU

Keep small:

- cycle;
- instret;
- a few programmable HPM counters.

Modules emit local registered event pulses; PMU observes them and never feeds control back into the core.

### Simulation-only rich trace

The simulator can collect far more information at zero FPGA cost:

```text
RobToken
PC/uOp
fetch/dispatch/issue/complete/retire cycle
branch recovery
cache/TLB events
queue occupancy
stall attribution
MSHR occupancy
store-forwarding events
```

A Konata-like timeline or machine-readable trace would be especially valuable for OoO debugging.

### Performance analysis rules

Always compare at least:

```text
cycles
instret
CPI / IPC
Fmax
IPC * Fmax
resource use
```

Normalize cache/TLB/branch events with MPKI where useful. Queue sizing should use occupancy/full-cycle measurements rather than intuition.

For bottleneck analysis, keep event counts distinct from mutually exclusive cycle-attribution counters. Bottlenecks will migrate after optimization, so every major optimization must be followed by a fresh profile.

## 19. Performance/complexity decision rule

AetherCore v2 should treat each performance mechanism as an ROI decision.

General heuristic:

- large share of total cycles + low/medium complexity -> strong candidate;
- small share of cycles + high complexity -> avoid;
- architecture-enabling abstraction with low runtime cost -> may be worth doing even before direct speedup;
- any IPC gain must be discounted by its Fmax/resource impact on FPGA.

Likely high-ROI areas:

- I$/D$;
- TLB/MMU correctness and latency;
- simple branch predictor;
- good pipeline partitioning/Fmax;
- store buffering;
- small non-blocking D$;
- conservative known-store disambiguation;
- small-window integer OoO if profile shows independent work waiting behind long-latency operations.

Likely lower-priority areas until proven:

- load speculation past unknown stores;
- memory replay;
- large PRF/ROB;
- large/wide broadcast networks;
- multi-port aggressive LSU;
- advanced predictors beyond measured need.

## 20. Rough FPGA performance hypothesis

These are working estimates, not commitments. They are intentionally recorded so later measurements can prove them wrong.

For an RV64 v2 with small OoO, L1 caches, MMU, simple predictor and conservative non-blocking LSU on a 7-series-class FPGA:

- early functional implementation: roughly 70-100 MHz possible;
- normally optimized design: roughly 100-140 MHz;
- good timing partitioning: roughly 130-160 MHz is an aspirational range;
- integer workload IPC: roughly 0.8-1.2 depending on workload;
- CoreMark efficiency hypothesis: roughly 3.0-3.8 CoreMark/MHz for a mature configuration.

A useful success point would be approximately 120-145 MHz RV64 with clearly better per-MHz performance than the v1 simple pipeline while remaining small enough for one person to understand and maintain.

These estimates must eventually be replaced by a configuration matrix reporting Fmax, IPC, CoreMark/MHz, LUT/FF/BRAM and real Linux workload behavior for each feature increment.

## 21. Reference designs and what to revisit

These projects should be revisited repeatedly rather than treated as one-time sources.

### Bergamot

Learn:

- small ROB/receipt-style identity;
- small OoO queues;
- limited result broadcast;
- in-order memory as a deliberate simplification;
- simple Linux-capable educational OoO structure.

Do not assume its exact data structures are the final AetherCore answer.

### Rocket Chip

Learn:

- ownership boundaries among core/TLB/PTW/cache/system;
- PMA/PMP integration;
- replaceable blocking/non-blocking cache ideas;
- mature privilege/system behavior.

Avoid importing Diplomacy/TileLink framework complexity merely for architectural similarity.

### BOOM

Use primarily as a correctness/audit reference for:

- rename;
- ROB;
- issue/wakeup;
- functional-unit wrappers;
- precise exceptions;
- branch recovery;
- LDQ/STQ and speculative-memory problems.

Do not treat BOOM as the v2 feature checklist.

### CVA6

Learn:

- application-core module boundaries;
- scoreboard / variable-latency completion;
- FPGA/ASIC-oriented pipeline organization;
- memory/system ownership.

### NaxRiscv

Especially important for FPGA-first OoO lessons:

- small/wide tradeoffs;
- ROB-ID wakeup vs value storage;
- fixed issue queue design;
- timing-aware frontend/backend structures;
- non-blocking memory and realistic FPGA PPA data;
- rich simulation visualization.

Avoid copying a plugin framework that makes the educational top-level hard to follow.

### BlackParrot

Learn:

- Frontend / Backend / Memory End separation;
- narrow latency-insensitive interfaces;
- transaction/credit-style memory ownership;
- critical-word-first ideas.

Do not import coherence/BedRock complexity until a multi-core/system goal requires it.

### XiangShan

Use as a high-end audit/reference for:

- TopDown performance methodology;
- where very wide OoO becomes complex;
- modern frontend/backend/LSU partitioning.

Its scale is not the v2 implementation target.

## 22. Open questions intentionally left unresolved

The following should remain open until implementation/profile evidence exists:

- ROB depth 8 vs 16;
- whether first mature v2 is 1-wide or 2-wide dispatch;
- one integer pipe vs two;
- exact branch predictor composition;
- whether values remain in ROB or eventually move to PRF;
- exact cache sizes/associativity after FPGA BRAM/timing measurements;
- MSHR count 0 -> 2 -> 4;
- whether load-load reordering needs additional checks;
- whether safe load-over-known-store bypass is sufficient;
- whether speculative load past unknown store is ever worthwhile;
- whether an L2 cache is useful on target FPGA/DDR configurations;
- whether dual retire earns its timing/logic cost;
- when critical-word-first refill becomes worthwhile.

No one of these should become architectural dogma before measurement.

## 23. Proposed evolution sequence

A possible implementation sequence, still subject to change:

### v2 skeleton

- new `AetherCoreV2` top-level;
- Frontend / Backend / Memory interfaces;
- `DecodedUop`;
- `RobToken`, `ProducerTag`, `ValueRef` abstractions;
- ROB 4/8;
- one-wide ordered issue;
- explicit Commit;
- blocking memory;
- DiffTest/commit comparison against v1.

### v2 Linux machine

- reuse/adapterize PMP, TLB/PTW and privileged state;
- I$/D$;
- simple predictor;
- store queue;
- OpenSBI/Linux/BusyBox qualification.

### v2 selective OoO

- variable-latency FUs;
- fixed-slot ready-select IQ;
- OoO integer/MUL-DIV completion;
- <=2 result network;
- small-window branch recovery.

### v2 width

- 2-wide fetch/decode/dispatch/retire where measurements justify it;
- same-cycle dependency and precise exception validation.

### v2 memory parallelism

- transaction IDs;
- 2 MSHRs;
- multiple outstanding loads;
- known-store disambiguation;
- simple full-overlap store-to-load forwarding;
- serialized device/atomic/fence path.

### future only if measured

- larger ROB/IQ;
- more MSHRs;
- backing predictor improvements;
- PRF migration;
- speculative LSU/replay;
- wider issue.

## 24. Design review rule

Before adding a major feature, answer four questions:

1. Which measured stall/resource bottleneck does it address?
2. What whole-program speedup can Amdahl-style reasoning plausibly allow?
3. What is the expected impact on FPGA Fmax/LUT/FF/BRAM?
4. Can the same result be obtained with a simpler mechanism or a cleaner abstraction boundary?

The design should remain willing to delete features whose `IPC gain × Fmax` result is negative.

## 25. Historical note

This document deliberately preserves several paths because the expected process is iterative:

```text
study mature cores
  -> form a small design hypothesis
  -> implement the cleanest measurable slice
  -> run DiffTest + real software
  -> synthesize / profile
  -> revisit Rocket/BOOM/CVA6/Bergamot/Nax/BlackParrot/XiangShan
  -> revise the hypothesis
```

The intended outcome is not a miniature copy of any one mature core. It is an AetherCore architecture whose complexity budget is explicit and whose choices are supported by correctness evidence, real Linux workloads and FPGA measurements.
