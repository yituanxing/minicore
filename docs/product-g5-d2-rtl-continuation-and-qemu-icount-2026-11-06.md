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

The source archive identifies the tested RTL model lineage as commit
`99a5c86e99f52ed12f95b304635d77613c9505c4`, archived model SHA256
`aa35c80024238420bea3976db677db2e0ef028aad9800482926b926e50155b71`,
rebuilt exact-model SHA256
`5ed1e31ea3c1a5dc8761e4ffb33a35a48db412e359521efa3e3eb408cfbae43d`;
the golden replay had identical RTL/host hashes. These observations verify
the **archived model**, not arbitrary newer RTL changes at branch HEAD.

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

### Third and fourth resumed runs: 1.05B to 1.15B

| RTL cycles | Retired instructions | CPIO cursor percent | Approx. completed entries |
| ---: | ---: | ---: | ---: |
| 1,055,000,000 | 272,701,636 | 78.36% | 945 |
| 1,060,000,000 | 274,061,517 | 78.68% | 956 |
| 1,065,000,000 | 275,523,772 | 79.52% | 960 |
| 1,070,000,000 | 276,793,268 | 79.65% | 977 |
| 1,075,000,000 | 278,107,500 | 79.90% | 990 |
| 1,080,000,000 | 279,372,406 | 80.58% | 998 |
| 1,085,000,000 | 280,676,664 | 80.92% | 1,010 |
| 1,090,000,000 | 281,953,400 | 81.04% | 1,026 |
| 1,095,000,000 | 283,221,640 | 81.09% | 1,044 |
| 1,100,000,000 | 284,488,643 | 81.12% | 1,061 |
| 1,105,000,000 | 285,778,298 | 81.19% | 1,078 |
| 1,110,000,000 | 287,042,780 | 81.22% | 1,096 |
| 1,115,000,000 | 288,318,411 | 81.25% | 1,113 |
| 1,120,000,000 | 289,596,730 | 81.28% | 1,130 |
| 1,125,000,000 | 290,876,358 | 81.35% | 1,147 |
| 1,130,000,000 | 292,150,072 | 81.43% | 1,164 |
| 1,135,000,000 | 293,424,755 | 81.48% | 1,181 |
| 1,140,000,000 | 294,704,043 | 81.53% | 1,198 |
| 1,145,000,000 | 295,982,220 | 81.65% | 1,214 |
| 1,150,000,000 | 297,258,096 | 81.77% | 1,229 |

All twenty further 5M slices completed and saved checkpoints, with no
retirement stalls. The most recent 1.10B→1.15B interval retired 12,769,453
instructions in 50,000,000 RTL cycles (3.9156 observed cycles per retired
instruction), but remained in `unpack_to_rootfs` among many small Perl files.
At 1.15B, 12,478,952 input bytes and ~2,035 entries remained; **the Debian
PID1 marker has not appeared**. The next 1.15B→1.20B archived-RTL
continuation was launched separately, not as a cold CI rebuild.

### Fifth resumed run: 1.15B to 1.20B

| RTL cycles | Retired instructions | CPIO cursor percent | Approx. completed entries |
| ---: | ---: | ---: | ---: |
| 1,155,000,000 | 298,532,127 | 81.83% | 1,246 |
| 1,160,000,000 | 299,799,947 | 81.86% | 1,263 |
| 1,165,000,000 | 301,066,177 | 81.89% | 1,280 |
| 1,170,000,000 | 302,329,886 | 81.90% | 1,298 |
| 1,175,000,000 | 303,600,937 | 81.96% | 1,315 |
| 1,180,000,000 | 304,882,784 | 82.01% | 1,332 |
| 1,185,000,000 | 306,164,069 | 82.06% | 1,349 |
| 1,190,000,000 | 307,426,799 | 82.08% | 1,366 |
| 1,195,000,000 | 308,685,653 | 82.11% | 1,384 |
| 1,200,000,000 | 309,949,753 | 82.13% | 1,401 |

Ten more 5M slices completed, with full snapshots and nonzero instruction
retirement at every step. The 1.15B→1.20B interval retired 12,691,657
instructions in 50,000,000 RTL cycles, or about 3.94 observed cycles per
retired instruction. At 1.20B, the parser still has 12,233,944 CPIO bytes
and ~1,863 entries to process; **no Debian PID1 marker appeared**. The 965M→
1.20B exact-model continuation accumulated 235M additional RTL cycles. The
model is not stalled; the observed workload is now numerous tiny Perl files
with significant per-entry overhead.

### Speed audit and safe alternative-host gate

The exact 99a5c86e archive was generated with Verilator 5.054 `--savable`,
host `-O3`/`-march=native`, but its G5 CI explicitly fixed
`SIM_SETTLE_MODE=reference` and rejected the historical
`AETHERCORE_SIM_ADAPTIVE_SETTLE` option. It is therefore **incorrect** to say
the archived 965M→1.20B continuation enabled every discovered optimization.
The host state hash and progress report each consume substantially less than
one second per slice; each 5M RTL-cycle slice typically consumes ~45–80s.
Most time is real simulated execution, not rebuilding or hashing.

`tools/ci/product_g5_checkpoint_host_ab.sh` is now an explicit, strict A/B
qualification gate for a *candidate* alternate C++ host binary that shares
the same Verilator RTL save format. It validates source identity and all input
file SHA256 values, starts both binaries from the same archived host+RTL state,
runs at most 1,000,000 cycles, and requires byte-identical resulting host and
RTL snapshots and identical cycle/commit/UART/trace summaries. It **never**
switches the active continuation binary automatically. A 500,000-cycle A/A
smoke with the **same baseline binary on both sides** passed at 1.15B:
9,170ms versus 7,965ms (a 1.151× difference from scheduling/noise, **not**
evidence of adaptive speedup). The compiled adaptive candidate and a real A/B
speed/equivalence measurement **do not yet exist**. The likely low-cost route
is recompiling only the C++ host against cached Verilator generated model
objects after making those objects available; the scratch archive has no
generated `obj_dir` or `__ALL.a`. Until A/B is proven, resume only the pinned
reference model from the 1.20B checkpoint.

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
