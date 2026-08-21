# GitHub-hosted CI and cache ownership

> Status: migration design for the public repository. This document records the ownership rules so hosted-runner performance work cannot silently weaken qualification.

## Goal

AetherCore CI must continue to run when no personal workstation or self-hosted runner is online. Public pull requests use GitHub-hosted Linux runners by default. A future trusted self-hosted runner may still be useful for FPGA synthesis, very long performance runs, or manual freeze qualification, but normal project progress must not depend on it.

## Core rule

**Cache is an accelerator, never an authority.**

The repository owns versions, revisions, hashes, recipes and qualification contracts. GitHub Actions cache may preserve bytes between disposable runners, but every restored payload is independently checked before use.

A cache miss is allowed to make a run slower. It must not change the result or make qualification impossible.

## Cache layers

### Immutable toolchains

Persist under `~/.cache/aethercore/toolchains`:

- pinned Verilator 5.024 source/build/install;
- pinned RV32 `riscv-none-elf` xPack toolchain;
- pinned RV64 Linux-target Bootlin GCC/binutils toolchain.

The existing `ensure_*` scripts remain authoritative. Their fixed source revisions/archive SHA-256 values and compile probes are re-run after restore.

### Scala/Mill dependencies

Persist dependency downloads under:

- `~/.cache/coursier`;
- `~/.cache/mill`.

These caches accelerate dependency/bootstrap downloads only. Generated RTL and test outputs are not qualification inputs.

### Reference implementations

Persist under:

- `~/.cache/aethercore/references`;
- `~/.cache/aethercore/sources`.

The RV32 NEMU reference remains pinned by revision and exact accepted shared-object SHA-256. Berkeley SoftFloat remains pinned by commit. Restored inputs are verified by the repository scripts before DiffTest consumes them.

### RV64 Linux/OpenSBI software

Persist:

- `~/.cache/aethercore/rv64/linux`;
- `~/.cache/aethercore/rv64/linux-build`;
- `~/.cache/aethercore/l32/opensbi`.

`rv64_linux_early_build.sh` already owns the important correctness boundary: Linux source SHA-256, compiler identity, configuration/recipe inputs and a generated `qualified.env`. A restored kernel object tree is accepted only when those contracts still pass.

The minimal-initramfs checkpoint seeds its private Kbuild object tree from that already-qualified baseline. It uses `cp -a --reflink=auto`: reflink when supported, ordinary copy otherwise, and deliberately never hard links. This prevents the derived initramfs build from mutating the qualified baseline cache through shared inodes.

### Exact-head simulation products

The RV64 PID1 workflow may cache the exact-head Verilator runtime directory under its exact commit SHA and RTL/simulator recipe hash. It is deliberately not restored across unrelated heads.

The first hosted Fast Gate does **not** persist mutable RTL/workload build outputs. Fresh workspaces plus immutable dependency caches are preferred until hosted behavior is stable enough to justify a separately audited incremental-build cache.

## Cache keys

Keys are content/recipe aware rather than named only by workload. Examples include hashes of the repository scripts that define the pinned toolchain or Linux recipe.

Restore prefixes may recover older cache contents, but repository-owned validation decides whether those bytes are reusable. An old restored directory is never equivalent to PASS.

## Artifacts versus caches

Actions artifacts contain evidence from a particular qualification run: logs, hashes, summaries and result manifests. They are not used as an implicit cache hit.

Long-lived frozen Linux/OpenSBI payloads should eventually use an immutable producer/consumer publication mechanism (release asset or equivalently durable frozen artifact plus repository manifest). Normal Actions cache is evictable and must not be the sole owner of a frozen behavioral oracle.

## Public repository safety

Normal public pull requests run only on GitHub-hosted runners. Do not expose a personal self-hosted runner to arbitrary fork pull-request code.

A future self-hosted lane must be explicitly trusted/manual and should be reserved for work where its persistent hardware or FPGA access is actually necessary.

## Validation topology

The existing validation policy remains useful:

1. **Frontier** — deepest real-software behavioral oracle first;
2. **Milestone** — focused affected regression pack;
3. **Freeze** — complete historical qualification.

Hosted runners remove the old single-machine scheduling constraint, so later migration may execute independent coarse-grained qualification lanes in parallel. Do not split the CI into dozens of tiny jobs merely because parallel runners exist; cache/download/setup overhead and evidence ownership should remain explicit.

## Migration order

1. Fast Gate -> GitHub-hosted, immutable dependency/reference caches.
2. RV64 minimal PID1 -> GitHub-hosted, toolchain + Linux/OpenSBI + exact-head simulation caches.
3. Qualify cold-cache and warm-cache behavior.
4. Migrate the remaining RV32 Linux, FreeRTOS, NuttX and freeze gates in bounded slices.
5. Keep self-hosted execution optional/manual rather than project-critical.
