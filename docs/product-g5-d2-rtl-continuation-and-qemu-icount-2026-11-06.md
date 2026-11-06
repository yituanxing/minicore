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

### Second resumed run: 1.00B to 1.05B

| RTL cycles | Retired instructions | CPIO cursor percent | Approx. completed entries |
| ---: | ---: | ---: | ---: |
| 1,005,000,000 | 259,696,170 | 70.60% | 851 |
| 1,010,000,000 | 260,894,833 | 70.60% | 851 |
| 1,015,000,000 | 262,100,224 | 73.72% | 853 |
| 1,020,000,000 | 263,341,863 | 75.59% | 860 |
| 1,025,000,000 | 264,658,813 | 76.23% | 869 |
| 1,030,000,000 | 266,191,787 | 76.23% | 869 |
| 1,035,000,000 | 267,523,624 | 77.74% | 881 |
| 1,040,000,000 | 268,830,044 | 77.96% | 895 |
| 1,045,000,000 | 270,119,701 | 78.10% | 912 |
| 1,050,000,000 | 271,403,110 | 78.22% | 928 |

All ten 5M slices completed with positive retirement and saved exact-model
checkpoints. The 1B→1.05B interval retired 12,906,126 instructions in
50,000,000 RTL cycles, i.e. **3.874 RTL cycles per retired instruction for
that interval**, not a universal CPU CPI or a QEMU conversion factor. At
1.05B, 14,910,916 CPIO input bytes and ~2,336 per-file entries remain;
there was still no observed Debian PID1 serial marker. A subsequent 1.05B→1.10B
continuation was initiated from the new exact state.

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

To preserve that cache across workflow invocations, the QEMU-only oracle
workflow can now upload the four exact binaries, four PASS manifests,
`ORIGIN.txt` (source run ID/attempt/commit/workflow) and `SHA256SUMS` **after**
the existing Product-firmware oracle succeeds. The separate
`.github/workflows/product-g5-qemu-icount-from-artifact.yml` workflow checks
the source GitHub run is successful, belongs to this repository and the exact
QEMU oracle workflow, and matches the required source SHA. It downloads the
named immutable artifact, verifies the complete eight-file whitelist and all
hashes **before** staging and running QEMU. It has no GCC/Linux/Scala or RTL
build steps. The new manual-dispatch workflow must first exist on GitHub's
default branch before GitHub will accept its dispatch; merely adding it to a
feature branch does **not** enable a run from the Actions UI. No such new
producer/consumer run has been attempted as of this report.

### Architectural handoff is a separate implementation

The existing G5 checkpoint reader can restore a 256MiB DDR image, but its
`VerilatedRestore` is for an **identical G5 RTL model state**, not QEMU state.
Loading QEMU RAM and pointing the PC at `run_init_process` is unsafe. A proper
warm importer needs a quiescent control point in
`ProductSmallPagedCore.scala`, privilege/machine CSR/SATP/PMP/FCSR loading in
`MachineCsrFile.scala` and its submodules, controlled physical register
population in `ProductSmallOoOBackend.scala` / `ProductPhysicalValueStore.scala`
with ROB/LSQ/cache/TLB/PTW empty, and reconciliation of PLIC/UART/ACLINT
state in `AetherSoCPlatformFabric.scala`. A safe first test would hand off a
tiny program, compare short retired-PC/register/memory traces, and only then
attempt a kernel boundary. **The DDR importer and these safety checks do not
exist yet; the QEMU result cannot currently skip D2 RTL initramfs unpack.**
Even with the byte-identical Product DTB, QEMU runs a `virt` machine rather
than the G5 SoC's actual fabric; the interrupt controller, timer, and UART
device state must not be assumed interchangeable from the DTB alone.

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
