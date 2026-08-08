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
- **Supervisor V1** — frozen physical-address M/S/U execution with Supervisor CSRs, synchronous exception delegation and SRET; Sv32 intentionally disabled in the V1 regression profile.
- **Supervisor Sv32 V2** — frozen RV32 Sv32 address translation with PA34 instruction/data paths, two-level page-table walk, precise page faults, correctness-first TLBs, `SFENCE.VMA`, and U-to-S page-fault delegation. Authoritative evidence: `docs/SV32_V2_FREEZE.md`.

## Next: real Supervisor-OS pressure

V2 has enough virtual-memory architecture to stop adding synthetic MMU features. The next bounded branch should start from `freeze/sv32-v2-minimal-translation` and use a real S-mode kernel as the design driver.

Target sequence:

1. audit small RV32/Sv32-capable kernels and choose the smallest target that genuinely creates page tables and enters S-mode;
2. boot the kernel through the existing M -> S boundary without inventing unnecessary firmware features;
3. prove that its real code consumes `satp`, Sv32 translation, TLB invalidation and page-fault handling;
4. allow the workload to expose the next missing architectural boundary, likely S-level interrupt/timer plumbing and/or an SBI-like firmware interface;
5. reach a real U-mode process under the S-mode kernel;
6. deliberately trigger unmapped/read-only/kernel-VA accesses from that process;
7. prove precise page-fault delivery, process termination/isolation and continued kernel/scheduler execution;
8. freeze the resulting OS-driven checkpoint before adding wider Linux-class capability.

The chosen OS should drive new architecture. Do not add Supervisor interrupt sources, firmware calls, ASIDs, hardware A/D updates or more MMU machinery merely because the specification contains them; add them when the real workload demonstrates the need.

## After the first real S-mode OS

Depending on the failures exposed by that workload:

- **Supervisor interrupt line** — add only the delegated timer/external interrupt mechanisms the selected kernel actually requires.
- **Firmware/SBI boundary** — introduce an M-mode firmware/SBI layer when a real S-mode kernel requires it rather than simulating Linux conventions prematurely.
- **Process/VM hardening** — expand address-space switching, page-fault recovery and user-process isolation from real kernel behavior.
- **RV64/Linux-class line** — when the architecture is ready, select the required RV64/MMU profile, then introduce OpenSBI, Linux, musl, BusyBox and deterministic user programs such as Lua/SQLite.

## Performance and hardware after software completeness

Once the functional software ladder is stable:

- caches and memory hierarchy;
- multi-cycle multiply/divide and other execution-unit latency work;
- branch prediction;
- bus/interconnect refinement;
- FPGA synthesis, timing closure and board bring-up;
- optional later superscalar work such as dual issue, ROB, register renaming and partial out-of-order execution.

Correctness and software completeness precede performance optimization. Every upstream failure that reveals an architectural bug should be reduced into a focused permanent regression before RTL is changed, and microarchitectural changes should rerun frozen executable hashes without silently recompiling them.
