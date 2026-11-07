# Product SD1 polling RTL: 1152M checkpoint and CPU/SPI audit (2026-11-07)

## Scope and evidence limits

This document records authentic Product RTL warm-relay evidence for PR #379. It is NOT a Debian login or systemd success claim. Native QEMU results are a separate oracle.
Cycle suffix M denotes one million simulated Product RTL cycles. All snapshots below belong to the polling firmware and fixed GPIO-CS Linux Image identity; they must never be mixed with the prior IRQ-based checkpoint family.

## Checkpoint provenance

- Official 752M-to-952M run: https://github.com/yituanxing/minicore/actions/runs/42344795877 (10/10 execute/upload SUCCESS).
- Official 952M-to-1152M run: https://github.com/yituanxing/minicore/actions/runs/42346556368 (10/10 execute/upload SUCCESS).
- Final 1152M artifact: product-sd1-spi-polling-1152m-42346556368, GitHub artifact ID 13564576032, ZIP SHA-256 9c4b2cb7eb5d191ad1ddcb4ce09a165c04a499ba2c3278cf63bb398c6856f283, not expired at inspection.
- 1152M .polling-ready: boot_cycles=1152000000, qualification=PRODUCT_SD1_SPI_POLLING_WARM_NOT_DEBIAN_PASS.
- 1152M full 256MiB Host snapshot SHA-256 f27ba4fd796f3f377a015f38bf4273042181f1cb01a879eb83bd86be6e31936a (recomputed from extracted bytes).
- 1152M RTL snapshot SHA-256 5b11447f1124aff84ad939392ffc0fbf9afc83a9bb45c17393696436230ad4c0 (recomputed).
- 1132M Host SHA-256 fb399904ce3bc5928e761c4963423f10a38263cd47471b751bbf59944cbe121a, exactly equal to the 1152M source_host_sha256 in .polling-ready.
- Fixed model 5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc; polling OpenSBI 4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be; DTB 72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c; official SD 6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7; Linux Image 522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393.
- The succeeding 1152M-to-1352M ten-window workflow was committed as 7d0213f00f6a843e5a9316235e2256dd1e47952e and triggered exactly once. Run https://github.com/yituanxing/minicore/actions/runs/42347894942 restored 1152M and produced its first 1172M checkpoint. This is a new run, not another cold boot.

## Guest observations

By 1152M: retired instructions 276,842,396; SD read blocks 5,545; SD writes 48. ext4-mounted and kernel Run /sbin/init markers are present. UART count 12,830 bytes, last UART activity at cycle 457,740,975; no new UART from the studied windows. login_seen=false. These facts do not prove PID1/systemd completion or login, and UART silence alone does not prove a deadlock.

Each SD read counter increment in sim/l32_sd_spi_card.h::queueReadBlock represents one 512-byte sector. The table records per-20M-cycle deltas and the symbolization of only the most recent 16 retired PCs at each endpoint.

| Checkpoint | SD reads delta | Retired delta | Last-16-PC sampled functions |
| --- | ---: | ---: | --- |
| 932M | +157 | +4,694,938 | __spi_pump_transfer_message |
| 952M | +150 | +4,695,022 | tiny_spi_txrx_bufs |
| 972M | +154 | +4,696,412 | crc_itu_t |
| 992M | +153 | +4,699,979 | spi_split_transfers_maxsize, __spi_pump_transfer_message |
| 1012M | +153 | +4,696,520 | tiny_spi_txrx_bufs |
| 1032M | +154 | +4,695,170 | tiny_spi_txrx_bufs |
| 1052M | +151 | +4,700,823 | tiny_spi_txrx_bufs |
| 1072M | +158 | +4,695,602 | crc_itu_t |
| 1092M | +151 | +4,697,815 | spi_transfer_one_message, spi_bitbang_transfer_one, spi_dma_sync_for_cpu |
| 1112M | +151 | +4,697,158 | crc_itu_t |
| 1132M | +153 | +4,700,347 | tiny_spi_txrx_bufs |
| 1152M | +138 | +4,694,009 | __mem_cgroup_charge, __filemap_add_folio |

The last 20M window additionally had SD writes +8 (40 to 48), SD commands +12 and no new UART. The change to memory-cgroup/page-cache functions at 1152M is evidence of a different instantaneous code path, not proof of overall progress to login.

## Exact Linux symbol recovery

- Polling firmware build artifact 13560557120 contains boot/fw_payload-polling.elf. Its .payload section embeds the exact fixed Linux Image (strip eight ELF section-alignment bytes to match the Image SHA above). OpenSBI ELF symbols must not be applied to Linux addresses.
- The embedded Linux IKCONFIG confirms CONFIG_KALLSYMS=y and CONFIG_KALLSYMS_BASE_RELATIVE=y.
- 37,936 kernel symbols were decoded from Image kallsyms with relative base 0xffffffff80001000, ordered addresses, token table and name markers checked.
- Kernel PC 0xffffffff805e68fa maps to __spi_pump_transfer_message; 0xffffffff805e9b5a maps to tiny_spi_txrx_bufs; 0xffffffff80006d9c maps to __sbi_set_timer_v02 and exact Image instruction bytes 73 00 00 00 (RISC-V ECALL).
- Linux 6.6.143 drivers/spi/spi-oc-tiny.c implements MMIO hardware Tiny SPI transmit/receive with TXR/TXE polling in the no-IRQ path. drivers/spi/spi.c handles messages; drivers/mmc/host/mmc_spi.c uses crc_itu_t. The pinned polling DT has no Tiny SPI interrupts property. This is hardware Tiny SPI with per-byte polling, not GPIO software bit-banging.

## Performance inference and safe follow-up

- Common steady windows showed roughly 151-158 SD reads per 20M Product RTL cycles: about 77-81 KiB read and 126-132k cycles per 512-byte block. At an assumed 50MHz guest clock, interval-average throughput is roughly 193-202 kB/s, including all kernel and guest activity; this is NOT the SPI hardware-only bandwidth or a measured fraction of cycles spent polling.
- End-of-window last-16 PC samples are not a cycle-level PC histogram. Do not infer a precise hotspot percentage from them.
- A low-risk additional sampling experiment can restore the independently authenticated 1152M snapshot with the exact unchanged Verilator executable and run isolated 1M-cycle warm chunks (1153M through 1157M), collecting snapshots/observer data. It must not alter the active long run or substitute for cycle-level profiling.
- Modifying the simulator's per-retire observe() to record a histogram would change the executable/model SHA; the current readHost identity check will reject old snapshots. Such profiling requires a separate qualified diagnostic identity and recovery compatibility gate, not a silent rebuild of the production long-run model.
- Preserve original artifacts, host/RTL SHA, source identity, UART and SD counters; stop and report the first real failure rather than restarting from cold or claiming Debian success based on green CI.
