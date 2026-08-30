# FPGA Architecture Pre-Decision Gate

AetherCore architecture experiments are admitted by **opportunity first, RTL second**.

The target is not maximum feature count or maximum IPC in isolation. The target is the
best real FPGA performance per constrained resource while preserving the qualified Linux
architecture.

## 1. No production RTL before an opportunity case exists

Architecture candidates begin with observation-only counters or an offline shadow model.
Production scheduling, ROB ownership, commit, memory ordering and recovery are unchanged.

For a counter that represents cycles a candidate could at best reclaim:

```text
U = opportunity_cycles / measured_cycles
ideal_cycle_speedup = 1 / (1 - U)
```

`ideal_cycle_speedup` is deliberately optimistic: it assumes every opportunity cycle is
removed with zero secondary stalls. If this upper bound is not material, the candidate is
rejected before an implementation exists.

Counters that are capacity-pressure proxies (ROB full, LoadQ full) are not treated as exact
speedup bounds. They are admission evidence for a higher-fidelity trace/shadow model.

## 2. FPGA cost is a resource vector, not an ASIC-style gate count

Every admitted candidate gets a pre-RTL cost sheet:

- LUT / LUT-mux risk;
- FF;
- distributed RAM / LUTRAM eligibility;
- BRAM;
- DSP;
- required read/write ports;
- CAM/tag-compare count;
- crossbar/mux width and fanout;
- new combinational depth on a likely critical path;
- clock-domain / CDC effect when applicable.

Do not collapse BRAM, DSP and LUT into one fake universal unit. The limiting device resource
and routing/timing risk decide the FPGA cost.

After a concrete Xilinx target is bound, Vivado post-place resource utilization and Fmax
replace ECP5 proxy estimates as the authoritative cost model.

## 3. Hard pre-implementation rejects

Reject a candidate before production RTL when any of these is true:

1. its ideal cycle-speedup upper bound is below the project's materiality threshold;
2. its capacity-pressure counter is negligible on the qualified Linux workload;
3. the predicted implementation exceeds the board resource budget;
4. a required multiported asynchronous structure is likely to force LUT mux/CAM expansion
   without a measured opportunity large enough to justify it;
5. the candidate creates a new high-fanout / wide-select critical cone and the ideal
   performance upper bound cannot cover a conservative Fmax loss;
6. a cheaper candidate attacks the same measured stall class with a better upper-bound /
   resource profile.

A useful non-arbitrary sanity check is performance density. If even the **ideal** throughput
ratio cannot exceed the predicted limiting-resource ratio, the candidate cannot improve
performance per constrained FPGA resource and is rejected.

## 4. Current observation classes

### Exact / near-exact opportunity counters

- `dual_compute_candidate`: at least two fresh safe Compute uOps are ready in the ROB4 view.
- `dual_domain_pairable`: an ordinary Load launch and an independent Compute launch could
  coexist if only the global single-launch conflict were removed.
- `frontend_bound`: cycle cannot dispatch because the frontend did not produce work while
  the backend could accept it.

These can form optimistic cycle-speedup caps directly.

### Capacity-pressure counters

- `rob_full_dispatch_pressure`: real dispatch wants to enter while ROB4 is full.
- `rob_full_no_launchable`: the same full-ROB blocked cycle **and** the current ROB4 exposes
  no fresh launchable Branch/Compute/Store/Load work. This is the stricter ROB8 admission
  signal: extra window entries can only expose immediately useful work on cycles where the
  current bounded window has run out of launchable work.
- `rob_full_no_launchable_load_head` / `rob_full_no_launchable_store_atomic_head`: split
  that starvation by the architectural memory-head owner so a larger window is not credited
  for stalls that are fundamentally a serialized memory-response problem.
- `loadq_full_ready_load`: LoadQ2 is full while a ready ordinary Load exists in the current
  scheduling window.

These indicate whether ROB8 / LoadQ4 deserve a trace-shadow study. They are not direct
speedup predictions because a larger queue changes future overlap. In particular,
`rob_full_dispatch_pressure` alone is never sufficient evidence for ROB8; the
full-and-no-launchable intersection must also be material.

### Critical-path ownership caps

- `critical_load_head`: no retirement while the architectural head is a Load.
- `critical_store_atomic_head`: no retirement while the architectural head is Store/Atomic.

These bound the fraction of retirement-critical time that Load/Store machinery can possibly
attack. A StoreQ proposal with negligible `critical_store_atomic_head` is rejected before RTL.

### Existing causal evidence

Keep using:

- top-down frontend/backend bound;
- critical compute / branch / memory / system;
- branch resolved / recovery / squashed-uOps;
- issue idle launchable / no-launchable;
- completion collision / backpressure;
- memory resolve / permit / request / response / completion stages;
- ROB occupancy distribution.

## 5. Candidate-specific admission path

### Compute + Memory dual-domain issue
1. Read `dual_domain_pairable`.
2. Convert it to ideal cycle-speedup upper bound.
3. Pre-cost only the extra issue arbitration, completion/bypass pressure and routing.
4. Reject without RTL if the ideal bound is too small.

### ROB4 -> ROB8
1. Read ROB occupancy and `rob_full_dispatch_pressure`.
2. If pressure is material, use a trace/shadow-window model to estimate how often four
   additional entries expose runnable work.
3. Pre-cost doubled ROB/dependency state, compare fanout and age-select mux growth.
4. Only then allow RTL.

### LoadQ2 -> LoadQ4
1. Read `loadq_full_ready_load` and critical Load time.
2. First require a shared translation/PMP datapath so additional lifetime slots have low
   marginal cost.
3. Pre-cost four lightweight slots plus txn tracking; do not replicate four full LSUs.
4. Only then allow RTL.

### StoreQ
1. Read `critical_store_atomic_head` and existing memory-stage store counters.
2. Estimate the subset attributable to pre-commit address/translation/permit work.
3. Pre-cost 2/4 entries using RAM-friendly narrow metadata.
4. Do not implement if Store is not a material retirement-critical owner.

### Full 2-wide / large OoO
This is never the first response to a stall counter. It requires evidence that cheaper
single-width structures have exhausted the same bottleneck and a board resource/timing
budget that can absorb multiported PRF, rename, wakeup/select, bypass and dual-commit cost.

## 6. Area-closure rule

Performance-neutral structural cleanup remains separate from architecture experiments.
It may proceed when a duplicated/over-wide/mis-inferred FPGA structure is identified and the
architectural timing/ordering contract is unchanged.

Current examples:
- PMP NAPOT compaction: productized;
- RegisterFile mirrored LUTRAM: productized;
- 32-entry RAT -> ROB4 CAM: rejected after integrated evidence;
- duplicated Load translation/PMP datapaths: next high-confidence area candidate.

The area line stops when the remaining changes are no longer clearly performance-neutral or
when real Vivado placement/timing shows that another resource, rather than LUT area, is the
binding constraint.
