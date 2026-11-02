# RV64 ISA-driven fast Linux cold boot: v1 / v2 experiment

## Qualification model

The canonical Linux 6.6.143 `baseline` remains intact.
`isa-fast` uses the same RISC-V GCC, OpenSBI, RTL and /init but prunes
unrelated driver and debugging initializers. The verified same-run baseline/v1
CI [41470173968](https://github.com/yituanxing/minicore/actions/runs/41470173968)
booted PID1 and retired a real STIP and SEIP on both profiles.

| Metric | Baseline | ISA-fast v1 |
|---|---:|---:|
| Guest cycles | 424041974 | 343376034 |
| Guest commits | 133498187 | 108879632 |
| Host seconds to user IRQ marker | 1738.37 | 1422.74 |
| UART bytes | 12833 | 3338 |

The same run showed no missing top-level opcode or exception-cause class.
That does NOT prove full RV64A/ISA equivalence. Directed tests remain required.

## v2 (experimental, not yet qualified)

`isa-fast-v2` layers the following on the qualified v1:
* Removes the **early polled UART console** and suppresses kernel printk
  console output using `loglevel=0`. It retains `console=ttyS0,115200`,
  Linux 8250, /dev/console and the real PID1 FIFO refill IRQ through PLIC.
* Disables optional dynamic modules, devtmpfs (initramfs already contains a
  c5:1 /dev/console node), namespaces, cgroups, BPF syscall, perf events,
  firmware loader, audit, keys, kallsyms and auxiliary debugging.
* Seeds an isolated v2 object tree from the qualified v1 object tree to
  rebuild only objects affected by Kconfig. The original baseline and v1
  firmware remain unchanged.

The simulator emits `L32_ISA_COVERAGE` plus
`L32_ISA_DETAIL` (opcode/funct3, integer funct7, AMO operation+width and
system instruction code). The comparator reports lost coverage subclasses
rather than treating PID1 success as equivalence. Exception causes,
STIP/SEIP, real UART output and PID1 are mandatory in each cold boot.

CI executes baseline, v1 and v2 sequentially on the **same Runner and
Verilator binary**, records actual cycles and steady-clock seconds and
uploads `isa-fast-v2-ab.json` and `baseline-v2-ab.json`.
No v2 speedup or coverage PASS is claimed before CI finishes.

## Limits

* A category being executed is not proof of correct semantics.
* LR/SC success/failure and MMU microarchitecture need directed tests
  or richer commit evidence; this report only records the retired op variant.
* If v2 loses a class, review whether a directed test replaces it.
* Build/run profiles use distinct directories and firmware hashes so state
  checkpoints from one profile cannot silently be used by another.
