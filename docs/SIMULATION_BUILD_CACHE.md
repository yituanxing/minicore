# Linux RTL simulation: staged build reuse

This is the **build-cache** part of the cold/warm acceleration plan. It is
intentionally separate from a resumable Linux checkpoint: caching a simulator
does not cache a Linux PASS and does not skip a requested runtime test.

## Current implementation

`Makefile.l32-linux-boot` now offers three distinct entry points:

| Target | Behavior | Intended use |
| --- | --- | --- |
| `run-local` | Always regenerate RTL and call Verilator | Forced clean model build / baseline |
| `run-cached` | Verify RTL and simulator identities; build only missed stages; **always** execute requested firmware test | Fast cold Linux iteration |
| `forkserver-cached` | Same staged reuse for the independent L32 forkserver executable; then execute the warm workload batch | Fast legacy L32 warm-shell iteration |

The legacy `run-existing` still bypasses construction and checks only that an
executable exists. It is **not** a qualified cache lookup. Prefer `run-cached`
for normal development. No production RTL, test image or test oracle is changed
by these targets.

Use the existing `make -f Makefile.l32-linux-boot ...` arguments, replacing
the final target with `run-cached` or `forkserver-cached`. An example for a
locally provisioned RV64 virtual board is:

```bash
make -f Makefile.l32-linux-boot \
  BUILD_DIR=build/v2-virtual-fpga-linux-pid1-model \
  TOP=AetherCoreV2OpenSbiRV64SimTop \
  ELABORATE_MAIN=aethercore.ElaborateV2VirtualFpgaBoardPA32RV64 \
  FW_BIN=build/rv64-minimal-init-boot/opensbi/platform/generic/firmware/fw_payload.bin \
  MAX_CYCLES=950000000 MILESTONE='RV64 USER UART IRQ OK' \
  MIN_INTERRUPTS=1 MIN_STIP=1 MIN_SEIP=1 \
  VERILATOR='verilator -LDFLAGS -ldl' \
  SIM_CXXFLAGS="-std=c++20 -O3 -march=native -I$(pwd)/sim/v2_rv64_opensbi_shim -I$(pwd)/sim" \
  run-cached
```

The toolchain and firmware still need to be provisioned with the project's
existing scripts before this command is executed.

## Qualification and invalidation

`tools/ci/l32_sim_artifact_cache.py` uses two independent qualified markers:

1. `rtl/.aethercore-rtl-qualified.json`: Chisel source contents (including
   local uncommitted and untracked source files), Mill/build recipe,
   elaboration entry point, and each generated `.sv` file SHA256.
2. `obj/.aethercore-sim-qualified.json` (or `obj-forkserver/...`): all of the
   above plus the generated SV contents, top, host C++/shim source contents,
   C++ compiler identity, Verilator identity, build flags, host architecture,
   native CPU features when `-march=native` is used, and the executable SHA256.

Markers are published atomically **only after** the relevant build succeeds;
invalid, missing or corrupted markers cause a rebuild. Firmware bytes,
test marker, UART input and timeout are runtime inputs, not model inputs.
Changing those does not trigger Verilator recompilation; running the resulting
test is still mandatory on every invocation.

The RV64 virtual FPGA workflow restores and saves `rtl/` and `obj/` using an
input-derived hosted Actions cache key. Repository validation remains the final
cache authority. It keeps `-march=native`, so Actions keys additionally include
the host CPU identity, and the cache manifest independently checks CPU features.
Changing Linux/firmware without changing Chisel/runner sources can therefore
reuse the compiled simulator across separate hosted runs.

## Performance measurements and next stages

The expected saving is in **RTL elaboration and C++ compilation**, not the
421.5-million-cycle Linux PID1 simulation itself. Compare a cold-cache run and
a matching warm-cache run with separate timestamps for generated RTL, Verilator
build and Linux runtime. The boot runner now emits `L32_SIM_PHASE` at the
OpenSBI banner, Linux banner and requested milestone. Each record includes
delta guest cycles, delta host seconds, effective phase CPS, cumulative commit
count and observed UART byte count. Its existing `L32_SIM_PROGRESS` records
still provide regular long-running progress even before a phase is reached.
No speedup multiplier is claimed until these durations are measured.

The previous L32 `fork()`/COW warm-shell test mechanism remains supported, but
the RV64 minimal PID1 image has no BusyBox command shell; it cannot consume the
L32 forkserver batch simply by changing `TOP`. Extending that workload protocol
is a separate step.

A **persistent Linux checkpoint** requires the RTL model, 256 MiB host RAM,
atomic LR/SC reservation, simulator time, outstanding bus/peripheral state,
timers/interrupts and host input state. A future restore marker must be keyed
by *both* the qualified model and firmware/device configuration. Such a
checkpoint is not implemented by the new build cache, and cannot replace the
unmodified reset-to-PID1 cold qualification.
