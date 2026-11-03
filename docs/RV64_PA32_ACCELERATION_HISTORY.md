# PA32 RV64 virtual FPGA: integrated historical simulator acceleration

## Isolated categories

1. **Host cycles/second**, not guest-cycle removal: `rv64_pa32_host_acceleration.sh`
   reuses #153/#363 adaptive settle and #374 compiler A/B. The same PA32 RTL
   and SHA256 firmware are used for GCC reference, GCC adaptive and Clang
   adaptive. All variants run a 20M-cycle cold OpenSBI/Linux prefix twice,
   with alternating order and byte-identical terminal-state oracle.
2. **Linux repeat workload acceleration**, not a new kernel: migrated #147
   `pa32_checkpoint_forkserver_main.cpp` forks the current Verilated model at
   the canonical verbose Linux `Run /init as init process` UART boundary.
   Both children independently reach the real UART IRQ/PLIC SEIP marker; a
   verifier checks the same cycle count, commits, interrupts and STIP/SEIP deltas.
   It is in-memory fork/COW, not disk persistence.
3. **Build/provenance**: existing #378 source+RTL+native-model hash-qualified
   cache remains untouched; no cached Linux PASS and no change to the qualified
   reset-to-PID1 CI gate. Firmware SHA is attached to every experiment.

## Prior evidence / decisions

* #363 Fast-top adaptive settle: +13.42% cycles/sec, merged on main.
* #374 Fast-top Clang -O3 versus GCC -O3: +3.97% median throughput (unpromoted).
* #147 legacy V2 Linux PID1 fork/COW: 2/2 children, identical 1,206,732-cycle
  and 308,665-commit replay with a real SEIP each (unmerged).
* #155/#156 host RAM `memcpy`: +0.17%/+0.34%; NO-GO.
* #157 request guard: +1.32%; under 3% gate; NO-GO.
* #364 Verilator 2/4 threads: 0.7693x and 0.4987x; NO-GO.
* #375 profile: most host cost in Verilated logic/common code; profiling
  overhead is large, so use it diagnostically, not as throughput benchmark.

## Qualification rule

The authoritative `v2-soc-virtual-fpga-linux-pid1.yml` cold signoff still
starts from reset and requires OpenSBI, Linux, PID1, STIP and UART PLIC SEIP.
Neither the 20M host A/B prefix nor a post-boot fork proves cold signoff.

No CPU RTL, Linux .config or FPGA peripheral semantics change here.
Both experimental workflows are separate from the original signoff.
Results are candidates until a **same-firmware full Linux cold run** proves
the promoted simulator settings. Do not multiply independent historic
speedup ratios from different RTL/hosts.

## Failure-only replay at the immutable PID1 snapshot

The PA32 fork/COW runner now supports opt-in
`PA32_CHECKPOINT_REPLAY_ON_FAILURE=1`. The default two-child
cold-then-replay signoff remains unchanged. After a failing child,
the parent creates an **additional** diagnostic child from its unchanged
checkpoint via the same `prepareClone()`/`fork()`/`atClone()` API;
that child records the latest 64 real retirement PC/instruction/exception
events. It emits `PA32_CHECKPOINT_REPLAY_COMMIT` and
`PA32_CHECKPOINT_DIAGNOSTIC_REPLAY_RESULT` diagnostics. The original
child's failure remains a failure even if the replay differs/succeeds.

This is a first failure-replay slice, **not** a periodic LightSSS
checkpoint nor a persistent disk snapshot. It adds no execution or
per-commit tracing on the default normal-PASS path.
