# FAST-RAM full Debian experiment: separate 512-MiB Product profile

Status: **design/preflight only; NO RTL, PID1 or Debian login PASS**.
This work must not modify PR #379, signed SD1 media, the Product SPI-SD top,
or any existing 256-MiB checkpoint. RAM-only simulation is an additional
diagnostic for CPU/LSU/PTW throughput, not a replacement for SPI-SD acceptance.

## Why a plain SD-to-RAM file copy is insufficient

The official SD1 riscv64 Debian rootfs is approximately 186,038,556 bytes.
`rv64_debian_rootfs_build.sh` already reserves 64 MiB for the kernel, 64 MiB
for services and 32 MiB for staging for a D3 embedded initramfs: the original
256-MiB Product DDR has only **96 MiB** of rootfs allowance. This is a genuine
memory-capacity limit; compressing the transfer image does not remove the
uncompressed guest runtime allocation.

There is an *independent* critical layout constraint. The frozen OpenSBI
`RV64_OPENSBI_FDT_ADDR=0x87f00000` is only 127 MiB from DDR base
`0x80000000`. The current payload builder intentionally rejects a firmware
image reaching that boundary. Embedding the full 186-MB cpio inside the Linux
payload will cross it. **Enlarging host RAM without relocating the FDT would
create an overlapping firmware/FDT image.** The original 256-MiB/SD1 boot
layout must remain frozen.

## Required isolated 512-MiB contract (not yet implemented)

1. **Product board address map.** Add an explicitly selected RAM-only map
   `0x80000000..0x9fffffff` in `AetherCoreProductUnifiedMemorySoC.scala`,
   retaining the same `smallOpenSbiDistroTarget` CPU/LSU/PTW and the default
   256-MiB map unchanged. Reject 512-MiB selection when SPI-SD is enabled.
2. **Distinct RTL top.** Add a separately named
   `AetherCoreProductDebianFastRam512RV64SimTop` so `--savable` models and
   generated headers cannot be confused with existing G5 or SD1 builds.
3. **Host DDR instance.** Keep `l32_opensbi_runtime.h::kRamSize` at 256 MiB
   by default. Use a per-instance allowed size for the FAST-RAM runner and
   require that checkpoint restore's byte count matches *that exact model*,
   not merely a caller-controlled file length. The new host/model fingerprint
   must never restore or reseed existing G4/G5/SD1 snapshots.
4. **DTB and FDT placement.** Extend `EmitAetherSoCDts` with an explicit
   RAM-only 512-MiB option and verify the `/memory/reg` aperture matches RTL
   and Host. In the separate 512-MiB payload build, choose an FDT address
   **above the entire measured** `fw_payload.bin` while inside that aperture;
   e.g. `0x9f000000` is a *candidate*, not an accepted constant until the
   measured payload and Linux allocations have been checked. Do not rewrite
   the frozen `opensbi_first_exec.env` value for SD1.
5. **Debian D3 payload opt-in.** Let only the separate RAM-only D3 profile
   adopt a 512-MiB memory allowance, using the existing signed Debian source
   archive and deriving new SHA-pinned rootfs/kernel/OpenSBI outputs. Require
   that `rv64_minimal_init_payload_build.sh` checks the measured firmware
   extent is strictly below its profile-specific FDT address.
6. **Run result.** Label this line `FAST_RAM_PRODUCT_CPU_DIAG_NOT_SD1_PASS`.
   Full DDR/model SHA, native Linux PID1, ordered serial service/login and
   wall-clock/cycle/retirement data are separate gates. A green build, Linux
   banner, or QEMU login alone is insufficient.

## Before any expensive runner launch

- Obtain real Linux and OpenSBI toolchains, regenerate both RTL and DTB from
  the **same** 512-MiB map, build an exact firmware, then verify its end is
  below the configured FDT and within DDR. This workspace currently lacks
  Scala/mill, Verilator, GSIM and the RISC-V cross compiler, so this validation
  is **not** claimed here.
- Negative tests must reject 512-MiB on SPI-SD, mismatched Host/RTL/DTB RAM
  sizes, old 256-MiB checkpoint restores, firmware-FDT overlap, unauthenticated
  Debian rootfs input, and a missing native service/login milestone.
- Start with a bounded 30M-cycle CPU equivalence test and real artifact;
  only after that should a longer RAM-only systemd/login boot be considered.

Use `tools/ci/product_fast_ram_debian_budget.py --rootfs-result PATH` as a
read-only first gate. Its `recommended_power_of_two_ram_mib` is a sizing
**proposal**, not proof that a matching RTL/Host/DTB model exists.
