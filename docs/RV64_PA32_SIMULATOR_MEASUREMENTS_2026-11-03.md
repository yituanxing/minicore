# AetherCore PA32: simulator acceleration measured results (2026-11-03)

## 1. Same-RTL, same-firmware Verilator version A/B (qualified prefix)

CI: https://github.com/yituanxing/minicore/actions/runs/41560954142

Exactly the same Chisel-generated PA32 SystemVerilog, Linux/OpenSBI
firmware SHA256, hosted runner, Clang -O3 and Adaptive Settle macro.
12,000,000 guest cycles per trial, two alternating rounds. All four
terminal milestone snapshots are byte-identical:
`AETHERCORE_PA32_VERILATOR_VERSION_STATE_MATCH`.

| Round | Verilator 5.024 time | Verilator 5.054 time | v5.024 cps | v5.054 cps |
|---|---:|---:|---:|---:|
| 1 | 41.693468 s | 32.105611 s | 287814.868 | 373766.445 |
| 2 | 42.648197 s | 31.981745 s | 281371.798 | 375214.050 |
| Median | 42.170833 s | 32.043678 s | 284593.333 | 374490.248 |

* Throughput uplift **31.58785%**, or **1.31588x**.
* Same-guest-cycle wall-time saving **24.01459%**.
* Valid **for this 12M-cycle firmware prefix only**, not a full-Linux
  cold-boot time; the same-firmware 5.024/5.054 full Linux PID1/STIP/SEIP
  A/B is a separate workflow, not yet counted.

## 2. GSIM vs Verilator v5.054: matched no-RAM-response CPU engine

CI: https://github.com/yituanxing/minicore/actions/runs/41566002940

Entire **same PA32 Chisel design**, same host, fixed external I/O inputs
(zero instruction/data/PTW responses, ready=1, no injected UART data),
250,000 cycles per trial, three alternating rounds.
GSIM pinned upstream source commit
`45a73d6afb9b1893781cfb57ccd54096987bb23d` with
the isolated `OP_ASUINT/OP_ASSINT` cast lowering compatibility patch.
GSIM native model compiled by Clang 19 -O3; Verilator 5.054 pinned to
`bbb60a403932a9f695cb6ff001ceb73de6ad9234`, Clang -O3.

| Round | GSIM cps | Verilator 5.054 cps |
|---|---:|---:|
| 1 | 810480.71 | 440379.35 |
| 2 | 813619.33 | 440192.83 |
| 3 | 808790.25 | 443106.90 |
| Median | **810480.71** | **440379.35** |

* GSIM **1.84041x** v5.054 throughput; equivalent wall-time reduction
  approximately **45.66%** for this workload.
* `AETHERCORE_PA32_ENGINE_AB_STATE_MATCH` checks identical terminal
  guest cycle, `mtime`, number of retires, traps, interrupts and
  retirement hash. Both engines returned:
  * `mtime=124992`
  * `commits=62496`, **`exceptions=62493`**
  * `interrupts=0`
  * `digest=0x4c4d4387bb6b693c`
* **CRITICAL:** This is a forced-zero response, repeated illegal-instruction
  trap workload. It is **not** a bootable firmware, valid guest software
  test or Linux result. The same terminal state proves this particular
  benchmark is comparable, not that the entire GSIM/Verilator hardware
  semantics are cycle-by-cycle equivalent.
* The GSIM/Verilator speedups from different workloads cannot be multiplied.

## 3. Historical GSIM host-RAM timing failure (now fixed for bare RV64I)

CI diagnostic:
https://github.com/yituanxing/minicore/actions/runs/41566002892

Generated 5,357,040-byte CHIRRTL and native GSIM model: PASS.
The model's 20 MHz -> 10 MHz timebase: PASS (256 steps after reset,
`mtime` delta 120).
Host RAM contains the correct first three RV64I instructions:
`0x00100093`, `0x00208113`, `0x00310193`.

Actual captured boundary sequence:

* guest cycle 28: `imem_valid=1`, `imem_addr=0x80000000`,
  host `reply_inst=0x00100093`.
* guest cycle 29: request goes low and host response becomes zero.
* subsequent retirement at PC `0x80000000`: instruction zero,
  illegal-instruction exception `cause=2`.
* 20,000-cycle run: 4,996 retirement events; **4,993 exceptions**;
  zero accepted sequential three-instruction RV64I program.
* This run is deliberately FAIL and its 213,281 native cycles/sec is
  not a valid guest-speed result.

**Historical conclusion:** GSIM's exposed `step()` advances a full hardware tick;
it cannot replace the qualified Verilator low-clock `eval()` sequence
that observes current RAM request and feeds a same-cycle combinational
response. The next step is an exact simulator-host combinational callback
(or equivalent phase-separated execution), plus cycle/retirement/IRQ
differential against the unchanged Verilator model. Do not modify the CPU
RTL or Linux configuration to hide this discrepancy.

## 4. Go/No-Go guidance

1. **Go, conditional final signoff:** Verilator v5.054 + Adaptive Settle
   already speeds up a true OpenSBI/Linux firmware prefix with byte-identical
   terminal state; promote only after a full same-firmware Linux/PID1/STIP/SEIP
   cold-run qualification.
2. **Go for research only:** GSIM shows 1.84x **engine-only** throughput
   on this particular matched zero-response workload.
3. **GSIM bare RV64I execution is now qualified, Linux remains a NO-GO:**
   A new same-tick host-RAM bridge fixes real instruction retirement and
   passes a strict Verilator v5.054 differential. Data stores, atomics,
   page-table walking and live Linux remain independent acceptance gates.

