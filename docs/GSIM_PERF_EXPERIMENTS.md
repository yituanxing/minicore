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
