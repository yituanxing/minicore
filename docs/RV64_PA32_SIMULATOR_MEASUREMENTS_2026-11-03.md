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

## 3. GSIM real-RV64I transport gate: still FAIL

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

**Conclusion:** GSIM's exposed `step()` advances a full hardware tick;
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
3. **No-Go for Linux yet:** GSIM's current external host RAM bridge fails
   actual RV64I retirement; no claimed Linux speedup, no substitution
   for reset-to-PID1 Verilator signoff.

Artifacts from both CI runs provide original TSV files, terminal state
snapshots, full logs and independent source/toolchain manifests.
