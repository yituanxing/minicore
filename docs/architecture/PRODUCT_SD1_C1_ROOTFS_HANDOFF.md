# Product SD1 C1: reusable Linux prefix, replaceable rootfs

## Goal and non-goals

The expensive OpenSBI + unchanged Linux initialization should run once on
the exact Product RTL configuration. A saved **C1 rootfs boundary** should
then accept different official Debian/Alpine root images and continue at the
**first rootfs consumption**, followed by native distribution PID1. It is not
a D2/D3-specific success marker, and it does not skip the new rootfs mount,
decompression (for initramfs profiles), fsck or subsequent service startup.

The old complete cold-boot path remains a mandatory qualification oracle
for at least one root image and after any incompatible RTL, kernel, firmware,
or peripheral revision. A warm C1 run is a development accelerator, not proof
that cold boot from reset always succeeds.

## Precise boundary: BEFORE rootfs consumption

For SD1, full Debian is a persistent MBR/ext4 disk rather than an embedded
initramfs; the guest will **mount** the new ext4 root, not unpack a Debian
tar during every boot. An initramfs-based profile would place C1 before the
full archive is unpacked. In both cases the essential property is identical:
no guest state derived from the *old* root may survive into the new run.

1. Use a fixed, minimal C1 `rdinit=/init` embedded with the **generic** Linux
   Image, not with Debian/Alpine. Do not include a full distribution in the
   OpenSBI `FW_PAYLOAD_PATH` blob.
2. Finish the common kernel initialization and reach a gated `/init` that
   emits `AETHERCORE_C1_READY`, without mounting or unpacking the full root.
   Confirm `/dev/console`, root device discovery and external storage state.
3. Wait for an explicit host release on serial input. At a fully quiescent
   simulated clock edge, save the entire Verilator RTL, 256MiB DDR, device,
   time/interrupt, UART, SPI SD, COW and observer state using the established
   SD1 savable host, not an architectural register-only QEMU export.
4. Restore the old model and old immutable SD input to verify exact checkpoint
   identities. Then, **only in C1 mode**, check the new image geometry, MBR,
   every sector actually exposed to the guest and the absence of in-flight
   SPI requests and writes before rebinding the backing image.
5. Record both the C1 snapshot identity and the new rootfs SHA, release the
   gated `/init`, mount the new ext4 root and execute its actual `/sbin/init`.
   Judge native getty/login independently from the C1 handoff marker.

Important: Linux may read the partition table or filesystem metadata before
`/init`. The gate **must not assume** that reaching PID1 implies no earlier
rootfs I/O. The SD SPI model's opt-in schema-v2 read ledger records the exact
bytes actually delivered before C1. A new image with changed already-read
sectors, a changed layout, a pending transfer or a COW write fails closed.
If real Linux cannot reach a usable pre-mount gate while keeping the desired
media unchanged, move C1 earlier or add a board-visible SD release boundary;
never bypass the read-ledger check to force a warm success.

## Current pieces and missing integration

- `sim/l32_sd_spi_card.h`: explicit opt-in schema-v2 consumed-sector ledger
  and strict `rebindC1Rootfs()` API. Ordinary strict v1 snapshots are still
  byte-identical. Source-only host tests prove unchanged previously consumed
  bytes are accepted, changed unread bytes become visible after warm resume,
  and changed already-read bytes, partial SPI transactions and forged ledgers
  are rejected. **This is not yet a Linux-level C1 PASS.**
- `sim/product_rv64_checkpoint_sd1_main.cpp`: currently saves complete RTL,
  DDR and card state, but its normal identity and normal restore must remain
  tied to the exact Linux/rootfs/SD SHA. A separate C1 mode is needed to arm
  read tracking at boot, verify the UART gate, restore the old snapshot, call
  the rebind API and resume with a new explicitly pinned rootfs.
- `software/rv64_userspace/product_sd1_c1_init.c` uses the same tiny /init
  for physical Product `/dev/mmcblk0p1` and a separate QEMU-virt `/dev/vda1`
  oracle, accepting only those two explicitly selected kernel `root=` values.
  `tools/ci/product_sd1_c1_qemu_oracle.sh` and
  `product_sd1_c1_qemu_watch.py` provide a bounded **guest** gate: `/init`
  prints READY before mounting the ext4 root, the host sends GO, then the
  guest mounts the new disk root and must reach native getty/login. This
  QEMU oracle is source/contract tested but has **not yet booted the official
  SD1 image**. Its virtio bus is different from Product physical SPI-SD, so
  even a QEMU PASS would not qualify C1 RTL state swapping.
- `tools/ci/rv64_debian_linux_build.sh` and
  `tools/ci/rv64_debian_payload_build.sh`: today both bind the SD1 kernel and
  firmware manifests to the complete rootfs result. A **separate C1 profile**
  must bind to fixed Linux/FW/DTB/mini-init SHA independent of the selected
  distro. The normal SD1 signed-image profile stays unchanged.
- QEMU-to-RTL architectural checkpoint transfer is **not** a prerequisite:
  the current exporter lacks direct injection of all RTL microarchitectural,
  interrupt and peripheral state. Reuse the precise same-model Verilator
  snapshot now; study architectural handoff separately.

## Qualification before expensive Debian longruns

1. Static/unit: strict v1 compatibility, v2 deterministic read ledger,
   same-media restore, changed unread-sector PASS, changed consumed-sector,
   geometry, COW, in-flight, and corrupted-checkpoint REJECT.
2. Bounded real RTL: one exact C1-ready marker, no ext4 root mount beforehand,
   verified quiescence, same-image cold-vs-resume UART/PC/retire differential.
3. Two-root proof: from one **identical C1** resume Debian and Alpine, both
   mount their own new root and reach native init markers. Preserve separate
   SHA and UART logs for each continuation.
4. Long-run gate: full media SHA/provenance, QEMU native init/login oracle,
   Product SPI short gate, recoverable SD1 stage relay, forward cycle/retire
   progress, failure-marker/panic watchdog and a manually authorized job.

Until (2) and (3) pass, report `C1_MEDIA_REBIND_HOST_PASS` only, never
`C1_LINUX_ROOTFS_SWAP_PASS` or a distribution/FPGA qualification.
