# Product persistent SD-root boot contract (G5-SD1)

## Purpose and stage naming

**G5-SD1** is a storage substage after G5-D3. It is *not* the Product
architecture roadmap's **D4 performance optimization** phase. The roadmap
also uses D3 for *FPGA full distribution* while G5-D3 currently denotes a
real-service initramfs baseline. Explicit `sd1` avoids silently redefining
either established milestone. SD1 means one narrow, auditable boot data path:
**real native Debian RV64GC/LP64D root filesystem on persistent SD media**.

Unlike G5 D3, the distribution's binaries, libraries, package database,
`/sbin/init`, users, services and logs are **not linked into Linux as an
initramfs**. A board loader provides OpenSBI `fw_payload.bin` (including the
Linux kernel Image), and its correctly paired DTB, into DDR. Linux owns the
SPI/MMC path, waits for `/dev/mmcblk0p1`, mounts ext4 as `/`, then directly
starts the **unmodified Debian `/sbin/init`**.

This is an implementation contract and a source-level wiring milestone. It
does not claim board timing closure, an actual SD card transfer or a Debian
login has passed. No long test belongs to this patch series.

## Physical and software identity

| Responsibility | Concrete contract |
| --- | --- |
| Product CPU | Small/Expanded single-lane RV64GC/LP64D, Sv39, OpenSBI M/S/U |
| Physical UART-upload FPGA elaboration | `aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1Rv64gc` (real 1 Mbaud serial FW+DTB source, CRC/credit handshake, cold boot, DDR address remap, CPU and SD pins) |
| External-stream FPGA elaboration | `aethercore.soc.ElaborateProductFpgaDdrLocalWindowSpiSd1Rv64gc` (same DDR-local window with an independent board-provided byte source) |
| Lower-level debugging boundaries | `ElaborateProductFpgaColdBootDdrStreamSpiSd1PinsRv64gc` and `ElaborateProductFpgaColdBootDdrMuxSpiSd1PinsRv64gc` remain isolated ABI-compatible RTL entrypoints |
| DDR | Product physical `0x80000000..0x8fffffff` remapped to DDR controller local `0..0x0fffffff`; board supplies 50 MHz clock-compatible DDR controller/PHY, PLL and wiring |
| SPI/SD | `ProductSoCBoardProfiles.persistentSpiSd`, OpenCores tiny SPI + Altera PIO GPIO CS, PLIC11 |
| DTB | `EmitAetherSoCDts --product --spi-sd --isa rv64imafdc_zicsr_zifencei` with the same SPI clock and 52 PLIC sources as actual Product RTL |
| SD image | Raw SDHC-compatible file, DOS/MBR, one Linux `0x83` partition starting at LBA2048 |
| Root filesystem | Real Debian riscv64 ext4 on `/dev/mmcblk0p1`, writable and persistent |
| SD simulator input | `AETHERCORE_PRODUCT_SD_IMAGE` points to the complete raw `.img`, not a partition-only `.ext4` |
| Kernel config | Built-in SPI/OC_TINY, GPIO_ALTERA, MMC_SPI, MMC_BLOCK, MSDOS_PARTITION, EXT4_FS, devtmpfs and native userspace facilities |
| Bootargs | `earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200 root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init` |

The default hardware UART and CPU timers remain identical to D1/D3. The
physical SD path is PIO-only and does not create an additional DMA/DDR writer.
Correctness, not raw SPI throughput, is the initial goal.

## Offline artifact construction

Do **not** execute these commands during a source-only architecture round;
they document the later explicit, separate construction of a real SD1 image.

```bash
# The user can prepare a pinned official Debian riscv64 full-root supplier
# with tools/ci/rv64_debian_sd1_official_full_rootfs_prepare.sh, then import
# its exact signed-source provenance, native init/getty/login and tar SHA.
# See PRODUCT_G5_SD1_OFFICIAL_ROOTFS_SUPPLIER.md for required source pins.
export RV64_DEBIAN_STAGE=sd1
export RV64_DEBIAN_FULL_ROOTFS_ARCHIVE=/path/to/debian-riscv64-full.tar
export RV64_DEBIAN_FULL_ROOTFS_SHA256=...64-character-hex-digest...

# Existing exact pinned GCC, Linux baseline, and OpenSBI prerequisites apply.
bash tools/ci/rv64_debian_rootfs_build.sh
bash tools/ci/rv64_debian_sd1_sd_image_build.sh
bash tools/ci/rv64_debian_linux_build.sh
bash tools/ci/rv64_debian_payload_build.sh
```

The SD1 rootfs builder preserves the D3 supplier/ELF/link-safety checks and
real Debian service setup. It **does not** install D3's shell `/init` wrapper,
and does **not** enforce the obsolete 96 MiB embedded-rootfs allowance. It
continues to reject missing native Debian `/sbin/init`, `agetty`, `login`,
`dpkg`, a foreign ELF ABI or a changed supplied tar SHA256.

The SD-media builder (`rv64_debian_sd1_sd_image_build.sh`) verifies the
staged derived-root tree digest (file bytes, paths, modes, owners, links and
special-file identity), then produces:

