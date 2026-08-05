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

## Frozen AetherCore platform contract

- ISA: RV32IM + Zicsr + Zifencei; no A, C, F, D, or V.
- RAM: `0x80000000 .. 0x83fffff7` (`0x03fffff8` bytes).
- UART TX: `0x10000000`.
- UART RX block: `0x10000100`.
- simulation exit: `0x10000008`.
- CLINT-compatible timer: `mtimecmp=0x02004000`, `mtime=0x0200bff8`.
- PLIC base: `0x0c000000`; source 1 is UART RX.

Every stage is fail-closed and stores configuration, build logs, ELF metadata,
image hashes, and the exact upstream commit IDs used for the result.
