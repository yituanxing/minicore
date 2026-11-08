# Signed SD1 Product RTL: frozen-image DDR forensic probes

These two Python probes are deliberately scoped to the **exact** Debian SD1
Linux Image SHA-256 `522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393`.
Kernel `task_struct` offsets, PID1 stack offsets and Sv39 assumptions **must
not** be reused for another kernel build without re-deriving and testing them.

Suggested repository destinations (keep both files side by side):

- `tools/ci/product_sd1_generator_progress.py` — task-list, saved trap and stack evidence;
- `tools/ci/product_sd1_pid1_elf_linkmap.py` — ELF `DT_DEBUG`/`link_map`
  and GNU hash dynamic-symbol recovery from PID1's mapped DDR pages.

The second script imports the first from the same directory. The scratch names
`sd1-generator-progress.py` and `sd1-elf-map.py` also work locally.

## Evidence prerequisites

1. Download a real Product SmallOoO RTL checkpoint ZIP from the *matching*
   GitHub Actions run; independently compare its SHA-256 to the GitHub artifact
   digest and record artifact ID, run ID and source commit.
2. Extract into an isolated directory containing `.migration-ready`,
   `persistent.pre.host`, `persistent.pre.rtl` and `card-progress.json`.
3. For both probes, supply the matching `System.map` whose SHA-256 is
   pinned as `117c099b65e37c891e5f3383ae36686967577dcb7be862a7f6066d9bf69061da`.
4. Both probes verify checkpoint schema, frozen Image SHA and complete Host/RTL
   SHA; the ELF probe now additionally verifies progress-file SHA **before**
   interpreting observer PCs. Both probes verify `System.map` SHA.

## Commands

```sh
python3 tools/ci/product_sd1_generator_progress.py SNAPSHOT_DIR EXACT_System.map --stacks --user-code
python3 tools/ci/product_sd1_pid1_elf_linkmap.py SNAPSHOT_DIR EXACT_System.map
```

The ELF probe reports PID1's actual loaded DSO base/path via its own runtime
linker `DT_DEBUG`/`r_debug`/`link_map`, then bounds user PCs to executable ELF
segments and optionally resolves **exported** dynamic symbols. When no symbol
containing the PC can be proven, `nearest_preceding_export` is a *hint*, not an
exact function name. The CPU observer preserves only the last 16 retired PCs;
these observations do **not** prove how an entire 20M-cycle segment was spent.

## Known replay fixture

From official RTL run `42469084829`, stage_01 endpoint 6312M artifact
`13613092120`, ZIP digest
`f440f7f6949d9d63b6eb11e1d6ba866b43795504410f241b3ce5f1136b726af1`:

- Host SHA `4742b668e0b6ea575eb7c636604e5398b392e911d6612d51e2c3fffce62d4c61`;
- RTL SHA `1c1eba67e9bfdeb651e5d3fb3e0ad7b4a803f5eaceb65611fa9ddeabbed87850`;
- expected DDR PID1 module map includes `/lib/riscv64-linux-gnu/libseccomp.so.2`
  and `/lib/riscv64-linux-gnu/libc.so.6` among last retired PCs;
- 2524 4KiB user-space mapped pages in that exact snapshot.

Neither script modifies checkpoints or triggers a simulator. Neither can
declare full Debian boot success. **Only real RTL service/login UART evidence**
is the product acceptance criterion; task-list activity alone is not PASS.
