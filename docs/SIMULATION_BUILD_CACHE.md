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

1. `rtl/by-source/<source-key>/.aethercore-rtl-qualified.json`: Chisel source
   contents (including local uncommitted and untracked source files),
   Mill/build recipe, elaboration entry point, and each generated `.sv` SHA256.
   Each source recipe has a separate directory; a failed or changed elaboration
   cannot leave obsolete `.sv` in the next build's input set. On a cache miss,
   generated `.sv` files in that recipe's directory are cleared before Mill runs.
2. `obj/.aethercore-sim-qualified.json` (or `obj-forkserver/...`): the
   generated SV file names and contents, top, host C++/shim source contents,
   C++ compiler identity, Verilator identity, build flags, host architecture,
   native CPU features when `-march=native` is used, and the executable SHA256.
   A **qualified** RTL tree is still required before the compiled simulator
   can be reused; a different Scala source recipe does *not* force Verilator
   recompilation if its newly generated SV is byte-identical. On a simulator
   cache miss the generated `obj/` tree is discarded to prevent stale C++
   objects from being accidentally relinked or requalified.

Markers are published atomically **only after** the relevant build succeeds;
invalid, missing or corrupted markers cause a rebuild. Firmware bytes,
test marker, UART input and timeout are runtime inputs, not model inputs.
Changing those does not trigger Verilator recompilation; running the resulting
test is still mandatory on every invocation.

The RV64 virtual FPGA workflow restores and saves `rtl/` and `obj/` using an
input-derived hosted Actions cache **prefix** plus a unique workflow run suffix.
The restore prefixes allow cross-commit reuse and let a new run replace a
corrupted immutable Actions cache entry. Repository validation remains the final
cache authority. It keeps `-march=native`, so Actions keys also include the host
CPU identity, and the manifest independently checks CPU features. Changing
Linux/firmware without changing Chisel/runner sources can therefore reuse the
compiled simulator across separate hosted runs. Only a newly built, qualified
simulator **or newly elaborated RTL** is uploaded; an already-restored model
and RTL tree are not uploaded again. The Linux workflow also saves the
validated Scala dependencies, RV64 GCC and Linux/OpenSBI baseline immediately
after each successful producer stage, instead of depending on a different
workflow to seed those caches.

Because GitHub-hosted machines can have different CPU instruction-set flags,
one hosted run may correctly MISS the native-model cache restored from a
different host. The workflow additionally runs an explicit **same-host**
`sim-cached` second lookup after the full Linux PASS, requiring RTL and model
HIT without executing Linux a second time. This distinguishes real cache
correctness from cross-run host availability. Both HIT/MISS records remain in
the uploaded build-stage log.

## Performance measurements and next stages

The expected saving is in **RTL elaboration and C++ compilation**, not the
421.5-million-cycle Linux PID1 simulation itself. Compare a cold-cache run and
a matching warm-cache run with separate timestamps for generated RTL, Verilator
build and Linux runtime. Each cached `make` invocation logs its HIT/MISS and
elapsed seconds for those stages to `logs/cache-stages.log`, which is uploaded
with the virtual FPGA CI evidence. The boot runner emits `L32_SIM_PHASE` at the
OpenSBI banner, Linux banner and requested milestone. Each record includes
delta guest cycles, delta host seconds, effective phase CPS, cumulative commit
count and observed UART byte count. Its existing `L32_SIM_PROGRESS` records
still provide regular long-running progress even before a phase is reached.
No speedup multiplier is claimed until real cold/warm PID1 workflow durations
have been measured and compared on equivalent hosts.

### Hosted measurements: 2026-11-02

Three full RV64 virtual-FPGA PID1 jobs reached the real `RV64 USER UART IRQ OK`
milestone and passed the required STIP/SEIP/interrupt checks. These are
wall-clock observations, **not** a controlled same-firmware/same-host IPC A/B:

| GitHub Actions run | Toolchain producer | RTL / compiled model | Linux runtime | Main job wall time | Result |
| --- | ---: | --- | ---: | ---: | --- |
| [41434010080](https://github.com/yituanxing/minicore/actions/runs/41434010080) | 232 s, cold | MISS 14 s / MISS 21 s | 1815 s | ~43m22s | PASS |
| [41446019947](https://github.com/yituanxing/minicore/actions/runs/41446019947) | 5 s, HIT | MISS 11 s / MISS 18 s | 1669 s | ~31m52s | PASS |
| [41450753730](https://github.com/yituanxing/minicore/actions/runs/41450753730) | 5 s, HIT | HIT 0 s / HIT 0 s | 1749 s | ~32m55s | PASS |

The third run restored the **actual first run's** qualified simulator cache
(`AETHERCORE_SIM_CACHE_HIT stage=rtl` and `stage=sim`) and then independently
proved same-host HIT/HIT after the full Linux PASS with
`AETHERCORE_SAME_HOST_CACHE_PASS stage=rtl,sim`. The middle run had a different
native CPU fingerprint, so rejecting the first run's `-march=native` binary
was intentional. The Linux baseline itself was a qualified cache HIT in the
second and third runs.

Total pipeline time from the producer's start to the main job's finish fell
from about 47m21s to 33m07s on the first-versus-third comparison. Of that, the actual
RTL/Verilator model build only accounted for 35 seconds on the cold run; the
vast majority of remaining time was **executing 424–427 million guest cycles**
at roughly 0.23–0.25 million cycles/second. Persistent guest-state checkpoints
and warm workload replay remain a separate, much larger opportunity.

**Reproducibility caveat:** the three jobs' final OpenSBI `fw_payload.bin`
SHA256 values differed; the embedded Linux `Image` hashes also differed.
Conversely, the minimal PID1 ELF, initramfs file-list, resolved kernel config
and device-tree blob matched by SHA256 across all three runs. The first varying
artifact is therefore in the rebuilt initramfs Linux kernel image, but its
precise cause has **not** been established. A future persisted guest-state
checkpoint must always key on the *actual* firmware hash, not just the Linux
version or source revision. Resolving this reproducibility gap is separate
work; do not claim the Linux runtime differences above as a simulation-speed
improvement attributable to caching.

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
