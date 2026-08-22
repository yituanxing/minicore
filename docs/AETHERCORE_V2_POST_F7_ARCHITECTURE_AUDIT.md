# AetherCore v2 post-F7 architecture audit

Status: design review after frozen F7 software parity.

Frozen reference head: `418a4b798410df45c0218f1a0122cdf0199211f6`.

This audit re-checks the original kickoff constitution, design notes, reuse audit, F0-F7 implementation and the intended selective-OoO destination before performance RTL begins.

## 1. Executive conclusion

The fundamental v2 target remains sound and should **not** be replaced:

> small-window selective out-of-order execution + precise in-order Commit + conservative memory ordering + FPGA-first bounded complexity.

F0-F7 successfully replaced v1 stage-based instruction-flow ownership with stable lifetime/dependency/value ownership. The project is therefore not facing another architecture rewrite. The next phase should widen deliberately narrow bring-up seams.

However, F7 exposed enough real behavior to refine the maturation plan. The most important revisions are:

1. close lifetime-generation safety before arbitrary-age issue;
2. treat RV64C as architectural-completeness work, not merely a future frontend optimization;
3. introduce a narrow `SchedulerWindow` abstraction before deciding whether a separate fixed-slot IQ is necessary;
4. make completion backpressure/bandwidth an explicit prerequisite for overlapping independent work;
5. generalize branch/recovery only before branches become arbitrary-age issue candidates;
6. decouple bus beat width from XLEN before the mature cache/non-blocking memory phase;
7. formalize a supported architectural-profile matrix rather than implying every configuration cross-product is qualified.

## 2. Original constitution vs frozen F7

### Preserved correctly

- v2 is a new instruction-flow microarchitecture; v1 remains the correctness/reference core.
- RV32/RV64 share one semantic/backend lineage.
- `DecodedInstruction` describes architectural semantics; v1 pipeline selectors terminate at the semantic bridge.
- `RobToken`, `ProducerTag`, `ValueRef`, and AetherMem transaction identity remain separate concepts.
- Tiny ROB owns lifetime/program order and validates full completion identity.
- dependency state owns `Ready(value)` / `Pending(ProducerTag)` and wakeup.
- issue policy is a separate owner.
- execution units use tagged Decoupled request/response interfaces and do not depend on pipeline stage identity.
- Commit remains the only architectural RF/CSR/trap/store-visibility owner.
- existing qualified CSR/PMP/VM/device/software semantics were reused instead of rewritten.
- core memory transactions carry identity from the beginning.
- the first implementation remains deliberately 1-wide, ROB4, oldest-only, one-outstanding/blocking memory.

### Deliberately immature but correctly seamed

- frontend is still a one-instruction correctness frontend, not the planned FetchBlock/Align/FetchQueue/predictor/cache frontend;
- branch recovery is head-specialized because F7 issue is oldest-only;
- top-level completion assumes oldest-only mutual exclusion between system/LSU/ordinary execution sources;
- memory is conservative **and globally blocking**, whereas the mature design intends conservative **non-blocking** memory;
- result values remain ROB/scoreboard-backed instead of PRF-backed;
- interrupts currently use an empty-ROB clean boundary;
- microarchitecture geometry remains fixed bring-up geometry rather than a measured implementation profile.

These are maturation targets, not evidence that the ownership rewrite failed.

## 3. Architectural parameterization audit

The governing rule remains:

> Parameterize software-visible architecture; use replaceable seams or named implementation presets for microarchitecture.

### Architecture-visible axes: parameterize and qualify

| Axis | Frozen F7 | Direction |
| --- | --- | --- |
| XLEN | RV32/RV64 semantic/backend code parameterized | keep; every new backend feature must cross-XLEN elaborate/test |
| I/M/A | parameterized through `IsaConfig` | keep |
| C | currently production-limited to RV32C and v2 paged frontend forbids C | **close RV64C gap early** with an XLEN-aware RVC frontend/decompressor |
| Zicsr/Zifencei | profile-owned | keep |
| M/S/U privilege | profile-owned | keep named supported profiles |
| VM | Sv32/RV32 and Sv39/RV64 integrated | keep; Sv48 only when a real target needs it |
| architectural PA width | independent from XLEN (PA34/PA56 already proven) | keep |
| PMP capability/count | architecture-visible but implementation currently bounded to PMP16 | keep fail-closed; add counts only when qualified |
| timer/Sstc capabilities | explicit capability flags, but Sstc currently RV32-bounded | generalize only when claiming the corresponding RV64 profile; do not block scheduling work |

