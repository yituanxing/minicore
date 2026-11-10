# Product G5 D2: exact 950M -> 955M archived-state forensic probe

Date: 2026-11-06. Scope: **historic real Debian D2 RAM-initramfs Product
RV64GC**. This is a bounded guest-state investigation, **not** a Debian PID1
PASS, distribution login PASS, performance comparison, or a new RTL build.

## Provenance and replay guard

- Cold 900M run: [GitHub Actions #42028338206](https://github.com/yituanxing/minicore/actions/runs/42028338206),
  artifact ID `13419381149`.
- Exact-source 900M -> 950M bridge:
  [GitHub Actions #42044232627](https://github.com/yituanxing/minicore/actions/runs/42044232627),
  artifact ID `13423569393`.
- Source commit: `99a5c86e99f52ed12f95b304635d77613c9505c4`.
- Archived model SHA256: `aa35c80024238420bea3976db677db2e0ef028aad9800482926b926e50155b71`.
- Archived executable SHA256: `5ed1e31ea3c1a5dc8761e4ffb33a35a48db412e359521efa3e3eb408cfbae43d`.
- Firmware SHA256: `d525de6a58422de93da6de5312d38867611153da94c580bca31f90ebf13eff2c`.
- `bridge-950m.sha256` and original `pre.sha256` verified. The exact
  archived executable replayed the original 900M -> 900.5M 500,000-cycle
  golden state. **Both RTL state and complete host/DDR state were
  byte-identical** to the original `.restored` archives before any new probe.
- The original firmware bytes do **not** need rebuilding: the checkpoint
  `restore` path validates the persisted firmware SHA and reads full RTL/DDR
  from the snapshot. The `/dev/null` firmware placeholder is never loaded
  by `restore` (only cold `save` loads firmware bytes).

Reproduce with two unmodified GitHub artifact ZIPs:

```bash
bash tools/ci/product_g5_d2_archive_probe.sh \
  d2_900m_original.zip d2_950m_bridge.zip \
  /tmp/g5-d2-archive-probe-new
```

The output directory must not already exist. The wrapper checks pinned SHA
identities, replay compatibility and each cycle boundary, then writes
`pc-report.txt`. It never invokes a 0->900M cold boot, recompiles RTL,
or runs an unqualified alternative CPU model.

## 5M-cycle bounded observation

| Real RTL metric | At 950M | At 955M | Difference |
| --- | ---: | ---: | ---: |
| Retired instructions | 244,648,576 | 246,019,304 | +1,370,728 |
| Exceptions | 8,570 | 8,595 | +25 |
| Interrupts | 8,784 | 8,834 | +50 |
| STIP | 4,392 | 4,417 | +25 |
| UART bytes | 78,647 | 78,647 | 0 |
| Debian D2 first PID1 marker | absent | absent | no qualification |

Ten independent 500,000-cycle restores all completed with exact expected
cycle/time, increasing retirement and valid saved states. They sample only
the Observer's **last 16 retired PCs at each boundary**, symbolized using the
archived exact `System.map`; these are samples, **not** full PC utilization
histograms or stack traces:

| Boundary | Sampled kernel function(s) |
| --- | --- |
| 950.0M | `lockref_put_or_lock`, `dput` |
| 950.5M | `__might_sleep`, `shmem_inode_acct_block`, `security_vm_enough_memory_mm` |
| 951.0M | `inode_add_bytes` |
| 951.5M | `memcpy` |
| 952.0M | `strlen` |
| 952.5M | `memcpy` |
| 953.0M | `memcpy` |
| 953.5M | `blake2s_compress_generic` |
| 954.0M | `__mod_node_page_state` |
| 954.5M | `get_page_from_freelist`, `_raw_spin_trylock` |
| 955.0M | `lru_add_fn` |

This is incompatible with the claim that the CPU is stuck in one fixed PC
loop. The samples and VFS/tmpfs/page-allocation functions are **consistent**
with ongoing large initramfs/rootfs setup, but do not yet prove the exact
worker, call chain or completion percentage. The last UART line at about
12.917 guest seconds was `of_platform_sync_state_init` returning; there is
no `AETHERCORE_DEBIAN_D2_START` evidence at 955M.

## Forensic tooling choice

The older `resume-diagnose` at 900M returned status 14 immediately because
its 100M-cycle *UART-silence* watchdog had already elapsed since UART stopped
at cycle 711,423,647. The absence of timer-boundary PC samples in that run
was a diagnostic-budget artifact, not proof of a stalled CPU.

The new bounded probe intentionally uses the existing ordinary `restore`
mode, which has no such watchdog and has an explicit 500,000-cycle cap per
process. A future task-level explanation would need PC/time histograms or
guest task-state observation from the same-identity snapshot, **before**
spending another hour on a new Debian cold boot.
