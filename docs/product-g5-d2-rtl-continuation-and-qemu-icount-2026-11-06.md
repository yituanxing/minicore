# Product G5 Debian D2: archived RTL continuation and QEMU instruction oracle

Date: 2026-11-06. Branch: `design/rv64gc-product-architecture-20261103`.

## Two distinct measurements

- **AetherCore RTL guest cycles** are authoritative for this particular Product
  G5 model, firmware, and saved CPU/device/DDR state. Continue with
  `tools/ci/product_g5_d2_continue_from_checkpoint.sh` from the **immediately
  preceding exact-model host+RTL checkpoint**, in 5,000,000-cycle increments.
- **QEMU guest instructions** can be sampled with
  `tools/ci/product_g5_qemu_export/run_icount_probe.sh` at exact Linux kernel
  symbols `populate_rootfs` and `run_init_process`. QEMU instruction count is
  **not** a measurement or exact projection of RTL clock cycles, and its
  virtual timer configuration may change interrupt behavior.

Do not conflate a CPIO input cursor, a kernel `run_init_process` breakpoint,
the number of retired instructions, and an observed Debian PID1 serial marker.

## RTL continuation procedure

Do **not** recompile or cold boot G5 for each slice. The continuation tool pins
the model, firmware identity, kernel ELF and System.map, refuses an already
existing output directory, reads the starting checkpoint's **actual** cycle
counter, verifies retirement progress, saves both host/RTL checkpoint files,
and hashes each completed checkpoint. It can be rerun on the last verified
checkpoint after an interrupted run; it never mutates its starting state.

Example (relative paths from the scratch workspace containing the archive):

```bash
bash tools/ci/product_g5_d2_continue_from_checkpoint.sh \
  d2-archive-probe-verified \
  d2-965m-to-1b-live/c1000000000 \
  d2-1b-to-1050m-live \
  1000000000 1050000000
```

The runner caps a single invocation at 100M cycles, saves checkpoints every
5M, applies a 240-second wall-time limit **per** 5M slice, and stops early if
the actual `AETHERCORE_DEBIAN_D2_START` PID1 serial marker appears.

### First resumed run: verified checkpoints

| RTL cycles | Retired instructions | CPIO cursor / 68,467,200 bytes | Approx. completed entries / 3,264 |
| ---: | ---: | ---: | ---: |
| 965,000,000 | 248,850,739 | 64.05% | 823 |
| 970,000,000 | 250,167,402 | 64.38% | 836 |
| 975,000,000 | 251,625,259 | 65.25% | 840 |
| 980,000,000 | 252,922,095 | 65.94% | 844 |
| 985,000,000 | 254,266,980 | 67.35% | 848 |
| 990,000,000 | 255,783,114 | 67.35% | 848 |
| 995,000,000 | 257,291,353 | 69.23% | 849 |
| 1,000,000,000 | 258,496,984 | 70.60% | 851 |

All seven 5M slices from 965M to 1B completed with nonzero retirement deltas.
The byte cursor remained at 67.35% at 990M because Linux was operating on
the 1.28MB SQLite shared library. It advanced after that file was completed;
**no stall or PID1 PASS is implied**.

`product_g5_initramfs_progress.py` now also examines `wfile_pos` only if Linux
is in its `CopyFile` state **and** CPIO record bounds, `next_header`,
`body_len`, and offset arithmetic agree. For example at 985M the kernel is in
`SkipIt`; the 276,608 value is a *previous file's stale write offset* and must
not be displayed as current-file progress. Even a validated zero `wfile_pos`
may lag an in-flight `kernel_write` and must not be treated as a deadlock.

Do not extrapolate PID1 cycles directly from `cursor_bytes / total_bytes`:
there are thousands of per-file metadata operations and nonuniform copy costs.

## QEMU-only diagnostics and limits

The historical Product-firmware QEMU architecture exporter was blocked by a
GDB `target.xml` containing an `xi:include` with an undeclared namespace.
`qemu_arch_export.py` now repairs **only** this known target-root omission,
then validates feature includes, register numbering, and XML bounds. The
exporter does **not** implement QEMU-to-RTL warm handoff; device/interrupt,
timer, privilege and DDR restoration still need explicit validation.

For an already staged exact D2 Product firmware, DTB, Linux Image, and
System.map (plus their PASS manifests), run QEMU only:

```bash
RV64_DEBIAN_STAGE=d2 \
AETHERCORE_ALLOW_G5_QEMU_ICOUNT=1 \
AETHERCORE_G5_QEMU_ICOUNT_SHIFT=6 \
AETHERCORE_G5_QEMU_WALL_SECONDS=300 \
bash tools/ci/product_g5_qemu_export/run_icount_probe.sh run
```

It uses one QEMU instance, one virtual hart, exact `-S` GDB stops, validates
the actual stopped PC at both kernel symbols, and queries QMP
`query-replay.icount` in the same process. It records a JSON manifest, QEMU
binary and Product inputs' checksums, serial and diagnostic logs, and a
**QEMU-instruction-only** qualification. The wall-time budget is bounded.

Validation as of this report: 9/9 architecture-export self-tests and 6/6
QEMU instruction probe mock tests passed; shell syntax checks passed. **No
real QEMU icount run** has been executed in the scratch workspace (QEMU and
the staged Product binaries are absent). The existing QEMU GitHub workflow
builds the guest before running it; to make a genuinely quick counter-only
workflow, first publish SHA-pinned firmware/DTB/Image/System.map as a reusable
artifact and then run the QEMU-only command from that artifact. Do not start a
full RTL/Verilator/GSIM CI run just to measure QEMU instructions.

## Completion criteria

1. Keep extending the archived exact-model RTL checkpoint, observe retired
   instructions and the real CPIO parser state, and record PID1 marker only
   when observed in serial output.
2. On a runner holding exact Product D2 images, obtain QEMU instruction counts
   for both kernel symbols; compare their *instruction* distance to the RTL
   retired-instruction progress, accounting for architectural/emulator
   differences. Do not report QEMU as RTL cycles.
3. Consider CPU/DDR/device QEMU-to-RTL handoff a separate, unqualified path
   until a state importer and a reproducible differential test have run.
