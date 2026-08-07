# Apache NuttX bring-up on AetherCore

This directory records the bounded NuttX bring-up contract.  The upstream OS
and applications repositories are pinned together in `manifest.env`; CI must
never build an unpinned branch tip.

The Zephyr baseline remains frozen at `freeze/zephyr-v3.7.2-z1-z4`.  NuttX work
starts from that proven CPU/SoC state and must continue to preserve the frozen
Zephyr regression.

## Stages

- **N1 — pinned host build:** build Apache NuttX 13.0.0 and the matching apps
  tree from the official `rv-virt:nsh` RV32 configuration, then force the
  architectural profile to `rv32im_zicsr_zifencei`.  A and C must remain
  disabled.  HostFS and semihosting are removed.  N1 is build qualification
  only and does **not** claim that the image boots on AetherCore.
- **N2 — AetherCore boot and console:** add a dedicated AetherCore board/chip
  boundary, link at `0x80000000`, reach `nx_start()`, initialize the native
  polling UART, and obtain an NSH prompt.
- **N3 — timer and scheduler:** connect the machine timer, run timed task
  switching, and pass a bounded `ostest` subset under deterministic memory
  stalls.
- **N4 — external interrupts and driver path:** connect UART RX through PLIC,
  validate claim/complete and ISR return, then exercise file-descriptor based
  console I/O.

## Protected userspace stages

The protected line is separate from the frozen N1-N4 flat build.  NSH here is
NuttX's own **NuttShell**, not Linux `/bin/sh` or BusyBox `ash`.

- **P1 — separated images:** start from the pinned upstream `rv-virt:pnsh`
  configuration, build an M-mode `nuttx` kernel and a distinct U-mode
  `nuttx_user` image containing NSH and `hello`, and combine their load images
  without changing their ELF boundaries.  User flash is
  `0x80040000..0x8007ffff` (RX) and user RAM is
  `0x80200000..0x802fffff` (RW).  PMP entries 0 and 1 are configured directly;
  the platform must never scan beyond AetherCore's four implemented entries.
- **P2 — real OS-backed U-mode execution:** boot a dedicated
  RV32IMU + PMP4 + timer + PLIC/UART simulation, wait for the first real
  `nsh> ` prompt, inject `hello`, require `Hello, World!!`, and return to a
  second prompt.  Qualification is not based on UART text alone: the runner
  snapshots counters at the first prompt and requires the command phase itself
  to add user-text retirements, ECALL-from-U traps (`mcause=8`), and MRET
  transitions.  Both `stall-period=0` and `3` must pass.

P1/P2 deliberately follow the real NuttX 13.0.0 **pure protected/PMP** model.
`CONFIG_LIB_SYSCALL` and `CONFIG_RISCV_PERCPU_SCRATCH` are enabled, while
`CONFIG_ARCH_ADDRENV`, S-mode, and MMU use remain disabled.  In this upstream
configuration NuttX does **not** allocate a separate per-process kernel stack:
`task_init()` only calls the RISC-V kernel-stack allocator when
`CONFIG_ARCH_ADDRENV && CONFIG_ARCH_KERNEL_STACK` are both active.  Therefore
P1/P2 system-call handling still uses the caller's user stack.  Forcing only
`CONFIG_ARCH_KERNEL_STACK` would not create the stack and would be a false
security claim.  A dedicated kernel-stack / address-environment port is a later
hardening milestone after the first genuine OS-backed U-mode path is qualified.

This means P1/P2 are intended to prove the privilege transition, PMP user
code/data access boundary, ECALL dispatch/return path, timer/interrupt
coexistence, and real U-mode application execution.  They do **not** yet claim
robust isolation from a malicious user that deliberately corrupts the syscall
stack while the kernel is servicing that call.

P1/P2 use the focused `NuttX Protected Userspace` self-hosted workflow.  The
frozen N1-N4 workflow remains byte-identical and does not run on U-mode branch
pushes.  The P2 cycle count is only an upper bound: a successful run terminates
immediately after the second prompt and complete architectural evidence.

**Qualification status:** P1/P2 are implemented but not yet frozen.  No success
claim may be made until `umode/nuttx-protected` is green and the uploaded P1/P2
artifacts have been audited.

## Frozen AetherCore platform contract

- ISA: RV32IM + Zicsr + Zifencei; no A, C, F, D, or V.
- RAM: `0x80000000 .. 0x83fffff7` (`0x03fffff8` bytes).
- UART TX: `0x10000000`.
- UART RX block: `0x10000100`.
- simulation exit: `0x10000008`.
- CLINT-compatible timer: `mtimecmp=0x02004000`, `mtime=0x0200bff8`.
- PLIC base: `0x0c000000`; source 1 is UART RX.
- The PLIC profile has one M-mode 32-bit enable word at offset `0x2000`.
  Software must not access the wider QEMU enable word at offset `0x2004`.

## N1-N4 frozen qualification

The NuttX 13.0.0 bring-up is frozen as `nuttx-13.0.0-aethercore-n4-uart-rx-plic-nsh-v1`.
Its qualification basis is commit
`6326c621c9307a5785a58597dd382ae5ca506d60`, workflow run `31113948586`, and
artifact `aethercore-nuttx-n4-31113948586-1` with archive digest
`sha256:7cca849c1086195332c57986130607708d3a08b61b8f0ff2542dd46f4c9e1216`.

The single bounded self-hosted stage passed N1, N2, N3, and N4 in order.  N4
injected `echo N4-IRQ-PASS` through UART RX at cycle 8,000,000 with 1,000-cycle
byte gaps.  Both deterministic memory profiles (`stall-period=0` and `3`)
produced the command output and returned to a second `nsh>` prompt before the
12,000,000-cycle bound.  This proves the UART RX device interrupt, PLIC
claim/dispatch/complete path, ISR return, serial receive buffering, and NSH
file-descriptor console path together.

Frozen evidence hashes:

- NuttX ELF: `a8d1d39d57b280673878fd09bf3b74d20445b4e36573503aa6583ec837b736e1`
- flat image: `21c186e81d94147e1dd30ac96760caee8481e8b54a94d3133b2fdc5a67bf7502`
- resolved config: `e666ecaae055d2d373cf42a1685ef88c252c17c23f6679afd1bccf25d44e0677`
- each N4 boot log: `a805d3ca2152f52e85282f22d048023cb17eb0c300f2a5f01e299f250e256baa`
- cached AetherCore simulator: `c7f322338910104bf12914db875dc9a56853fe5e45a70d1996561e9aef8ba9d6`

The ordinary NuttX workflow intentionally remains one bounded self-hosted job
with persistent source, host-tool, and simulator caches.  Fast and Full gates
remain outside ordinary NuttX iterations.  Any change to the pinned sources,
NuttX overlays, IRQ/timer/UART contracts, simulator, or relevant RTL requires a
new N1-N4 qualification before the freeze reference may move.

Every stage is fail-closed and stores configuration, build logs, ELF metadata,
image hashes, and the exact upstream commit IDs used for the result.
