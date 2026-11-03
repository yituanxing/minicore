# GSIM SuperNode + LLVM PGO strict-performance experiments

**Qualified baseline:** [run #41578321826](https://github.com/yituanxing/minicore/actions/runs/41578321826), commit `39b1f628`, full Linux PID1 at 428,327,594 guest cycles; GSIM 759.18 s, Verilator 1083.30 s, speedup 1.42692x.

The [experimental sweep](../.github/workflows/rv64-pa32-gsim-performance.yml) uses one GitHub runner and one firmware SHA-256. It creates a single Verilator 5.054 + Adaptive model and tests GSIM SuperNode 35, 15, 24, 48. Each candidate must pass the same 20k RV64I differential and **20,000,000-cycle real OpenSBI/Linux prefix strict differential** before its speed is reported. Checkpoint, commit digest, UART and 256 MiB host RAM checks remain mandatory.

After the SuperNode sweep, the fastest 20M candidate is compiled with LLVM instrumentation, trained with the real firmware and 20M guest cycles, merged with llvm-profdata-19, rebuilt with an LLVM profile, and again checked against Verilator on the exact same 20M benchmark. Performance and correctness evidence are preserved as artifacts, including failures. Each phase produces live status messages, and simulator engines report regular progress.

**Important:** A faster 20M result does not qualify the changed GSIM model for complete Linux PID1. The final selected SuperNode/PGO variant must pass the full 428M-class Linux + PID1 + STIP/SEIP + UART + RAM differential before replacing the default engine.

Execute locally after provisioning pinned toolchains and building the known firmware:

```bash
JOBS=4 bash tools/ci/rv64_pa32_gsim_performance_sweep.sh
```

The sweep intentionally regenerates and recompiles GSIM per SuperNode setting to avoid stale graph/model artifacts. Results still require repeated matched host measurements to distinguish true speedups from thermal or system noise. The PGO route uses clang instrumentation rather than privileged `perf` or BOLT.

Default PR gates run the strict 20M firmware differential. The already-qualified 450M Linux PID1 signoff is explicit workflow_dispatch with full_linux=true. No candidate is promoted on 20M results alone.

## Bounded profiling and stop criterion

The host-bridge CI now opts into `AETHERCORE_GSIM_PHASE_PROFILE=1`
for **one existing 20M strict GSIM/Verilator firmware differential**.
The regular GSIM runner remains unprofiled by default. Every 4093 guest
cycles it samples the three mutually exclusive host phases:

* `request_ns`: request/response, RAM and port setup prior to `top->step()`
* `model_ns`: `top->step()`, **including** internal same-tick host bridge
* `observe_ns`: retirement, UART, interrupts, checkpoints and progress

Percentages are **sampled phase estimates**, not proven exact whole-run
percentages. The terminal 256 MiB RAM state digest is outside the timing.
The profile summary is streamed to CI and retained in the full log.

**Stop gate:** take this measurement, make at most one coherent optimization
batch against a demonstrable bottleneck, then require complete 20M strict
differential and matched same-host speed comparison. Do not promote without
the full Linux PID1/STIP/SEIP/UART/RAM signoff. If improvement is not stable
and material, stop optimizing the simulator and return to RV64 SoC architecture,
device integration, and FPGA Linux bring-up.

The four-SuperNode + PGO scan is **manual-dispatch only** from this point:
ordinary PR updates must not automatically repeat the broad optimization suite.
