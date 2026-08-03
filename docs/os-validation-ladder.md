# External OS validation ladder

This document defines how AetherCore will use existing RISC-V operating systems as progressively stronger CPU verification workloads.

The goal is not to fork and redesign each OS. The goal is to keep upstream software recognizable, add the smallest possible board/port layer, and use each OS to expose a new group of architectural mechanisms.

## Validation policy

Every imported OS must pass the following sequence before it is allowed into the RTL gate:

1. Pin an upstream release or commit and record its source hash.
2. Run the unmodified upstream target on its documented QEMU/reference platform.
3. Audit the produced ELF attributes and disassembly for required ISA extensions.
4. Add only the minimum AetherCore board, timer, interrupt-controller, UART, linker and boot glue.
5. Run the same image on the NEMU/reference path before running it on RTL.
6. Record deterministic success markers, architectural event counts and timeout bounds.
7. Add at least one negative probe that deliberately breaks an architectural mechanism and proves the gate detects it.
8. Keep upstream kernel changes separate from board/port changes. Suspected upstream bugs must first reproduce on the upstream reference platform.

This separation prevents an OS defect from being mistaken for an RTL defect:

- fails on upstream QEMU/reference: software or configuration defect;
- passes upstream but fails on both NEMU and RTL: AetherCore platform/port defect;
- passes NEMU but fails RTL: CPU or RTL platform defect;
- fails only under injected stalls or interrupts: precision, replay or timing-sensitive architectural defect.

## Recommended ladder

| Stage | Workload | First configuration | Main CPU mechanisms exercised | Current blockers | Decision |
|---|---|---|---|---|---|
| 0 | AetherCore isolated scheduler | RV32IMU, M-mode kernel, two U-mode tasks, PMP | precise traps, MRET, timer preemption, full context switch, syscalls, dynamic PMP | exact-head self-hosted gate still queued | Complete and freeze first |
| 1 | FreeRTOS Kernel | RV32 single-hart M-mode, machine timer, UART | compiler-generated context frames, periodic tick, preemption, priorities, queues, semaphores, critical sections, heap | board/timer glue and exact ISA audit | **First external OS** |
| 2 | Zephyr | custom `aethercore_rv32im` board, UP M-mode; then userspace/PMP | richer scheduler, nested kernel paths, syscalls, object validation, user stacks, PMP reprogramming, drivers | determine whether the selected configuration can avoid A/C and which Z* extensions are mandatory | **Second external OS** |
| 3 | Apache NuttX | RV32 monolithic `nsh`-class build; later protected/kernel build | tasks, signals, POSIX APIs, allocator, VFS, shell, ROMFS/FAT, device drivers, user/kernel separation | QEMU-virt device assumptions; likely more ISA/platform work | **Third external OS** |
| 4 | xv6-riscv | upstream RV64 multiprocessor kernel | S-mode, SBI boundary, Sv39, page tables, TLB invalidation, page faults, processes, fork/exec/wait, filesystem, virtio, spinlocks | current core is RV32IMU and lacks S-mode/MMU/A/RV64 path | Long-term architectural target |
| 5 | Linux | minimal RV32/RV64 kernel and initramfs | full privilege/MMU/interrupt/device/ABI stress, demand paging, VFS, drivers, userspace | requires the preceding architecture and platform layers | Final system target |

## Stage 1: FreeRTOS qualification

Pin the current selected FreeRTOS Kernel release instead of tracking its moving branch.

Initial workload:

- two CPU-bound tasks at different priorities;
- one periodic task driven by the machine timer tick;
- queue producer/consumer pair;
- binary semaphore unblock from the tick/interrupt path;
- task delay and wakeup ordering;
- heap allocation/free churn with integrity checks;
- deliberate register signatures in every integer register across context switches;
- deterministic final UART signature and exact task counters.

Acceptance requirements:

- boot and first task start;
- at least 1,000 timer ticks;
- both voluntary and preemptive context switches;
- priority inversion test without priority inheritance, followed by the mutex/priority-inheritance variant;
- queue and semaphore stress;
- no register, stack canary or heap corruption;
- repeat under the existing deterministic stall modes;
- negative probes for timer delivery, saved `mepc`, saved `mstatus` and one callee-saved register.