### Platform axes: separate from architecture

- reset vector and memory map remain platform-owned;
- bus beat/data width must be allowed to differ from XLEN in the mature memory system;
- core semantic load/store width remains XLEN/ISA-owned while memory beat packing/splitting belongs behind the memory seam.

### Microarchitecture axes: do **not** put into `IsaConfig`

- ROB depth;
- scheduler/IQ depth;
- fetch/decode/dispatch/commit width;
- execution-unit count;
- cache geometry;
- MSHR count;
- predictor type;
- issue policy;
- LSU speculation policy.

For FPGA/PPA experiments, a later `V2ImplementationProfile` may contain a **small set of named qualified presets** (for example `Selective4`, `Selective8`) rather than an arbitrary all-combinations generator.

## 4. Lifetime identity must be strengthened before selective OoO

Frozen F7 uses ROB4 with a two-bit per-slot generation. F7 already exposed a practical lesson: numeric `RobToken` values repeat and must never be treated as unbounded historical identities.

Arbitrary-age issue increases the risk because a killed variable-latency DIV or future memory transaction may return after the slot has been reused repeatedly.

Before arbitrary-age issue is qualified, choose and test one explicit safety rule:

- widen generation/epoch substantially for the tiny window, **or**
- prove a bounded maximum late-response lifetime relative to slot reuse, **or**
- add a separate wider backend epoch for invalidated speculative work.

The recommended simple implementation is a wider generation/epoch because the FPGA cost is small at ROB4/8 and the reasoning is much clearer than relying on timing bounds.

## 5. Revised selective-OoO progression

### Step S0 — measurement and seam closure

No scheduling behavior change.

- simulation-only stall/occupancy counters;
- lifetime-generation safety;
- supported-profile matrix tests;
- define scheduler-window and completion contracts.

### Step S1 — ROB-backed selective issue, one issue/cycle

Do **not** introduce a separate IQ yet.

Expose a read-only fixed-size scheduler view containing, per live ROB lifetime:

- uOp semantic/execution class;
- RobToken/ProducerTag/ValueRef;
- operand readiness/materialized values;
- issued/completed state;
- age relative to ROB head;
- serialization eligibility.

Choose the oldest eligible ready candidate each cycle.

Initial arbitrary-age classes:

- Integer;
- MUL/DIV.

Keep head-ordered initially:

- Branch;
- Memory;
- CSR/System;
- FENCE/SFENCE;
- AMO/LR/SC;
- other serializing or externally visible work.

This proves the value of selective scheduling with minimal new state.

### Step S2 — decide whether a separate fixed-slot IQ is justified

After S1, measure:

- scheduler select timing/Fmax;
- ROB-read fanout;
- occupancy and ready-window usefulness;
- complexity of per-slot issue state.

If ROB-window scanning is clean at ROB4/8, it may remain the implementation. If timing or growth becomes poor, preserve the same scheduler contract and move scheduling storage into a small fixed-slot IQ.

A separate IQ is therefore a **replaceable implementation option**, not a mandatory milestone.

## 6. Branch and recovery maturation

Frozen F4/F7 recovery is intentionally specialized: a normal branch recovery is accepted only when the branch completion names the ROB head. That is correct only because issue is oldest-only.

Before Branch becomes an arbitrary-age issue class:

1. ROB must validate a live branch completion at any age;
2. recovery must define a cut point and invalidate only younger lifetimes;
3. RAT/producer state must be rebuilt from committed state plus surviving ROB entries;
4. frontend redirect occurs immediately;
5. late killed completions remain harmless through lifetime identity;
6. prediction metadata, when added, must remain separate from architectural `DecodedInstruction`.

For ROB4/8, prefer a small sequential recovery walk over BOOM-style per-branch rename checkpoints unless measurements prove recovery latency is dominant.

This same recovery owner should later provide a common cut-point mechanism for branch redirect, privileged flush and interrupt handling, while preserving different architectural semantics for each cause.

## 7. Completion network maturation

