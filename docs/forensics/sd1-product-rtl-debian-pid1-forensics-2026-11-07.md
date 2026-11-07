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

## Strict remaining acceptance boundary

**Established:** authentic Product RTL, complete DDR+SD snapshot continuity, real ext4 root mount, PID1 kernel task with user `mm`, RISC-V dynamic ELF execution of `/sbin/init`, and `exe_file` pointing to systemd.

**Not established:** PID1 completing dynamic relocation/initialization, systemd reaching its service loop, child services spawning, login prompt, interactive login, dynamic Debian programs, or persistent rootfs behavior after login. The recorded `login_seen=false`; do not relabel this evidence as `PRODUCT_DEBIAN_PASS` or `SYSTEMD_READY`.

Next zero-impact step: compare more saved DDR snapshots of PID1 `mm`, task state, file mappings and progress; use the exact recovered symbols and verified page tables. Do not substitute different RTL binaries, kernel images, firmware, SD media or shortcut QEMU results for the frozen polling lineage.
