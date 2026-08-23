# AetherCore v2 F8 — performance baseline contract

Frozen base: `418a4b798410df45c0218f1a0122cdf0199211f6` (F7 software parity).

F8 begins performance work without reopening F7 architectural ownership. The first step is measurement, not a scheduler rewrite.

## Non-negotiable invariants

The following remain unchanged unless a later, separately qualified milestone explicitly says otherwise:

- precise in-order Commit remains the sole architectural RF/CSR/trap/store-visibility owner;
- `RobToken`, `ProducerTag`, `ValueRef`, and physical memory transaction identity remain distinct;
- stores and atomic writers externalize only with exact Commit-derived head permission;
- exceptions, interrupts and xRET remain precise architectural boundaries;
- unchanged OpenSBI/Linux/RTOS workloads and existing exact-head qualification gates remain the correctness reference;
- F7 frozen head never moves.

## Baseline questions

Before changing issue policy, measure where cycles are lost:

1. frontend cannot supply/accept a new instruction;
2. ROB full / dispatch blocked;
3. live ROB head is waiting on source dependencies;
4. head is ready but its execution resource cannot accept it;
5. memory head is blocked by the one-outstanding LSU;
6. PTW / address translation is active or blocking progress;
7. system/privileged serialization blocks younger work;
8. completed work waits at Commit;
9. asynchronous interrupt/WFI clean-boundary drain time;
10. productive issue/retire cycles and achieved IPC.

Counters are diagnostic/simulation observability only. They must not affect architectural behavior or timing decisions.

## Required first measurements

At minimum report:

- cycles;
- commits;
- IPC;
- dispatch accepted / dispatch blocked;
- ROB occupancy histogram (0..4);
- issue fires by class: integer/branch, mul-div, memory, system;
- head-not-ready cycles;
- head-ready-but-not-issued cycles with an exact schedulable-head predicate;
- LSU busy cycles and memory request/response counts;
- PTW-active / translation-stall cycles where observable;
- commit-idle with non-empty ROB cycles;
- privileged/interrupt serialization cycles, including clean-boundary interrupt hold and WFI halt time.

Each stall category must have an explicit predicate and should avoid double-counting where practical. A hierarchical attribution is preferred over a misleading sum of overlapping counters.

## P8.0 exact measurement slice after selective issue

The production selective-compute slice was qualified at #151 before the first whole-program counter collection. P8.0 therefore measures both the remaining bottlenecks and the real utilization of that mechanism. The measurement implementation is a simulation-only wrapper around the unchanged production core; no counter output feeds any scheduling, completion, LSU or Commit decision.

The frozen predicates are:

- `cycles`: one sample for every simulation cycle after reset;
- `commits`: `CommitTrace.valid`;
- `dispatch_accepted`: production backend dispatch `fire`;
- `dispatch_blocked`: production backend dispatch `valid && !ready`;
- `rob0..rob4`: one histogram sample per cycle from the real ROB occupancy output;
- `selective_candidate`: production selective-compute request `valid`;
- `issue_int`: accepted selective request whose execution class is Integer;
- `issue_mul`: accepted MulDiv request with MUL/MULH/MULHSU/MULHU operation;
- `issue_div`: accepted MulDiv request with DIV/DIVU/REM/REMU operation;
- `issue_branch`: exact production Branch request `fire`;
- `issue_mem`: exact production LSU request `fire`;
- `system_completion`: System completion `fire`; this is deliberately not mislabeled as a generic FU issue because System has no independent issue-request seam;
- `selective_bypass`: accepted selective request whose exact `RobToken` differs from the current age-0 SchedulingView token;
- `bypass_compute_head` / `bypass_branch_head` / `bypass_memory_head`: classify each actual selective bypass by the blocked/current head execution class;
- `bypass_other_head`: fail-closed diagnostic bucket; production policy expects this to remain zero because System/None heads must not be bypassed;
- `head_not_ready`: age-0 SchedulingView entry is live, incomplete, exception-free and its operands are not ready;
- `head_ready_not_issued`: age-0 entry is live, incomplete, exception-free, operand-ready, belongs to Integer/MulDiv/Branch/Memory, and no accepted exact-head launch occurs that cycle;
- `compute_head` / `branch_head` / `memory_head` / `system_head`: live incomplete age-0 residency by execution class;
- `commit_idle_nonempty`: ROB occupancy is nonzero while CommitTrace is not valid;
- `interrupt_hold`: the real `TinyPagedCore.interruptHold` clean-boundary drain state;
- `wfi_halted`: the real `TinyPagedCore.halted` / WFI-waiting state;
- `lsu_busy`: real blocking LSU busy output;
- `memory_launch_blocked`: exact-head LSU request is valid but not accepted;
- `mem_req` / `mem_resp`: internal AetherMem request/response handshakes at the core/platform seam;
- `ptw_active`: platform PTW request valid;
- `completion_collision`: more than one of System/LSU/Execute completion sources is simultaneously valid;
- `completion_backpressure`: at least one valid completion source is not ready;
- `lsu_compute_overlap`: a selective-compute request is accepted while the real LSU remains busy.