| Artifact | Purpose |
| --- | --- |
| `build/rv64-debian-sd1-sd/sd-rootfs.img` | Raw SD card bytes for physical programming or a later Product SD-card model |
| `build/rv64-debian-sd1-sd/partition-rootfs.ext4` | The same ext4 filesystem before inserting at LBA2048 |
| `build/rv64-debian-sd1-sd/result.txt` | Rootfs/input identity, full derived tree SHA256, image SHA256, size, partition LBA and FS UUID |
| `build/rv64-debian-boot/result.txt` | Builder artifact evidence, OpenSBI/Image/DTB and SD1 bootargs (not runtime proof) |

The disk-size policy reserves at least 50% size headroom plus 128 MiB for
ext4 metadata and Debian services, with a minimum card size of 512 MiB. An
explicit `RV64_SD1_SD_IMAGE_MIB` may enlarge it; undersizing fails **before
RTL**. The image is a regular sparse file; no `losetup`, mount or foreign
executable is required for offline population. The SD simulator must read a
copy-on-write view so a diagnostic boot does not corrupt this pinned source.

The derived staged rootfs replaces an obsolete supplier `/etc/fstab` root
device entry with the exact `/dev/mmcblk0p1` ext4 contract and preserves any
original as `evidence/supplier-fstab`. It does not alter the supplier archive
or invent new native Debian binaries.

The actual built SD image SHA256 is a build-time input identity. Later writes
are intentional and expected on persistent media, and should not be compared
to its original SHA without selecting a fresh immutable baseline.

## Why this fixes the old memory problem

The earlier D3 path put all Debian packages directly in
`CONFIG_INITRAMFS_SOURCE`. Product's 256 MiB RAM and the fixed
`0x87f00000` DTB location then limited the full OpenSBI+Linux+rootfs blob to
under ~127 MiB. Those images often cannot hold a useful complete Debian.

For SD1, `rv64_debian_linux_build.sh` enforces
`CONFIG_INITRAMFS_SOURCE=""`, keeps all requisite SPI/MMC/ext4 drivers
**built in**, and `rv64_debian_payload_build.sh` emits the exact `rootwait`
bootargs and SPI device tree. The G4/OpenSBI payload builder still checks
that the **kernel-only** firmware does not overlap the FDT. Debian's package
files now occupy SD space, independently of the DDR memory window; the
running kernel and processes **still** must fit in available physical RAM.

No onboard SD ROM boot is implied: the board DDR loader must first stream
the OpenSBI `fw_payload.bin` and DTB into their pinned DDR addresses, drain
the loader writes, then surrender the DDR AXI ownership to the CPU safely.
The board-neutral UART source and finite-window upload protocol are implemented
in `AetherProductUartFirmwareUpload`, with a pinned-manifest host uploader
`tools/ci/product_sd1_uart_firmware_upload.py`. They close the **logical**
external byte-source gap, not the electrical USB-UART wiring. See
`PRODUCT_SD1_PHYSICAL_UART_UPLOAD.md` for the required host READY/header,
CRC, AXI-drained window credit and post-release 115200 baud handoff.
The SPI SD card supplies the Linux root filesystem *after* OpenSBI and Linux
are running. The SD1 storage contract is an input to the broader G5 D4
**FPGA board acceptance milestone**, not completion of the independently
named architecture-roadmap D4 **performance** stage.

## Integration gates before a single expensive long run

1. Check that the **same source commit** selects the SD1 DDR mux + SPI-SD
   FPGA top **and** DTS `--spi-sd`; the old RAM-only Product sim/FPGA top
   cannot run an SD-root kernel.
2. Assert actual PLL/CPU clock matches DTB SPI `clock-frequency`, SD IO
   voltage and pull-up, DDR geometry, FDT/firmware physical addresses.
3. Confirm byte-exact SD image geometry: 512-byte sectors, MBR signature
   `55aa`, partition #1 type `0x83`, first LBA2048, ext4 contents and SHA.
4. Check SD1 Linux build's resolved Kconfig, missing-root initramfs exclusion,
   native ELF architecture and all supplied artifact SHA256 values **before
   RTL generation**. The current G5 D1/D2/D3 workflow is not an SD1 run.
5. Before one opt-in Linux long run, qualify the Product SD-card model/MMIO
   path and simulator version/settle choice with an independently bounded
   short differential check. Product 5.054/adaptive throughput and checkpoint
   benefits are not proven by V2 history alone.
6. On physical FPGA, qualify DDR loader handoff, pin timing and SD transfer
   independent of a Debian boot, then capture OpenSBI→Linux→`mmcblk0p1`
   mount→native Debian init→getty/login evidence for the exact board/image.

Remaining physical dependencies are the actual board's DDR PHY/PLL and pin/clock
constraints, **50 MHz DDR UI/Product clock compatibility or a proper CDC**,
vendor AXI ID/data-width adaptation if needed, electrical USB-UART connection,
and SD socket voltage/pullups. The UART upload controller and Debian supplier
are real source-level implementations, not evidence of a finished bitstream,
real hardware boot or a successfully authenticated Debian login.