Frozen `TinyExecutionCluster` already arbitrates Decoupled responses between Integer/Branch/MUL/DIV. That seam is healthy.

The composition above it still assumes system, LSU and ordinary execution cannot complete simultaneously and asserts that at most one source is valid. `TinyBlockingLsu` completion is also not backpressurable.

Before execution and memory can overlap generally:

- make every completion source backpressurable or bufferable;
- add an explicit bounded completion arbiter/network;
- preserve full identity validation at the ROB before wakeup;
- initially accept one completion/cycle if profiling says that is sufficient;
- raise to two only when collision counters show material pressure.

Do not build a wide common-data-bus network preemptively.

## 8. Register/value storage

Keep the committed architectural `RegisterFile` and ROB/producer-backed speculative values through the first selective-OoO generations.

A PRF/free list is **not** required merely to issue a few independent instructions out of order. CVA6-like scoreboard experience shows useful latency hiding is possible without a BOOM-scale PRF backend.

Revisit PRF only if one of these becomes a measured limiter:

- wider dispatch/retire;
- larger ROB/window;
- too many retained speculative values;
- register-file/bypass port pressure;
- timing of value distribution.

`ValueRef` remains the migration seam if/when this happens.

## 9. Memory maturation

The mature target remains conservative non-blocking memory, not speculative BOOM/XiangShan-style memory dependence prediction.

Recommended sequence:

### M0 — keep memory head-ordered while compute selective issue matures

No LSU change.

### M1 — explicit memory-ordering/state seam

Introduce a narrow status/permission interface for:

- older stores pending;
- known/unknown store addresses;
- memory system drained;
- uncached/device transaction pending;
- fence/translation-fence completion.

Move mature ordering policy out of ad-hoc frontend serialization.

### M2 — store queue / early store address and data capture

- calculate store address/data before retirement when safe;
- never externalize before Commit permission;
- drain committed stores in program order initially;
- MMIO remains non-speculative.

### M3 — safe load overlap

A younger load may proceed only when every older store address is known and no forbidden overlap exists.

- unknown older-store address -> wait;
- known non-overlap -> proceed;
- full overlap + ready store data -> forward;
- partial overlap -> wait initially.

### M4 — non-blocking D$ / small MSHR set

Start around two MSHRs and only grow from measured miss-level parallelism.

Do not initially add:

- memory-dependence prediction;
- load speculation past unknown stores;
- violation detection/replay;
- large associative LDQ/STQ.

## 10. Bus width and memory protocol

The current v2 requires `busDataBits == XLEN` in the F6/F7 composition. This is a bring-up constraint and conflicts with the original architectural separation between integer width and transport width.

Before M3/M4/cache work, split the concepts explicitly:

- ISA/LSU operation width: derived from XLEN/instruction semantics;
- internal memory beat width: platform/memory-subsystem property;
- bus adapter width: external interconnect property.

Sub-beat extraction, masks, multi-beat assembly and widening/narrowing belong in the memory subsystem/adapter, not in architectural decode or Commit.

## 11. Frontend maturation and RV64C

The frozen v2 frontend is intentionally early-only:

`PC -> InstructionFetchAdapter -> one canonical 32-bit instruction -> semantic decode`.

The mature ownership target remains:

`predictor -> PC generation -> ITLB/I$ -> FetchBlock -> align/RVC -> FetchQueue -> decode`.

### Architectural closure first: common RVC

Generalize the current RV32-only decompressor into an XLEN-aware RVC semantic decompressor/alignment layer:

- shared RV32C/RV64C encodings;
- RV32-only C.JAL handled only at XLEN32;
- RV64-only C.ADDIW / LD/SD forms handled only at XLEN64;
- correct `instBytes=2` and canonical 32-bit instruction delivered to the existing semantic decoder;
- cross-XLEN legality tests.

After that, remove the production `C => RV32` restriction and qualify named RV64C profiles.

This should happen before claiming mature RV32/RV64 architectural symmetry, but it need not be entangled with selective-issue RTL.

### Performance frontend later

Then add, by measurement:

- FetchQueue;
- simple BTB/BHT/RAS;
- I$;
- fetch-block alignment;
- possibly 2-wide decode/dispatch.

Prediction metadata must live in frontend/backend prediction records, not in architectural semantic decode.

## 12. Interrupt/privileged maturation