`head_ready_not_issued` deliberately excludes System operations. It is a scheduler/resource-pressure predicate, not a claim that every ready live head should issue every cycle. Already-launched work waiting for completion is excluded by the exact accepted-launch and class-specific ownership rules above.

The unchanged Linux PID1 workload does not terminate after its proof. The measured shell therefore takes a one-shot machine-readable `AETHERCORE_V2_PERF` snapshot as soon as the UART phrase `RV64 USER UART IRQ OK` is complete, **before CR/LF line termination**. This makes the boundary insensitive to tty CR/LF normalization. `exitValid` remains a second one-shot boundary for terminating workloads. Counter arithmetic, marker semantics and ROB4 histogram behavior are separately frozen by focused ChiselSim regression.

## Whole-workload identity rule

A total-cycle comparison is apples-to-apples only when the **final executable workload identity** matches. Matching source revisions, Linux version, compiler version and baseline kernel Image are necessary but not sufficient; the deterministic PID1 ELF, initramfs Image and final OpenSBI `fw_payload.bin` hashes must also match.

P8 evidence therefore records those final hashes with every measured Linux run. If they differ, cycle/commit deltas may be reported as diagnostic observations but must not be attributed to a microarchitectural change. Detailed causal counters from a single exact workload remain valid for bottleneck attribution.

This rule exists because the first #152 measurement exposed an otherwise invisible reproducibility gap: the frozen F7 and P8 runs used identical `minimal_init.S`, build script and baseline Linux Image, yet their final minimal-initramfs payload hashes differed. That gap must be resolved before treating the frozen F7 total-cycle number as a strict performance baseline.

## First expected optimization

If measurement confirms head-of-line dependency blocking is material, the first bounded microarchitectural change is **oldest-ready single issue** over the existing small live window, not wide issue.

That mechanism is now present in #151 for exception-free Integer and Mul/Div. The next mechanism must therefore be selected from measured post-#151 pressure rather than mechanically following this historical expectation.

The safety policy remains:

- preserve single issue per cycle;
- preserve in-order Commit;
- allow younger execution only for classes proven side-effect-free before Commit;
- keep stores, atomics, CSR/system operations, fences and other externally visible/serializing operations conservative/head-ordered initially;
- branch/recovery semantics must retain exact lifetime ownership;
- no PRF/free-list, LDQ/STQ, speculative load replay, caches/MSHRs, multi-issue or multi-retire without separate measured justification.

The goal is to isolate the value of each bounded mechanism before adding larger structures.

## Evaluation sequence

1. freeze baseline counters and counter-contract tests;
2. collect focused microbenchmark and real OpenSBI/Linux samples;
3. verify final workload hashes before whole-program baseline comparison;
4. identify dominant stalls by percentage of cycles and causal overlap;
5. implement one bounded mechanism;
6. compare cycles/commits/stall distribution against an exact-identity baseline;
7. run all correctness gates on the candidate exact head;
8. keep the mechanism only if measured benefit justifies its complexity.

## Diagnostic acceleration

The separate checkpoint/forkserver path may be used for repeated Linux measurements and short experiments. It is diagnostic acceleration only; final correctness qualification remains a cold boot from reset through unchanged software.
