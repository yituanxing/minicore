# minicore historical PR and branch cleanup — 2026-11-04

## Verified final state

- Repository: `yituanxing/minicore`
- Open PRs: **123 → 14** in this cleanup wave (**109** closed, all **unmerged**)
- Remote branches: **262 → 202** (**60** SHA-lease deletions)
- Exact SHA archive tags created/verified for this wave: **165 / 165** (86 + 23 + 34 + 22)
- Historical GitHub Actions indexed: **4,373** (2,591 + 652 + 867 + 263)
- Pinned main: `7ff35c6acdc3068754292bba814e4ec52ba4ec50`
- Pinned Product: `087a3ed9588c8fe4ff99b682957f3adbe70230aa`
- Remaining active PR IDs: **#379, #378, #73–69, #49, #45–40**; all 18 distinct head/base branch names exist
- No lost selected SHA: **165/165 archive refs exact**, selected 60 deleted branch refs absent, 105 selected retained HEAD refs exact
- No RTL/Linux functional tests were run as part of repository housekeeping; preserved Actions verdicts are historical, not fresh qualification.

## Evidence and completed Actions

| Stage | Result | Actions |
|---|---|---|
| Archive 86 historic PR135 heads | 86 exact SHA tags | [41851058032](https://github.com/yituanxing/minicore/actions/runs/41851058032) |
| Close the 86-PR historical stack | 86 closed unmerged, source branches initially kept | [41851893462](https://github.com/yituanxing/minicore/actions/runs/41851893462) |
| CAS-delete six non-frozen redundant experiments | 6 deleted, 80 other legacy-head refs retained | [41853703069](https://github.com/yituanxing/minicore/actions/runs/41853703069) |
| Archive 23 other dormant open PRs | 23 exact SHA tags and 652 CI runs indexed | [41854053390](https://github.com/yituanxing/minicore/actions/runs/41854053390) |
| Close those 23 old PRs | 23 closed unmerged, **23 live branches retained** because unique code | [41854485166](https://github.com/yituanxing/minicore/actions/runs/41854485166) |
| Archive 34 historical area A/B experiments | 34 exact SHA tags and 867 CI runs indexed | [41855539159](https://github.com/yituanxing/minicore/actions/runs/41855539159) |
| CAS-delete 33 area A/B refs | 33 deleted; PA32 Stage1 and four qualification refs retained | [41856105618](https://github.com/yituanxing/minicore/actions/runs/41856105618) |
| Archive 22 historic performance/measurement experiments | 22 exact SHA tags, 263 CI runs indexed | [41856321468](https://github.com/yituanxing/minicore/actions/runs/41856321468) |
| CAS-delete 21 performance/measurement refs | 21 deleted; Unified Linux Path evidence branch retained | [41856728348](https://github.com/yituanxing/minicore/actions/runs/41856728348) |

## Authoritative manifests / reports

- `maintenance/legacy-pr135-86-head-manifest-20261104.json`
- `maintenance/legacy-pr135-86-ci-evidence-20261104.json`
- `maintenance/legacy-pr135-86-retention-decision-20261104.json`
- `maintenance/remaining23-historic-pr-manifest-20261104.json`
- `maintenance/remaining23-ci-evidence-20261104.json`
- `maintenance/area34-ab-head-manifest-20261104.json`
- `maintenance/area34-ab-ci-evidence-20261104.json`
- `maintenance/area34-ab-retain5-delete33-20261104.json`
- `maintenance/measure-perf22-head-manifest-20261104.json`
- `maintenance/measure-perf22-ci-evidence-20261104.json`
- `maintenance/orphan137-branch-inventory-20261104.json`

## Protection decisions and next cleanup boundary

- Do **not** prune active PR #72's `test/l32-linux-real-programs`, Product #379, GSIM #378, FreeRTOS/RV64A and Zephyr validation heads or their base branches.
- Do **not** prune exact FROZEN architecture HEAD refs, Linux/RTL/FPGA qualification checkpoints, or branches hardcoded in active workflow/docs.
- Preserve all 23 historic PR heads closed in the second wave: each has unique commits outside main/Product and important historical validation.
- Preserve `area/pa32-stage1-ab` (300 CI runs) and the four other area PLIC/ROB qualification refs; preserve `perf/fast-host-data-multislot` as the Unified Linux Path evidence anchor.
- The 8 additional old `*-next/*-prep` branches received contradictory risk classifications: 7 contain meaningful Linux/NuttX/Sstc/architecture work, so **no deletion** was performed. Only `agent/zephyr-stage-resume-prep` may be a further individually tagged target.
- Two ancient queued CI runs on `explore/rv64a-v1` are unrelated to this wave and should not be interpreted as success or failure.
- All mutations were on the maintenance branch or safely closed historical PRs / exact branch refs. Neither main nor Product was merged into, reset or force-pushed.