F7 takes asynchronous interrupts only after the ROB reaches an empty clean boundary. This is precise and easy to verify but may waste cycles when the window is populated.

Keep it through initial selective compute issue. Measure drain cost.

If interrupt drain is material, evolve toward a commit-owned interrupt cut point:

- identify the next architectural PC at a precise retirement boundary;
- squash younger speculative work through the common recovery owner;
- enter the trap without requiring the whole ROB to naturally drain first.

Do not optimize this before counters show value.

System/CSR/fence operations remain conservative barriers until scheduler ordering semantics are explicit and tested.

## 13. Frontend/backend width and superscalar policy

Do not infer that selective OoO requires 2-wide operation.

Keep initially:

- dispatch width = 1;
- issue width = 1;
- commit width = 1.

Selective issue can hide long latency while preserving a simple one-result/one-retire datapath.

Only consider width=2 after counters show sustained structural saturation at width=1. If widened, change one boundary at a time and keep named implementation profiles rather than arbitrary width combinations.

## 14. Reference-core lessons

### Rocket

Useful lesson: clean reusable TLB/PTW/cache/ISA components and strong parameterized architectural composition. Do not copy the full generator/configuration surface into AetherCore.

### CVA6

Most relevant near-term reference. Its scoreboard stores tagged in-flight work/results, accepts out-of-order functional-unit completion and commits architectural state in order. This supports AetherCore's decision to keep a committed RF and tagged small window before considering PRF complexity.

### Bergamot

Confirms the teaching/FPGA niche for a relatively small superscalar core with ROB, branch prediction, multiple typed execution pipelines and caches. AetherCore should learn from its simple typed-pipeline organization without copying its 2-wide policy as a requirement.

### BOOM

Use as the correctness/architecture reference for true OoO mechanisms: age-ordered issue, ROB, PRF, branch recovery and LSQ. It is primarily a source of seams and hazards to understand, not a feature checklist.

### XiangShan

Use as a later reference for high-performance memory/branch/recovery mechanisms and timing problems. Its large ROB/PRFs/issue queues/LSQs and speculative wakeup/replay machinery are intentionally outside the first AetherCore mature target.

## 15. Revised phase plan

### A8 — architecture closure before aggressive optimization

- post-F7 audit frozen;
- strengthen lifetime generation/epoch safety;
- formalize supported architectural-profile matrix;
- common RV32C/RV64C decompression/alignment contract;
- define `SchedulerWindow` and completion contracts;
- no performance behavior change required for closure.

### P8.0 — baseline telemetry

- cycles/commits/IPC;
- ROB occupancy;
- head-of-line dependency stalls;
- execution/LSU/PTW/serialization/interrupt drain;
- completion-collision opportunities.

### P8.1 — selective compute issue

- one issue/cycle;
- ROB-backed `SchedulerWindow`;
- Integer + MUL/DIV arbitrary-age issue;
- Branch/Memory/System remain ordered.

### P8.2 — recovery maturation + Branch issue

- arbitrary-age branch completion/recovery;
- younger-only squash;
- small sequential RAT rebuild;
- then allow Branch oldest-ready.

### P8.3 — completion overlap

- explicit system/LSU/execution completion arbitration/backpressure;
- retain one accepted completion/cycle until measured insufficient.

### P8.4 — conservative non-blocking memory

- memory-ordering status;
- store queue;
- safe load disambiguation/forwarding;
- non-blocking D$ + ~2 MSHRs.

### P8.5 — mature frontend

- common RVC already available from A8;
- FetchQueue;
- simple predictor;
- I$;
- fetch-block/align path.

### P8.6 — measured geometry/width tuning

Compare named FPGA implementation presets, e.g. ROB4 vs ROB8 and scheduler window size, then consider 2-wide fetch/dispatch/retire only if `IPC x Fmax x resource` improves.

## 16. Revised target statement

The target should be stated as:

> **AetherCore v2 is a single-person-maintainable, FPGA-first RV32/RV64 application core with parameterized software-visible architecture, a small tagged scheduling window, selective out-of-order compute execution, precise in-order architectural Commit, conservative non-blocking memory, and a decoupled frontend/memory/SoC structure. Implementation geometry is chosen from measured named presets rather than exposed as an arbitrary generator surface.**

This is a refinement of the original target, not a replacement.