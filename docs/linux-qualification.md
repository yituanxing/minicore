# AetherCore Linux qualification contract

A Linux boot message is not sufficient evidence that the processor or platform is correct. Claims must identify the exact CPU profile, Linux release, firmware, toolchain, device tree, root filesystem, and test matrix.

## Qualification layers

1. **Construction**
   - pinned toolchain and source revisions
   - reproducible kernel, firmware, DTB, initramfs, and platform artifacts
   - no compiler, emulator, or external CPU fallback in the DUT path

2. **Architectural execution**
   - RISC-V architecture tests for every advertised extension and privilege mode
   - directed precise exception, interrupt, return, page-fault, and atomic tests
   - randomized retirement-by-retirement differential testing under memory backpressure
   - explicit and counted reference shadows for any unsupported reference-model event

3. **Linux boot semantics**
   - upstream `ARCH=riscv defconfig` for a pinned Linux release
   - only platform description and standard-driver enablement; no disabling required CPU semantics
   - OpenSBI/firmware entry, kernel entry, paging enable, scheduler start, initramfs `/init`, and userspace shell milestones

4. **Userspace behavior**
   - fork/exec/wait and signals
   - mmap, mprotect, page faults, copy-on-write, and memory pressure
   - futexes and scheduler contention
   - pipes, poll/epoll, TTY, clocks, and timers
   - filesystem create/read/write/rename/fsync/mount tests
   - relevant kselftest and LTP subsets

5. **Stress and negative evidence**
   - repeated cold boots
   - deterministic memory stalls and interrupt jitter
   - long multicase process, VM, filesystem, and syscall stress
   - deliberate architectural mismatch and fault injection that must be detected

## Claim levels

- **Kernel entered**: execution reaches early Linux assembly only.
- **Linux boots**: the pinned kernel reaches `/init` or a userspace shell reproducibly.
- **Defconfig qualified**: the pinned upstream RISC-V defconfig boots through the standard platform stack and passes the frozen runtime suite.
- **Supported Linux profile qualified**: architecture, boot, userspace, stress, and negative gates all pass for the named profile.

The final claim must use the scoped form:

> Linux `<release>` upstream RISC-V defconfig boots to userspace on AetherCore `<profile>` / platform `<version>` and passes qualification suite `<contract>`.

It must never be shortened to an unqualified statement that “Linux is correct.”

## Planned Linux CPU profile

The target is a single-hart RV64 platform developed in this order:

1. complete RV64 A (`Zalrsc` and `Zaamo`) without advertising partial A support
2. M/S/U privilege transitions, delegation, supervisor CSRs, interrupts, and `SRET`
3. Sv39 translation, TLBs, `SFENCE.VMA`, and precise page faults
4. standard timer and external interrupt platform, PLIC, UART, and device tree
5. OpenSBI and deterministic Linux early boot
6. upstream defconfig userspace and runtime qualification

Caches are not required for the first logical boot claim, but they are required before describing the platform as practically usable.
