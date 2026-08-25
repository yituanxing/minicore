# AetherCore v2 P8.3 — completion bandwidth decision

Status: measurement-based completion-bandwidth decision after final architecture structural closure.

Structural base: #181 frozen exact head `d2960fba6102370d614d183e08f18f5433407ee1`.
Production RTL measurement identity: #180 frozen exact head `aecb4705f7621f919b1ee847e49f8c73941b5afc`.

#181 changes only documentation and tracked source-contract tests, so the production RTL measured at #180 is exactly the production RTL inherited by this decision branch.

## Decision

**Do not widen the current one-completion-per-cycle fabric. P8.3 completion bandwidth is a NO-GO on the current ROB4 / one-launch / one-retire architecture and Linux workload.**

The existing A8 completion architecture is retained unchanged:

- every producer owns a Decoupled response until handshake;
- System / LSU / Execute may be simultaneously pending;
- top-level completion transport uses round-robin arbitration;
- Integer / Branch / MUL / DIV response transport is also round-robin;
- a stalled response remains owned and bit-stable;
- the ROB remains the final full-lifetime validator;
- the fabric accepts at most one completion into the ROB per cycle.

P8.3 therefore does not require a new completion network. The only remaining performance question was whether the single ROB completion port is materially saturated. Exact Linux measurement says it is not.

## Exact Linux evidence

Exact production head: `aecb4705f7621f919b1ee847e49f8c73941b5afc`.

Workflow: AetherCore V2 P8 Linux Performance Gate `32828956754` — **SUCCESS**.

Workload: unchanged Linux 6.6.143 + deterministic PID1 proof, with the production performance wrapper observing the real completion-source valid/ready handshakes.

Final marker snapshot:

```text
cycles=306754737
commits=138270910
completion_collision=1450968
completion_backpressure=1450968
memory_head=109979781
lsu_busy=59542603
head_ready_not_issued=84830615
commit_idle_nonempty=165682190
rob4=113611623
```

Normalized to total measured cycles:

| Metric | Cycles | Fraction |
| --- | ---: | ---: |
| completion collision | 1,450,968 | 0.4730% |
| completion backpressure | 1,450,968 | 0.4730% |
| memory head | 109,979,781 | 35.8527% |
| LSU busy | 59,542,603 | 19.4105% |
| head ready but not issued | 84,830,615 | 27.6542% |
| commit idle with ROB non-empty | 165,682,190 | 54.0113% |
| ROB4 full | 113,611,623 | 37.0366% |

`completion_collision == completion_backpressure` is expected for the current one-port fabric: whenever two or more top-level completion sources are valid together, at least one producer must wait. The counter therefore measures real pressure rather than an inferred scheduler proxy.

## Why 0.4730% does not justify two-wide completion

The collision fraction is an **upper bound on cycles exposed to completion-port contention**, not an expected speedup from widening.

A second accepted completion in a collision cycle does not necessarily shorten the architectural critical path because:

- Commit remains one instruction per cycle and strictly in order;
- the second completion may belong to a younger instruction that was not blocking retirement;
- ROB4 limits the amount of independent work that can accumulate;
- one-launch-per-cycle limits the long-term rate at which new completion-producing work enters the backend;
- some delayed completions are already hidden behind older memory or retirement latency.

Therefore the recoverable whole-program cycle fraction must be less than or equal to 0.4730%, and in practice is expected to be materially smaller.

Widening completion would require paying always-on complexity for a small upper bound:

- a second ROB completion write/validation path or equivalent multi-write structure;
- a second dependency wakeup/update path;
- additional result-selection and fanout;
- more simultaneous state-update cases in recovery/retirement verification;
- FPGA routing/timing pressure.

That trade does not meet the project's FPGA-first bounded-complexity threshold on current evidence.

## Comparison with the real current bottleneck

The same exact Linux snapshot reports:

- memory-head residency: **35.8527%** of cycles;
- LSU busy: **19.4105%**;
- completion collision/backpressure: **0.4730%**.

Memory-head pressure is roughly 75.8x the completion-collision window, and LSU-busy pressure is roughly 41.0x it. This is consistent with the earlier P8.2 branch-exposure decision, where most actionable younger-Branch opportunity also occurred behind a memory head.

The next complexity budget therefore belongs to conservative memory concurrency, not completion width.

## Existing completion ownership remains mandatory

NO-GO for widening is **not** permission to simplify away the A8 completion fabric.

The following remain required correctness/performance seams:

1. `TinyCompletionArbiter` remains Decoupled and round-robin;
2. System / LSU / Execute simultaneous pending responses remain legal;
3. execution-unit response arbitration remains fair across Integer / Branch / MUL / DIV;
4. producers retain response state across backpressure;
5. the ROB validates complete lifetime identity before wakeup/architectural progress;
6. one accepted completion per cycle remains the current implementation geometry, not an ISA-visible parameter.

## Re-open criteria

Do not revisit wider completion because a roadmap once mentioned it. Re-measure first if a material architecture/workload change can increase collision pressure, for example:

- issue/launch width becomes greater than one;
- commit width changes;
- ROB/window grows materially;
- memory becomes non-blocking and substantially increases simultaneous LSU/compute completions;
- additional execution pipelines are added;
- a representative workload shows materially higher `completion_collision` / `completion_backpressure`;
- FPGA implementation evidence shows a second completion path is unexpectedly cheap.

If reopened, compare the new collision/backpressure distribution against this exact 0.4730% reference before changing RTL.

## Next

P8.3 is closed without production RTL change.

Proceed to P8.4 conservative non-blocking memory. The first slice is **M1 memory-ordering/status ownership**, not a cache or MSHR implementation:

- expose explicit older-store / known-address / drain / device-pending / fence-state facts;
- preserve exact Commit-only external visibility for stores and atomic writers;
- keep MMIO non-speculative and serialized;
- only after that seam is qualified add early store address/data capture, safe load overlap/forwarding, and finally a small non-blocking cache/MSHR set.
