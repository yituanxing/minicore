# Product C1 cross-simulator architectural relay: isolated experiment

This experiment is **not** the existing Product SD1 C1 same-RTL checkpoint
(`c1-save/advance/release`), and it does **not** modify or replace the official
signed SD1 cold RTL service/login long run.

## First contract gate, implemented

`tools/ci/product_c1_arch_relay/preflight.py` checks the existing optional
G5 QEMU architectural export at `run_init_process`:

- one hart, S-mode capture, 0x80000000-based exact 256 MiB physical RAM;
- actual SHA-256 of RAM, exact fw_payload, DTB, Linux Image and System.map;
- breakpoint PC vs captured PC, x0=0 and missing minimum S-mode GPR/CSR evidence;
- separate PMP register coverage and non-restorable counter/WARL CSR report;
- 32 FPRs plus FCSR when `mstatus.FS` is not Off;
- QEMU device/RTL state omissions remain explicit; no unavailable CSR becomes 0.

Run after collecting a real QEMU diagnostic export (all four input paths must
point to the original exact bytes):

```bash
python3 tools/ci/product_c1_arch_relay/preflight.py \
  --manifest /work/export/manifest.json \
  --ram /work/export/ram-0x80000000-0x10000000.bin \
  --firmware /work/fw_payload.bin --dtb /work/product.dtb \
  --linux-image /work/Image --system-map /work/System.map \
  --report /work/c1-arch-source-review.json

python3 -m unittest tests_py.test_product_c1_arch_relay_preflight -v
```

This tool creates a report only if its target path does not exist, and prints
`PRODUCT_C1_ARCH_SOURCE_SUBSET_REVIEWED_NOT_RTL_RESUMED`. It **always** writes
`rtl_handoff_pass=false`. It is not a state converter or an RTL injector.
If required architectural registers are absent, it still records their names
in the JSON report but exits with status 3 (not a successful input gate).
`minimum_s_mode_register_subset_present=true` does **not** mean a complete
or restorable architectural state (notably PMP, counters, WARL and devices).
It does not assert device equivalence or successful restoration. The QEMU
diagnostic source itself is not yet qualified on a real
QEMU runner in this environment.

## Isolated S-mode P0 restore ABI (implemented; real RTL still unqualified)

`sim/product_c1_arch_restore.S` defines a 424-byte little-endian machine-state
table (`PC`, 18 CSR slots, two PMP slots, `x0..x31`) and an M-mode instruction sequence that
rebuilds GPR/selected CSR state and attempts `mret` to an S-mode resume PC.
`tools/ci/product_c1_arch_relay/restore_abi.py` packs that table only after a
matching SHA-verified source review and only for deliberately restricted P0
fixtures: FS=Off, SATP=0, no enabled/pending IRQ, **exactly one unlocked
PMP NAPOT RWX entry** covering the fixed Product 256 MiB DDR aperture
`[0x80000000, 0x90000000)` (`pmpaddr0=0x21ffffff`, low byte of
`pmpcfg0=0x1f`, all other PMP slots zero), and MSTATUS/MEPC already
compatible with exact `mret` side effects.  Product has PMP16, and a
zeroed PMP16 denies unmatched S/U fetches and memory accesses. The assembly
writes `pmpaddr0` before enabling `pmpcfg0`, prior to `mret`.
It is **not** suitable for normal Linux/SD1 snapshots.  The isolated fixture
and Host simulation entry now exist, but compilation with the frozen RV64
toolchain and actual Product RTL execution still require the dedicated CI gate.

For the isolated synthetic P0 fixture, the Host can overlay only the reserved
first MiB, without modifying source RAM, and turn the overlay off after the
restoring `mret` retires. The target code is outside this reserved aperture;
the earlier `fence.i` therefore does **not** establish general Linux cache
coherence. Actual QEMU/Linux snapshots still need a reserved-memory/DTB
contract, WARL-aware state qualification, and a post-resume reference trace.

## P0 synthetic fixture and next Linux experiment

The isolated P0 files are `sim/product_c1_arch_p0_main.cpp`,
`sim/product_c1_arch_p0_overlay.h`, `sim/product_c1_arch_p0_fixture.S`,
`sim/product_c1_arch_p0.ld` and the fixture tool under
`tools/ci/product_c1_arch_relay/`. CI in
`.github/workflows/product-c1-arch-p0.yml` builds a *real* G4 Product RTL
executable on the experimental branch, then must observe MRET retirement,
five target retirements and four matching memory markers. Its synthetic source
is **not** a QEMU-generated Linux checkpoint, and a passing fixture still
does not qualify cross-simulator Linux execution.

1. Use existing G3/G4 real Product OpenSBI and Linux fixtures for a bounded
   comparison. Do **not** try to import QEMU virtio state directly into SD1 SPI.
2. After the isolated P0 Host fixture runs in real Product RTL, adapt the
   architecture export to a *real* QEMU/NEMU S-mode capture. Product has no
   architectural debug-load port, so the restore shim must execute from the
   ROM-to-DDR boot path, rebuild GPR/allowed CSR state and retire MRET.
3. Prove original memory is restored before returning, caches/TLB are
   coherent, no MMIO/IRQ/LRSC/DMA work is in flight, and mret/WARL effects
   match reference. `mcycle`/`minstret` and some counter/config CSRs cannot
   simply be loaded; reject incompatible captures instead of silently changing
   values. Compare a short RTL commit trace against the source execution.
4. Only after a real S-mode RAM-only resumption with a bounded trace match,
   test Linux initramfs/PID1. Device-aware SPI SD1 is a later, separate gate.

## Independent acceptance

| Track | Actual requirement |
| --- | --- |
| Source contract | SHA-verified evidence and explicit omissions; never RTL PASS |
| Cross-simulator C1 POC | Real Product RTL resumes at exported PC and matches a bounded reference commit/state trace |
| Same-RTL C1 media rebind | True RTL `AETHERCORE_C1_READY`, SPI quiescent, verified consumed-sector ledger, then successful rootfs handoff |
| Official signed SD1 | True Product SmallOoO RTL UART complete Debian service/login from its signed cold boot |

Neither a green QEMU oracle nor an exported memory dump qualifies the last
three lines. Do not mutate the frozen SD1 long-run top or its checkpoint format
from this experiment.
