# AetherCore Zephyr bring-up

## Baseline

The initial port is pinned to **Zephyr v3.7.2 LTS**. It uses the hardware-model-v2 board/SoC layout and remains fixed until the first complete AetherCore Zephyr contract is frozen.

The branch starts from the accepted FreeRTOS platform head `69d862344ee1964db2d319c36603cac58d40e5b3`. FreeRTOS remains a permanent regression workload; Zephyr becomes the primary source of new CPU requirements.

## First workload

The first application is deliberately small and deterministic:

1. boot in RV32 M-mode;
2. initialize the console UART;
3. create one preemptible worker thread;
4. exchange four semaphore hand-offs between `main` and the worker;
5. exercise the system timer through `k_sleep()`;
6. print `AETHERCORE ZEPHYR PASS handoffs=4` and exit through the simulator contract.

This workload separates architecture failures from Zephyr subsystem complexity. Networking, filesystems, userspace, SMP and power management are not enabled in the first milestone.

## Milestones

### Z0 — host contract

- pin the Zephyr revision and manifest;
- freeze the application source and expected output;
- validate repository shape on a GitHub-hosted runner;
- do not consume the self-hosted runner.

### Z1 — board and SoC build

- add out-of-tree AetherCore board and SoC definitions;
- describe RAM, UART, timer and PLIC in Devicetree;
- compile `zephyr.elf` and `zephyr.bin` reproducibly;
- inspect ISA attributes and linker layout without running RTL.

### Z2 — boot and console

- load the Zephyr binary in `AetherCoreSimTop`;
- reach `main()` and emit the boot signature;
- validate startup, BSS, stack, `gp`, `mtvec` and UART output.

### Z3 — timer and scheduling

- deliver Machine timer interrupts;
- complete four semaphore hand-offs between two threads;
- prove context-save/restore and `k_sleep()` progress;
- run with memory stall periods `0,2,3,5,7`.

### Z4 — external interrupt platform

- bind Zephyr UART interrupt handling to the existing PLIC path;
- exercise an ISR-to-thread wake-up and work queue;
- retain the FreeRTOS external-interrupt regression unchanged.

### Z5 — isolation and broader kernel services

- add Zephyr userspace/PMP once the M-mode kernel is stable;
- add atomics only when a concrete Zephyr configuration requires the RISC-V A extension;
- then qualify mutexes, message queues, work queues and system heap behavior.

## Runner policy

The self-hosted `minicore-wsl` runner must never be the development scheduler.

1. **Host gate:** branch pushes run only static contract tests on `ubuntu-latest`. It has a five-minute timeout and cancels obsolete runs.
2. **No automatic self-hosted PR gate during exploration:** the Zephyr branch stays without a PR until Z1 has a reproducible build contract. This prevents the repository-wide Fast Gate from consuming the runner for every metadata edit.
3. **Stage gate:** RTL execution is requested only for a coherent milestone. It uses one cached toolchain, one cached Zephyr workspace, incremental simulator outputs and `cancel-in-progress` concurrency.
4. **Failure locality:** a stage gate stops at the first failed layer and emits the exact command, output signature and artifact hashes. It does not continue into unrelated CPU regressions.
5. **Full gate:** the historical FreeRTOS/RV32/RV64/CoreMark/Embench/littlefs gate runs only at a milestone freeze, never for ordinary Zephyr iteration.
6. **Front-end progress continues:** while a stage run executes, repository analysis, Devicetree/Kconfig work and the next small patch continue independently; no workflow is used as a substitute for design work.

## Persistent cache layout

The eventual self-hosted stage gate will reuse:

```text
~/.cache/aethercore/zephyr/
├── zephyr-v3.7.2/
├── modules-v3.7.2/
├── sdk/
├── host-tools/
└── builds/
    └── <stage-contract>/
```

Downloads are checksum-pinned. A cache miss may provision a dependency, but a normal source-only iteration must not redownload Zephyr, its modules, the compiler, Verilator or NEMU.

## Exit rule

A milestone is complete only when its positive workload, negative probe, deterministic output contract and relevant previous-stage regression all pass. Passing a download, compile, boot message or one scheduler iteration alone is not a milestone.