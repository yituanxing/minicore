# PA32 GSIM daily run and live feedback

Qualified baseline: [Linux PID1 CI](https://github.com/yituanxing/minicore/actions/runs/41578321826),
commit `39b1f62`. At 428,327,594 guest cycles, GSIM was 1.42692x
faster than Verilator 5.054 + Adaptive (759.183 s vs 1083.296 s).

After building the existing pinned GSIM native model, reuse it without
rerunning the Verilator engine:

```bash
FW_BIN=build/rv64-minimal-init-boot/opensbi/platform/generic/firmware/fw_payload.bin
SIM_LOG=build/daily-gsim.log SIM_TIMEOUT_SECONDS=7200 \
  bash tools/ci/rv64_pa32_sim_run.sh gsim "$FW_BIN" 450000000 --linux
```

Change `gsim` to `verilator` for a single Verilator reference run (the
Verilator model must be built first). To compare both with strict per-100k
checkpoints, UART, interrupts, commit digest and full RAM, use
`tools/ci/rv64_pa32_gsim_firmware_diff.sh` instead.

The runner saves every diagnostic line to SIM_LOG but streams a compact live
heartbeat every five million guest cycles, plus first-observed
OpenSBI/Linux/PID1/STIP/SEIP events. Change the frequency with
`AETHERCORE_SIM_PROGRESS_CYCLES`. The reported
`budget_eta_seconds` is based on the maximum cycle budget, not a promise
of when PID1 will occur (Linux can stop early). Failure or timeout preserves
a nonzero return code and prints the final 60 diagnostic lines.

Progress is emitted at existing 100k-cycle checkpoints and is host-only.
It does not update guest time, architectural state or the differential oracle.
