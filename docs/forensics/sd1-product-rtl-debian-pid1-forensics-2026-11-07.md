# Product RTL SD1 Debian PID1: read-only checkpoint forensics (2026-11-07)

> **Verdict:** authentic Product RTL has created Linux PID 1 and successfully installed a dynamically linked RISC-V userspace ELF for `/sbin/init` (identified as `/usr/lib/systemd/systemd` by independent executable-dentry walk). **This is not a Debian-login or systemd-ready PASS.**

## Immutable evidence and scope

| Evidence | Official run / artifact | ZIP SHA-256 |
| --- | --- | --- |
| 1992M full RTL+DDR+SD snapshot | [run #42362515087](https://github.com/yituanxing/minicore/actions/runs/42362515087), artifact `#13570461840` | `e75a09c4cecc12e31109f0a0c5799837f4316fdce12c9759be4a7eb9bb829ef8` |
| 2032M full RTL+DDR+SD snapshot | [same run](https://github.com/yituanxing/minicore/actions/runs/42362515087), artifact `#13570575831` | `b78d8eb3ef1acd3b8200aebd7b70d91696a5c2d6829091fca5f1290461d021a5` |
| Exact recovered `vmlinux` + `System.map` | [run #42362566520](https://github.com/yituanxing/minicore/actions/runs/42362566520), artifact `#13570825162` | `0803914dd835ecfcf7b02278f95781366c6d0a702e258b342fe2ec6a26775b64` |

The 2032M artifact ZIP and its inner data were downloaded and independently rehashed. `.polling-ready`: `schema=AETSDCP1`, `boot_cycles=2032000000`, `snapshot_host_sha256=3cbbe38303f7c797cc95ab3b697378f11d3b3212e4ae18eae016aa62618c6d48`, `snapshot_rtl_sha256=1a0aa3ae8f262abd5aab97f403869b9dd438507f1f50c361de63a750da3b7294`, `card_progress_sha256=c1412f021c9ec582c082d30a36f4d060184422758333af6a2904500050b5fcb0`: all match actual bytes. The 256 MiB guest DDR starts at byte offset **13,742** inside `persistent.pre.host`.

The separate recovery job recompiled the fixed Linux 6.6.143 GPIO-CS kernel and passed a **non-circular** hard gate against the real RTL checkpoint's frozen `linux_image_sha256=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393`. Its recovered `vmlinux` SHA is `a2afd87e9963dced857a200ea83a2b7244cf77ae321828c6118d5737978e8d14` and `System.map` SHA `117c099b65e37c891e5f3383ae36686967577dcb7be862a7f6066d9bf69061da` (also independently rehashed). This is the exact Image identity used by this polling RTL relay, not a different QEMU/kernel build.

## Direct Linux task and PID1 proof (2032M DDR)

All addresses below were read **offline** from the saved DDR; no RTL was rerun and no guest memory or SD bytes were changed.

1. `System.map`: `init_task=0xffffffff8140bac0`, `init_mm=0xffffffff8148e1c0`, `swapper_pg_dir=0xffffffff814fe000`. Walking the recovered Sv39 kernel page tables resolves `init_task` to physical `0x8160bac0` (DDR offset `0x0160bac0`). The static `task_struct.comm` bytes at offset `+0x628` are `swapper/0`.
2. Recovered task-list node offset is `task_struct.tasks +0x368`. Both `next->prev` and `prev->next` were checked at **every** link: 41 live dynamic task records, cycle closing to the static `init_task`, **zero broken reciprocal links**. This is a real kernel task list, not a string scan.
3. The first live task is `0xffffffd801880000`; `comm +0x628 = init`, `pid +0x430 = 1`, `tgid +0x434 = 1`. The next task is `kthreadd`, PID/TGID 2. The PID/TGID offsets also agree with the sequence 0,1,2,3,4,5,6,7 on the first eight tasks.
4. PID1 has **non-null** `mm` and `active_mm` at task offsets `+0x3b8/+0x3c0`, both `0xffffffd802460000`; `kthreadd` has null `mm`. The corresponding `mm_struct.pgd +0x70` is `0xffffffd802452000`, whose physical address is `0x82452000`, distinct from the kernel `swapper_pg_dir`. This is a genuine installed userspace address space.
5. Through PID1's own Sv39 PGD, `mm->arg_start/end` (`mm_struct +0x168/+0x170`) contain exactly `/sbin/init\0`; `env_start/end` (`+0x178/+0x180`) contain `HOME=/\0TERM=linux\0`. At mapped user VA `0x2ab9152000` the DDR contains an ELF64 header with RISC-V machine `0xf3`, type `ET_DYN`; its `PT_INTERP` names `/lib/ld-linux-riscv64-lp64d.so.1`. This is direct evidence of successful userspace ELF installation for PID1, beyond the kernel's earlier `Run /sbin/init` print.
6. Independent 1992M dentry forensics followed PID1 `mm->exe_file` (identified using `get_mm_exe_file` offset `+0x480`) to a regular executable path **`/usr/lib/systemd/systemd`**, consistent with Debian `/sbin/init` resolving to systemd. This is evidence of the executable identity, **not** evidence that systemd finished initialization.

## Activity, user PC, and fault caveats

- The 2012M observer had retired PCs in userspace around `0x3f88c914ac`; the 2032M PID1 user PGD translates `0x3f88c914ac` to physical `0x80ddf4ac` with non-zero RISC-V instruction bytes. No other userspace task appears in the 2032M global task list. This supports userspace execution but does not show every instruction or precise scheduling history.
- A later observer reported RISC-V store page-fault cause 15 at `0x3f88c913e8`, fault address `0x3f883966c0`. At **2032M**, that address maps through PID1's user PGD to physical `0x80c296c0` with PTE low flags `0x53` (valid/readable/user/accessed, not writable). This is **consistent with a normal COW write fault**, not enough to prove either successful COW resolution or a bug. Later-state attribution requires a same-cycle snapshot.
- The 2032M interval itself retired 4,652,464 instructions, read 91 SD sectors, logged no new UART bytes and `guest_failure=null`. Root ext4 was mounted. These are liveness counters, not an init-completion certificate.

## Follow-up: four authenticated DDR checkpoints (1992M–2132M)

All four full Product RTL-DDR-SD ZIPs were separately downloaded and rehashed. A read-only Sv39 task-list/PGD walk using the same exact recovered symbols produced these results:

| Cycles | Artifact / ZIP SHA-256 | Tasks (excluding swapper/0) | PID1 `__state` | PID1 user PTE page equivalents | SD cumulative reads |
| --- | --- | ---: | --- | ---: | ---: |
| 1992M | `#13570461840` / `e75a09c4cecc12e31109f0a0c5799837f4316fdce12c9759be4a7eb9bb829ef8` | 41 | `0x0` (`TASK_RUNNING`) | 751 | 11,929 |
| 2032M | `#13570575831` / `b78d8eb3ef1acd3b8200aebd7b70d91696a5c2d6829091fca5f1290461d021a5` | 41 | `0x0` (`TASK_RUNNING`) | 789 | 12,052 |
| 2072M | `#13570398096` / `02d6cee25ea9745a62d63d7c039e15ca35753613f56dfe7b982581b804eccfd1` | 41 | `0x0` (`TASK_RUNNING`) | 790 | 12,358 |
| 2132M | `#13571010718` / `b05f43d16811b094c4a64e56c7ed6ca2ab34de6889d32b91d661ae6581ca3f93` | 41 | `0x0` (`TASK_RUNNING`) | 803 | 12,807 |

Each reciprocal task list closed without broken pointers. In **all four**, PID1 was the only task with a non-null user `mm` (same PID/TGID=1, `comm=init`, `/sbin/init` argv, userspace PGD and executable `exe_file`); no systemd child/service tasks appeared. Importantly, the task base `+0x0` value `0xa` is RISC-V `thread_info.flags`, **not** process `__state`: `CONFIG_THREAD_INFO_IN_TASK` places the 48-byte `thread_info` first, with 32-bit `task_struct.__state` at `+0x30`. PID1 `__state=TASK_RUNNING` at every sampled checkpoint (runnable, not proof of continuous on-CPU execution).

PID1 gained **52 user-mapped 4 KiB page equivalents** from 1992M to 2132M, while SD reads increased by **878 sectors**. At user fault VA `0x3f883966c0`, leaf PTE progressed `0x2030a453` at 1992M/2032M (read-only, PA `0x80c29000`) → `0x21fa94d7` at 2072M (writable, new PA `0x87ea5000`) → `0x21fa94d3` at 2132M (same new PA, write-disabled). This is direct evidence of page replacement and subsequent permission changes, **consistent with COW fault handling**; it does not establish the exact syscall or systemd readiness. PID1 task/MM bytes changed across snapshots even though its identity, argv and top-level user PGD pointer stayed the same.

**Revised conclusion:** PID1 is not shown to be permanently blocked or simply looping on an unresolved COW fault. Its user mappings evolved and its sampled task state is runnable. It also has not spawned services or produced a login prompt by 2132M, so complete Debian remains **NOT PASS**.
## Follow-up: verified 2352M and 2372M Product RTL checkpoints

Both ZIP archives were downloaded again and independently SHA-256 hashed (not merely trusted from artifact metadata). The same exact recovered 6.6.143 `vmlinux`/`System.map` and read-only Sv39 DDR page walk were used:

| Cycles | Authenticated artifact / ZIP SHA-256 | PID1 user page equivalents | Dynamic tasks | SD cumulative reads |
| --- | --- | ---: | ---: | ---: |
| 2352M | `#13571003948` / `4c2edbda7dc07713afdd29249c8c6ca78c0e9d38c9a20130f9ca923915854f91` | 936 | 41 | 14,344 |
| 2372M | `#13571054869` / `1eaea527262304d2790c6f5d218473aae109c2d6cca6faeb386adf48ad979eb4` | 936 | 41 | 14,502 |

Both ZIPs' `.polling-ready` Host/RTL/card-progress hashes match independently calculated bytes. The 2372M `source_host_sha256=6f71c51bc2961c77ad6b5c38806fa7cabfdf4b7f0ca41eb11ed20a256da02ca9` exactly matches the authenticated 2352M `snapshot_host_sha256`. The frozen model/firmware/DTB/Linux Image/SD/rootfs hashes are unchanged.

At both DDR checkpoints the reciprocal task list closes with 41 dynamic tasks. PID1 remains the sole user-`mm` task, `pid=tgid=1`, `comm=init`, argv `/sbin/init`, non-null `mm`, same user PGD and executable file pointer, `__state=0` (`TASK_RUNNING`). **No user service child and no login** were observed. The PID1 user-mapped 4 KiB page-equivalent count stayed at **936** for these two samples, and its `mm`/top-level PGD bytes were unchanged; the PID1 `task_struct` SHA did change. This plateau does not imply no execution or no further guest progress.

The authentic 2352M→2372M 20M-cycle interval retired **4,697,935 instructions**, read **158 SD sectors** (+80,896 bytes), took **117 host seconds** (~0.171 MHz simulation throughput), had 200 interrupts, 100 exceptions and no new UART bytes; `guest_failure=null`, `login_seen=false`. Its 2372M final-16 retired PC sample resolves using the **exact** vmlinux symbols to kernel `crc_itu_t` (`0xffffffff803fed90`), while the earlier 2352M sample resolves to `tiny_spi_txrx_bufs` (`spi-oc-tiny.c`). These one-instant samples are compatible with active SD/SPI/CRC work and suggest an I/O bottleneck to investigate; **they do not prove the whole interval was spent in those functions, systemd completion, or an FPGA Fmax**.

## Strict remaining acceptance boundary

**Established:** authentic Product RTL, complete DDR+SD snapshot continuity, real ext4 root mount, PID1 kernel task with user `mm`, RISC-V dynamic ELF execution of `/sbin/init`, and `exe_file` pointing to systemd.

**Not established:** PID1 completing dynamic relocation/initialization, systemd reaching its service loop, child services spawning, login prompt, interactive login, dynamic Debian programs, or persistent rootfs behavior after login. The recorded `login_seen=false`; do not relabel this evidence as `PRODUCT_DEBIAN_PASS` or `SYSTEMD_READY`.

Next zero-impact step: compare more saved DDR snapshots of PID1 `mm`, task state, file mappings and progress; use the exact recovered symbols and verified page tables. Do not substitute different RTL binaries, kernel images, firmware, SD media or shortcut QEMU results for the frozen polling lineage.
