# NaxRiscv official Buildroot RTL full-userspace acceptance — 2026-11-10

## Verdict

**TRUE RTL USERSPACE PASS, VERIFIED FROM THE ORIGINAL ARTIFACT.** The upstream
simulator returned `SUCCESS nax_buildroot_full`, process exit code `0`, and
created `full_state/PASS`. The GitHub Actions wrapper's final gate was a
**false negative**, because it anchored its grep to `^SUCCESS`, while the
simulator emitted `root@buildroot:~#SUCCESS nax_buildroot_full` without a
newline. This is not a Nax RTL or Linux failure. No costly rerun is warranted.

## Provenance

- Isolated Actions run: https://github.com/yituanxing/minicore/actions/runs/42822277222
- GitHub branch: `experiment/nax-verilator-throughput-20261110`; workflow commit
  `68e3d4a338599ab65cace528a5f3c229a63f450a`.
- Official NaxRiscv repository: `SpinalHDL/NaxRiscv`, pinned revision
  `9f452d50560d02fb391bc8039f5453c54e0911af`.
- Official unattended acceptance recipe: `src/test/cpp/naxriscv/testsGen.py`
  (`--getc`, `--putc`, `--success`), unchanged contract.
- Simulator: source-built Verilator 4.228, Spike lockstep, `TRACE=no`.
- OS: official prebuilt **RV32IMA Buildroot Linux 5.10.1** with OpenSBI and
  initramfs loaded directly to guest RAM; **not Debian, not an SD boot**.
- GitHub-hosted Linux VM: AMD EPYC 7763, 4 vCPU, 15 GiB memory.
- Artifact ID `13768517856`, archive SHA256
  `6773d0c0ebfa4a98a693f24e91e816924324221fe415266f7e962f5f8d4cae25`.
  Original `linux-full-rtl.log`, `outcomes.txt`, `full_state/PASS`, and
  reproducibility environment logs included. GitHub retention: 14 days.

## Actual results

| Metric | Observed |
|---|---:|
| Linux initramfs `Run /init` | Present in UART |
| `buildroot login:` and authenticated `root` prompt | Present in UART |
| `cat /proc/cpuinfo` | `isa: rv32ima`, `mmu: sv32` |
| `echo 1+2+3*4 \| bc` | `15` |
| `micropython` and `math.sin(math.pi/4)` | `0.7071067811865475` |
| `ls /` | Output returned to root prompt |
| Native simulator verdict | `SUCCESS nax_buildroot_full` |
| Simulator exit code | `0` |
| Native PASS file | `full_state/PASS` exists in uploaded artifact |
| RTL cycles | **213,026,285** |
| Retired instructions | **128,592,734** |
| IPC | **0.603647** |
| Actual RTL-simulation wall time | **1,199 s = 19m 59s** |
| Average long-run throughput | **177,670 RTL cycles/s** |
| Approximate time to first `buildroot login:` | **16m 47s** after benchmark began |

## Important comparison limits

The earlier 12M-cycle Nax Linux early-boot experiment (run #42754929058)
measured an average of about 259,000 RTL cycles/s on an **AMD EPYC 9V45**.
The full userspace run used an **AMD EPYC 7763** and averaged 177,670 cycles/s.
Different CI host generations and substantially different Linux phases mean
this slowdown is **not** isolated evidence of an RTL or I/O bottleneck.

The separate AetherCore signed SD1 SPI-polling window measured 163,399
cycles/s over 200M cycles; this is **not** a controlled same-host comparison.
Its CPU, firmware, OS, storage path, device model, and checkpoints all differ.

The full Nax test read its initramfs from host-initialized guest memory and
was never expected to model Linux's SD/SPI-card I/O. The pass establishes
that an open-source out-of-order RISC-V RTL can run a complete command-line
Linux userland under Verilator and that this specific host run takes about
20 minutes, not that AetherCore's SD1 has passed.

## Wrapper defect, no rerun

The workflow expected a line-start `^SUCCESS nax_buildroot_full`; the original
simulator writes `root@buildroot:~#SUCCESS nax_buildroot_full` on the same
line. The wrapper exited 1 after real `PASS`. Future workflows should check
`SUCCESS nax_buildroot_full` as a substring and require the native `PASS`
file and process exit code 0. This existing run is fully authenticated by
its saved artifact and does not need to consume another 20 minutes.