Artifacts from both CI runs provide original TSV files, terminal state
snapshots, full logs and independent source/toolchain manifests.

## 5. Actual host RAM / RV64I GSIM / Verilator differential PASS

* Host callback integration CI: https://github.com/yituanxing/minicore/actions/runs/41568066719
* Independent 20k-cycle Verilator differential CI:
  https://github.com/yituanxing/minicore/actions/runs/41568779809

The pinned GSIM C++ emitter can invoke a native host callback **within**
an existing `subStep0()` supernode computation. The unchanged PA32 RAM
responds to imem/data/PTW requests in the same abstract hardware tick,
rather than delaying the response by another `step()`. The CPU RTL,
existing physical memory algorithm and Linux configuration are unchanged.

20,000 cycles, byte-identical four-instruction RV64I image at
`0x80000000`:

| Metric | GSIM | Verilator 5.054 |
|---|---:|---:|
| Genuine retired events | 9,987 | 9,987 |
| Exceptions | 0 | 0 |
| Interrupts | 0 | 0 |
| mtime | 9,992 | 9,992 |
| Retired PC/instruction digest | 0x233f18bc7d6d98a8 | 0x233f18bc7d6d98a8 |

* Both simulators retired real ROM `0x1000` instructions and RAM
  `0x00100093`, `0x00208113`, `0x00310193`, then repeatedly retired
  the valid `0x0000006f` loop.
* First 16 retired PC / inst / exception / cause records matched.
* Full-stream retirement FNV digest matched.
* GSIM host callback: **112 calls**, **8 valid instruction-fetch calls**,
  all 8 with nonzero instruction data.
* GSIM ran `clang++-19 -O0` in this correctness mode, so its 20k-cycle
  wall time is **not comparable** with the earlier `-O3` 1.84x
  fixed-zero GSIM engine microbenchmark.

Thus the fundamental GSIM same-cycle host fetch issue has been solved for
actual RV64I instruction execution. **Do not extrapolate this proof to
MMU/CSR, PTW, atomics, interrupts or Linux.**

## 6. Full OpenSBI/Linux 6.6.143 PID1: Verilator v5.054 qualification

CI: https://github.com/yituanxing/minicore/actions/runs/41565619428

Both models ran the *exact same firmware* and complete PA32 RTL, with
Clang -O3 plus Adaptive Settle. STIP and SEIP interrupt and ISA coverage
summaries matched; `AETHERCORE_PA32_FULL_LINUX_STATE_MATCH` passed.

| Measure | Verilator 5.024 | Verilator 5.054 |
|---|---:|---:|
| Guest cycles | 340,397,416 | 340,397,416 |
| Retired instructions | 107,360,309 | 107,360,309 |
| STIP | 3,378 | 3,378 |
| SEIP | 1 | 1 |
| Wall time (s) | 748.188 | 560.917 |

Verilator v5.054 delivers **1.33387x** speedup, or **25.03% shorter
full Linux cold start** with no CPU architecture or guest software
changes. Keep v5.024 as regression reference. This figure is a real
Linux result, **not** the GSIM speedup.

## 7. Next real firmware GSIM gate

The PA32 GSIM/Verilator differential has now been extended to the same
unmodified OpenSBI+Linux firmware binary, from identical 256 MiB host
RAM, for 2,000,000 initial cycles. It compares every-run retirement
digest and the first 16 retired instructions as well as mtime,
interrupts and exceptions, with an immutable firmware SHA256.
The CI is https://github.com/yituanxing/minicore/actions/runs/41569416797.

No claim of OpenSBI or Linux GSIM PASS is made before that workflow
actually completes. If it diverges, diagnose ordinary stores, LR/SC,
AMO and page-table response/commit ordering from the first mismatch.

## 8. Same-firmware two-million-cycle real OpenSBI comparison: PASS

Successful workflow:
https://github.com/yituanxing/minicore/actions/runs/41569416797
Artifact: pa32-gsim-host-bridge-41569416797, including the exact
same SHA256 firmware image, both runner logs and differential results.

* Full source: Linux 6.6.143 \`early-banner-v1\`, OpenSBI 1.6,
  firmware SHA256 \`f81c8d333584b3dbb49cce6e7cb614638598971205854b5fa74cb2273b11dde8\`.
* Both ran exactly **2,000,000** guest cycles, retired **753,104**
  instructions, counted **1 exception, 0 interrupts**, and ended at
  \`mtime=999992\`, \`PC=0x8001cb2c\`.
* Full retirement PC/instruction digest matched:
  \`0x9b4338a93c18c325\`.
* 16 initial retirement PC, inst and cause tuples also matched.
* GSIM: **13.181075 s**, **151,732.7 cycles/s**.
* Verilator v5.054: **19.324522 s**, **103,495.4 cycles/s**.
* Observed **1.466x** GSIM speedup for this exact prefix. This result
  is **preliminary**, since GSIM's runner was built using Clang-19
  \`-O0\` whereas the Verilator model build used \`-O3\`.
  Do not use this number as a full-Linux or equal-build-flags claim.

The next 20-million-cycle CI now requires:
1. Clang-19 \`-O3 -march=native\` runner/model flags on each engine;
2. Equal 100,000-cycle retirement checkpoints with first mismatch location;
3. Equal complete 256 MiB host RAM FNV-1a digests **plus LR/SC reservation**
   state at the end; the hash is computed only after timing stops;
4. Matching total commits, trap/IRQ count, \`mtime\`, terminal PC and
   retirement digest; and
5. Measured host throughput only **after** all correctness gates pass.

This still is an **OpenSBI/Linux firmware prefix** test. It is not
evidence that GSIM has booted the Linux kernel all the way to PID1.