FreeRTOS is first because its RISC-V port is compact and machine-mode oriented. It adds real compiler-generated scheduler and synchronization paths without requiring S-mode or virtual memory.

## Stage 2: Zephyr qualification

Start with a custom AetherCore board definition rather than attempting to emulate every QEMU `virt` peripheral.

Bring-up order:

1. `hello_world` and UART.
2. synchronization sample with two threads.
3. timer, sleep and preemption tests.
4. kernel mutex, semaphore, queue and memory-pool tests.
5. userspace disabled, to establish the basic kernel baseline.
6. userspace plus PMP, privileged stacks and syscall argument validation.
7. selected upstream architecture and kernel test suites.

Before coding the board port, compile the chosen upstream configuration and reject it if the image contains unsupported A, C, floating-point or other instructions. If Zephyr requires a small missing architectural extension such as `FENCE.I`, add and verify that extension as an explicit CPU milestone instead of silently patching the OS.

## Stage 3: NuttX qualification

NuttX is the first intentionally broad operating-system workload. It should be introduced only after FreeRTOS and Zephyr are stable, because failures will cross more subsystems.

Bring-up order:

1. minimal RV32 monolithic kernel and serial console;
2. NuttShell with built-in commands;
3. task creation, signals, timers and POSIX synchronization;
4. ROMFS and file operations;
5. allocator and filesystem stress;
6. protected/kernel build with separate user image;
7. optional VirtIO block/network only after the CPU and basic platform are stable.

NuttX is especially useful for finding ABI, stack, allocator, interrupt nesting, unaligned-access and long-running state-corruption defects.

## Stage 4: xv6-riscv qualification

Do not port current xv6 down to RV32 merely to make it run early. That would turn the verification project into an OS rewrite and weaken provenance.

Use upstream xv6 when AetherCore has:

- RV64 integer execution;
- atomic A extension;
- S-mode CSRs and trap delegation;
- an SBI/OpenSBI-compatible M-mode layer;
- Sv39 translation and page-table walks;
- TLB and `SFENCE.VMA`;
- supervisor timer/external/software interrupts;
- QEMU-virt-compatible UART, PLIC/interrupt controller and VirtIO block, or a clearly isolated xv6 board layer.

Then enable xv6 progressively:

1. early console and kernel boot;
2. allocator and page-table setup;
3. user-mode `init` and system calls;
4. fork/exec/wait;
5. filesystem and VirtIO disk;
6. multiprocessor boot and spinlocks;
7. full upstream user tests.

## Cross-OS mechanism coverage

| Mechanism | Existing microkernel | FreeRTOS | Zephyr | NuttX | xv6 |
|---|---:|---:|---:|---:|---:|
| M-mode boot/traps | yes | yes | yes | yes | SBI layer |
| timer interrupt | yes | yes | yes | yes | yes |
| preemptive context switch | yes | yes | yes | yes | yes |
| synchronization primitives | limited | strong | strong | strong | strong |
| U-mode | yes | optional/custom | yes | protected build | yes |
| PMP isolation | yes | optional/custom | yes | possible protected path | no, uses MMU |
| S-mode/delegation | no | no | optional | optional | required |
| virtual memory | no | no | not the first target | Sv32 configurations exist | Sv39 required |
| filesystem/device stack | no | no | optional | strong | strong |
| multiprocessor/atomics | no | optional later | later | later | required |

## Immediate execution order

1. Finish and merge the exact-head isolated-scheduler gate.
2. Create a pinned FreeRTOS source-fetch script and source manifest.
3. Build the unmodified RISC-V FreeRTOS port for a reference target and audit its instructions and CSR use.
4. Implement only AetherCore timer/UART/linker/startup glue.
5. Add the deterministic two-task, queue, semaphore and heap workload.
6. Run it first on the reference model, then on RTL with stall and negative-probe matrices.
7. Start Zephyr qualification only after the FreeRTOS gate is frozen.

## Upstream sources

- FreeRTOS Kernel: https://github.com/FreeRTOS/FreeRTOS-Kernel
- Zephyr: https://github.com/zephyrproject-rtos/zephyr
- Apache NuttX: https://github.com/apache/nuttx
- NuttX applications: https://github.com/apache/nuttx-apps
- xv6-riscv: https://github.com/mit-pdos/xv6-riscv
