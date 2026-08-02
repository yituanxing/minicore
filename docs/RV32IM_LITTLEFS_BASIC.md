# RV32IM littlefs Basic Stateful Checkpoint

This checkpoint moves AetherCore's software-driven validation from stateless benchmark algorithms to a persistent filesystem state machine.

The workload pins upstream littlefs at:

```text
littlefs-project/littlefs
6cb4e86540eca0d9ba62500a298385c9d863c8be
```

Upstream `lfs.c`, `lfs.h`, `lfs_util.c`, and `lfs_util.h` are fetched at that exact revision. The implementation files are compiled unchanged; their SHA-256 values are retained with every CI artifact.

## Boundary

This is the basic deterministic persistence checkpoint. It verifies normal filesystem evolution across close, unmount, and remount. It does not yet inject power loss, torn programming, failed erase operations, or arbitrary reset cut points.

Those recovery cases belong in a separate checkpoint after this normal-state baseline is frozen.

## Build contract

```text
compiler:             riscv64-unknown-elf-gcc 13.2.0
-march:               rv32im
-mabi:                ilp32
optimization:         -O2
freestanding/static:  yes
```

littlefs is configured with:

```text
LFS_NO_MALLOC
LFS_NO_ASSERT
LFS_NO_DEBUG
LFS_NO_WARN
LFS_NO_ERROR
```

All filesystem, lookahead, read, program, and per-file caches are supplied by static storage. CI retains an ordered ELF symbol table and rejects any linked `malloc`, `calloc`, `realloc`, or `free` symbol.

The repository-owned freestanding C surface was extended only with the contracts required by the pinned upstream source:

- integer format macros from `<inttypes.h>`;
- `strspn` and `strcspn` for path parsing.

The upstream filesystem implementation and CPU RTL were not modified to obtain a pass.

## Deterministic NOR-flash model

The block device is a RAM-backed model with NOR semantics:

```text
block size:       256 bytes
block count:      128
capacity:         32,768 bytes
read size:        16 bytes
program size:     16 bytes
cache size:       64 bytes
lookahead size:   16 bytes
inline files:     disabled
```

The model enforces:

- block and offset bounds;
- read/program alignment;
- erase sets every byte in a block to `0xff`;
- programming may only change bits from `1` to `0`;
- an attempted `0` to `1` transition returns filesystem corruption;
- every read, program, erase, sync, and byte transfer is counted.

The flash array is preserved across `lfs_unmount()` and a fresh `lfs_mount()` while all littlefs objects and caches are cleared. This distinguishes persistent media state from stale in-memory state.

## Stateful operation sequence

The workload performs the following deterministic sequence:

1. erase-initialize the RAM flash;
2. format and mount littlefs;
3. validate reported filesystem geometry and limits;
4. create `/data`;
5. create `/data/alpha.bin` and write 700 deterministic bytes spanning multiple blocks;
6. seek to offset 123 and verify a 211-byte partial read;
7. create `/data/note.txt` and verify its fixed content contract;
8. rename `alpha.bin` to `archive.bin`;
9. truncate `archive.bin` to 513 bytes;
10. append 73 deterministic bytes, producing a final size of 586 bytes;
11. traverse `/data` and require exactly `archive.bin` and `note.txt` with the expected types and sizes;
12. record allocated filesystem block count;
13. unmount and calculate a CRC32 over the complete 32 KiB flash image;
14. clear filesystem objects and all caches while retaining the flash image;
15. remount the same media;
16. stat and fully read both files;
17. compare all 586 bytes of `archive.bin` and all note bytes against their expected contents;
18. traverse the directory again;
19. require the allocated block count to be unchanged;
20. unmount and require the full-media CRC32 to be unchanged by the read-only remount phase.

Each failure path prints a stable `LFS_FAIL stage=<n> err=<code>` record before exiting nonzero. The successful path prints the exact block-device evidence below.

## Frozen binary

```text
binary bytes:   36,140
binary words:    9,035
SHA-256:         0d49f8ac86a400c8e54831e9418c51c0d5093f66edf9bfa2c628a5b38f0e230c
```

CI requires the final symbol table to retain the real filesystem paths exercised by the workload, including:

```text
lfs_format
lfs_mount
lfs_file_write
lfs_file_truncate
lfs_rename
lfs_dir_read
lfs_unmount
```

## Frozen block-device result

```text
read operations:     528
program operations:   26
erase operations:      9
sync operations:      13
read bytes:        14,304
program bytes:      1,392
used blocks:            8
image CRC32:      ccb1e5e1
```

The exact success record is:

```text
LFS_PASS read_ops=528 prog_ops=26 erase_ops=9 sync_ops=13 read_bytes=14304 prog_bytes=1392 used_blocks=8 image_crc32=ccb1e5e1
```

## Exact architectural result

The complete workload runs under deterministic memory backpressure and retirement-by-retirement RV32 NEMU DiffTest:

```text
cycles:             6,253,575
retirements:        4,819,485
DiffTest matches:   4,819,485
stall period:               5
self-check exit:            0
```

Every valid retirement matches the independent reference. Every committed RAM or passive-MMIO Store byte selected by the RTL write mask is also compared, including UART evidence and the final exit Store.

Reference:

```text
OpenXiangShan/NEMU revision:
8601834e4889e6bf3b6113eb5f824ba7689126f5

exact single-step reference SHA-256:
1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
```

## Preserved gates

This checkpoint adds an independent stateful workload without replacing earlier evidence:

```text
frozen RV64 normal-retirement gate:   944,407
RV32I GCC DiffTest:                       585
RV32IM CoreMark DiffTest:             646,301
RV32IM Embench batch 1:               184,185
RV32IM Embench batch 2:             1,144,895
RV32IM littlefs basic:              4,819,485
```

Strict smoke, precise-fault boundaries, deliberate mismatch probes, and all directed/generated RV64I and RV64M regressions remain mandatory.

## Next checkpoint

The next stateful checkpoint should reuse this exact media geometry and frozen normal-state image contract, then introduce deterministic failure injection around selected program, sync, rename, truncate, and metadata-commit boundaries. Each injected run should simulate process reset, remount the retained media, and verify that the filesystem is mountable and resolves to one of the explicitly permitted pre-operation or post-operation states.
