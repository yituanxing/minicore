# Roadmap

AetherCore is advanced by executable software pressure: small programs establish architectural mechanisms, focused regressions freeze them, and increasingly capable operating systems expose the next missing boundary.

## Completed / frozen ladder

- **S0.x** — Chisel/CIRCT/Verilator bring-up, directed RV64I pipeline/hazard/fault regressions and pinned NEMU retirement DiffTest.
- **S1.x** — RV64M, compiler-produced workloads, CoreMark, Embench and littlefs-style real software qualification.
- **Machine OS line** — FreeRTOS-driven Machine CSRs, precise traps, timer/external interrupts, PLIC, WFI and preemptive context switching.
- **U-mode line** — U-mode execution, ECALL/syscall return, PMP isolation and isolated preemptive tasks.
- **Zephyr Z1-Z4** — frozen Zephyr kernel/interrupt qualification.
- **NuttX N1-N4** — frozen flat NuttX build, NSH, timer/scheduler and UART RX through PLIC.
- **NuttX protected P1-P3** — real protected userspace, RV32A workload-driven atomics, trusted kernel exception stacks, PMP fault isolation and scheduler/NSH recovery.
- **Supervisor V1** — frozen physical-address M/S/U execution with Supervisor CSRs, synchronous exception delegation and SRET. Sv32 remains disabled by design.

## Next: Supervisor V2 — Sv32 foundation

The next architecture branch should start from `freeze/smode-v1-minimal-trap` and remain bounded to virtual-memory fundamentals.

Target sequence:

1. `satp` CSR and Sv32 mode/WARL behavior;
2. two-level Sv32 page-table walk;
3. instruction, load and store address translation;
4. R/W/X/U permission enforcement;
5. precise instruction/load/store page-fault causes with `stval`;
6. a small correctness-first TLB;
7. `sfence.vma` invalidation semantics;
8. focused stale-translation and cross-page negative probes;
9. a real S-mode OS workload that creates page tables and reaches U-mode through them.

The implementation should not add cache/performance complexity until translation correctness and fault precision are frozen.

## After Sv32

- **Supervisor V3** — S-level interrupt delivery required by a real supervisor OS, including the appropriate delegated timer/external interrupt plumbing rather than pretending Machine sources are S sources.
- **Real supervisor OS** — choose the smallest OS/kernel that genuinely exercises address spaces, page faults, context switches and U-mode processes; use its failures to drive missing architecture.
- **Firmware boundary** — introduce an M-mode firmware/SBI layer when the selected S-mode OS needs it.
- **Linux-class line** — expand toward the architecture width and MMU profile required by the chosen Linux target, then OpenSBI, Linux, musl, BusyBox and deterministic user programs such as Lua/SQLite.

## Performance and hardware after software completeness

Once the functional software ladder is stable:

- caches and memory hierarchy;
- multi-cycle multiply/divide and other execution-unit latency work;
- branch prediction;
- bus/interconnect refinement;
- FPGA synthesis, timing closure and board bring-up;
- optional later superscalar work such as dual issue, ROB, register renaming and partial out-of-order execution.

Correctness and software completeness precede performance optimization. Every upstream failure that reveals an architectural bug should be reduced into a focused permanent regression before RTL is changed, and microarchitectural changes should rerun frozen executable hashes without silently recompiling them.