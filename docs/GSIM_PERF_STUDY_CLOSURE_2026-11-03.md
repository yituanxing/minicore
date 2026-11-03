# PA32 GSIM performance study — closed (2026-11-03)

**Disposition: STOP optimization; no promotion of ThinLTO, PGO or SuperNode settings.** Original qualified GSIM default stays unchanged. This is not a rollback of already qualified correctness improvements, caching or progress logging.

## Exact evidence

- Full Linux PID1, GSIM vs Verilator 5.054, run [41578321826](https://github.com/yituanxing/minicore/actions/runs/41578321826), commit `39b1f628`: 428,327,594 guest cycles, 135,600,052 committed instructions, STIP 4,277 / SEIP 1, UART and host RAM witnesses matched. GSIM 759.183 sec, Verilator 1083.296 sec: **1.42692x** within that run.
- SuperNode 15/24/35/48 and PGO, strict 20M OpenSBI/Linux-prefix checks, runs [41590419350](https://github.com/yituanxing/minicore/actions/runs/41590419350) and [41591394599](https://github.com/yituanxing/minicore/actions/runs/41591394599): all candidates passed. SuperNode 15 was fastest or approximately tied with 35, but gain was small and host-sensitive. PGO showed no consistent speedup. These results **do not** qualify nondefault candidates for full PID1.
- Sparse host phase attribution, run [41599349425](https://github.com/yituanxing/minicore/actions/runs/41599349425): 4,886 samples in 20M cycles; `request=5.06%`, `model=92.52%`, `observe=2.42%`. The model phase includes same-tick host callbacks and cannot be interpreted as pure generated-RTL cost.
- Final Clang19 ThinLTO trial, run [41600661713](https://github.com/yituanxing/minicore/actions/runs/41600661713): strict 20M PASS on all four alternating A-B-B-A trials; baseline median **35.102942 s**, ThinLTO median **33.804066 s**, **1.03842x** (3.84% faster): below the predetermined >=5% threshold, `NO_PROMOTION`.
- Exact latest rerun [41600823611](https://github.com/yituanxing/minicore/actions/runs/41600823611), head `8fec6670`: contracts and native jobs both SUCCESS, strict 20M PASS in all four trials. Baseline median **32.463459 s**, ThinLTO median **33.470619 s**, **0.96991x** (3.01% slower): `NO_PROMOTION`.

The differing host-scale results prove that absolute seconds and different-host GSIM/Verilator speedups must not be intermixed. Equal-firmware, same-host alternating measurements are the only defensible basis for this small optimization decision.

## Closing controls

- Keep default GSIM configuration and correctness oracles unchanged; **do not enable ThinLTO/PGO, change SuperNode default, or reduce Linux checks**.
- The costly wide SuperNode/PGO workflow is manual-dispatch only.
- The ThinLTO A-B-B-A trial is now opt-in `workflow_dispatch(thinlto_ab=true)`; ordinary PR updates retain contract, real-memory and firmware strict gates without repeating a research-only compiler experiment.
- Do not claim portable RTL snapshots or QEMU/NEMU architectural checkpoint restore as implemented. Existing host-fork precise snapshots and proposed cross-engine fast-forward remain separate.
- PR #378 is still **draft** and covers simulation caches and other changes besides these optimizations. This closure is a performance research decision, **not** authorization to merge an unreviewed 70+-file draft PR. Review source scope, pending checks and the previously noted firmware hash reproducibility issue before promotion.

## Engineering handoff

AetherSoC v0 board-neutral Linux PID1 is qualified. Move effort to the project-level RV64GC/LP64D FPGA Linux computer and to real microarchitecture contracts. See the separate product architecture audit, which must distinguish the frozen v0/v2 implementation from the more ambitious new two-decode/two-retire OoO target.

**Do not reopen simulator optimization** absent a change in host, RTL model, user workload or a measured slowdown that materially blocks development. An explicitly scoped checkpoint/fast-forward capability may be considered later on its own merits; it is not a prerequisite to the CPU/SoC hardware work.

## Archived historical CI probes

After the closure push, two superseded automated PR workflows remained red:

- `rv64-pa32-gsim-feasibility.yml`: unpatched pinned GSIM frontend reaches a historical `splitNodes.cpp:658 dupTreeWithBits` assertion, exit 134;
- `rv64-pa32-gsim-source-patch.yml`: the old single-step/no host-callback bare RV64I probe retires no valid program, `matched_rv64i=0`, exit 25.

Those workflows are now **workflow_dispatch only**, preserved for historical reproduction. They are **not** the qualified GSIM-vs-Verilator path and must not be used as Linux pass gates. The authoritative `rv64-pa32-gsim-host-bridge.yml` retains source-contract tests, bare RV64I and memory/atomics differential, real 20M firmware strict differential, and optional manual full Linux PID1 signoff. We are not silencing failures in that qualified path.
