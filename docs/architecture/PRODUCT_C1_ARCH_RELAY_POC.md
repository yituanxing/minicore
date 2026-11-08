# Product C1 cross-simulator architectural relay: isolated experiment

This experiment is **not** the existing Product SD1 C1 same-RTL checkpoint
(`c1-save/advance/release`), and it does **not** modify or replace the official
signed SD1 cold RTL service/login long run.

## First contract gate, implemented

`tools/ci/product_c1_arch_relay/preflight.py` checks the existing optional
G5 QEMU architectural export at `run_init_process`:

- one hart, S-mode capture, 0x80000000-based exact 256 MiB physical RAM;
- actual SHA-256 of RAM, exact fw_payload, DTB, Linux Image and System.map;
- breakpoint PC vs captured PC, x0=0 and missing GPR/CSR evidence;
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
`PRODUCT_C1_ARCH_SOURCE_REVIEWED_NOT_RTL_RESUMED`. It **always** writes
`rtl_handoff_pass=false`. It is not a state converter or an RTL injector.
`arch_registers_complete=true` does not assert device equivalence or successful
restoration. The QEMU diagnostic source itself is not yet qualified on a real
QEMU runner in this environment.

## Next implementable experiment

1. Use existing G3/G4 real Product OpenSBI and Linux fixtures for a bounded
   comparison. Do **not** try to import QEMU virtio state directly into SD1 SPI.
2. Add `sim/product_rv64_arch_handoff_main.cpp` and an M-mode restore shim to
   preload RAM and rebuild GPR/allowed CSR state by retired instructions.
   Product currently has no architectural debug-load port. The boot ROM jumps
   to DDR at 0x80000000; a host-owned shim is possible but is **not** proven.
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
