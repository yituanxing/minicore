# G5 Debian D2: exact initramfs progress from archived guest DDR

Date: 2026-11-06. Scope: original real G5 Debian D2 Linux 6.6.143 image,
**not** a Debian PID1 PASS, not a cold build or a calibrated time-to-login.

## Direct observation, no UART guessing

The 900M and 950M input states are the archived GitHub Actions evidence
identified in [the exact-state probe](g5-debian-d2-950m-to-955m-archive-probe-2026-11-06.md).
The same byte-identical-golden-checked archived model continued from 950M
to 955M, then from 955M to 960M and from 960M to 965M. None of these
probes reboots Linux or recompiles the model/toolchain.

Matching `System.map` exposes Linux `init/initramfs.c` globals:

- `this_header` at `ffffffff80c00938` (CPIO parser input boundary);
- `byte_count` at `ffffffff80c00940` (input bytes remaining);
- `state`, `next_header`, `body_len` nearby;
- `__initramfs_start..__initramfs_size` spans **68,467,200 bytes**.

The binary host checkpoint consists of a versioned observer, a DDR header,
256 MiB of DDR and a 24-byte trailer. The exact layout, including the 79,300
byte DDR prefix, is parsed and validated. The Linux Image virtual base
`ffffffff80000000` maps to guest physical `0x80200000`; its first 96 bytes
match the corresponding ELF LOAD bytes. The guest `070701` CPIO prefix
also matches the exact `vmlinux` archive. At each boundary,
**`this_header + byte_count == 68,467,200`**.

| Guest cycles | Retired instructions | CPIO parser bytes | CPIO bytes | Complete entries | Current newc record |
| ---: | ---: | ---: | ---: | ---: | --- |
| 900,000,000 | 231,812,645 | 33,307,476 | 48.65% | 789 / 3,264 | `usr/lib/riscv64-linux-gnu/libc.so.6` |
| 950,000,000 | 244,648,576 | 42,141,773 | 61.55% | 811 / 3,264 | `usr/lib/riscv64-linux-gnu/liblz4.so.1` |
| 955,000,000 | 246,019,304 | 42,466,440 | 62.02% | 815 / 3,264 | `usr/lib/riscv64-linux-gnu/libm.so.6` |
| 960,000,000 | 247,385,906 | 43,075,908 | 62.91% | 820 / 3,264 | `usr/lib/riscv64-linux-gnu/libmount.so.1.1.0` |
| **965,000,000** | **248,850,739** | **43,851,256** | **64.05%** | **823 / 3,264** | **`usr/lib/riscv64-linux-gnu/libnsl.so.1`** |

These 3,264 non-trailer newc entries plus one `TRAILER!!!` contain
67,974,529 bytes of file payload. The parser boundary is at the start
of the **current file payload**, or occasionally between records; its
percentage does **not** include an unknown amount already written within
that file. It is nevertheless direct evidence that the kernel is still
unpacking rootfs, rather than spinning at one unchanging instruction PC.

## Why remaining cycles are not 36% of boot time

From 900M to 965M the input boundary advanced, but there are still
**2,441 non-trailer entries** and 24,615,944 archive bytes left. The
remaining entries include many small `usr/share` and `var/lib` files;
each can cause separate inode, dentry, metadata and memory work.

Observed disjoint throughput windows:

| Interval | Input advance | Cycles per advanced byte |
| --- | ---: | ---: |
| 900M -> 950M | 8,834,297 B | 5.660 |
| 950M -> 955M | 324,667 B | 15.400 |
| 955M -> 960M | 609,468 B | 8.204 |
| 960M -> 965M | 775,348 B | 6.449 |

If the remaining bytes cost only the best/worst *already seen byte rates*,
the CPIO cursor might reach the end at **1.104B–1.344B total guest cycles**.
**This is a byte-only optimistic scenario range, not a lower/upper bound or
a reliable PID1 ETA.** A separate illustrative two-window regression on
both file-count and byte movement predicts about **2.8B total cycles**,
but is unstable on short windows and is *not* a trustworthy forecast either.
Only later checkpoints with a representative sample of the small-file tail
can turn this into a calibrated ETA.

The bounded 960M -> 965M restore took **105.856 seconds of local host
wall-clock time** for 5M guest cycles, using the exact archived program.
That single local sample corresponds to approximately 47.2k guest cycles/s;
do **not** extrapolate it to a different CI runner without measuring the
same hardware/configuration. The original archived CLI rejected a 10M
single-call resume; 5M bounded restores work.

## Reproduce the zero-boot progress report

With the original `vmlinux`, `System.map` and host checkpoint files:

```bash
python3 tools/ci/product_g5_initramfs_progress.py \
  --vmlinux <original-vmlinux> \
  --system-map <original-System.map> \
  <900m.pre.host> <950m.bridge.host> <955m.restored.host> \
  <960m.restored.host> <965m.restored.host>
```

The tool reads only the observer/header, the Linux symbols, a small amount
of guest RAM and the ELF's embedded CPIO; it does **not** copy the whole
256 MiB DDR into memory. The archived golden-replay wrapper now also
generates `initramfs-progress.txt` as part of its original 950M->955M
diagnosis, without any additional guest cycles.

**Decision:** no unattended full Debian CI run until either (a) a much
faster exact-state continuation is proven, or (b) a checkpoint well into
the small-file tail establishes a defensible remaining-work estimate.
