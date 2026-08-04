# AetherCore Zephyr bring-up

## Baseline

The initial port is pinned to **Zephyr v3.7.2 LTS** with the matching **Zephyr SDK 0.16.9** RISC-V toolchain. It uses the hardware-model-v2 board/SoC layout and remains fixed until the first complete AetherCore Zephyr contract is frozen.

The branch starts from the accepted FreeRTOS platform head `69d862344ee1964db2d319c36603cac58d40e5b3`. FreeRTOS remains a permanent regression workload; Zephyr becomes the primary source of new CPU requirements.

## First workload

The first application is deliberately small and deterministic:

1. boot in RV32 M-mode;
2. initialize the console UART;
3. create one preemptible worker thread;
4. exchange four semaphore hand-offs between `main` and the worker;
5. exercise the system timer through `k_sleep()`;
6. print `AETHERCORE ZEPHYR PASS handoffs=4`;
7. call the semantic `aethercore_exit(0)` platform API, which writes the simulator exit register at `0x10000008`.

The application never embeds the exit MMIO address directly. Networking, filesystems, userspace, SMP and power management are not enabled in the first milestone.

## Frozen PLIC software ABI

The internal PLIC state stays compact, but its MMIO interface follows the conventional one-based software layout:

- source ID zero is reserved;
- priority source zero reads as zero and ignores writes without a bus fault;
- pending and enable bit zero are permanently reserved;
- UART RX source ID one is represented by pending/enable bit one;
- the first single-word profile supports at most 31 real sources;
- `riscv,ndev = <2>` describes table entries zero and one, while only source one is connected.

This layout is shared by Zephyr and the permanent FreeRTOS regression. FreeRTOS now enables UART RX with `1 << source_id`, not `1 << (source_id - 1)`.

## Milestones

### Z0 — host contract

- pin the Zephyr revision and manifest;
- freeze the application source and expected output;
- validate repository shape on a GitHub-hosted runner;
- do not consume the self-hosted runner.

### Z1 — board and SoC build

- add out-of-tree AetherCore board and SoC definitions;
- describe RAM, UART, exit, timer and PLIC in Devicetree;
- compile `zephyr.elf` and `zephyr.bin` reproducibly;
- inspect ISA attributes and linker layout without running RTL;
- prove that `aethercore_exit` is present in the linked map.

### Z2 — boot and console

- load the Zephyr binary in `AetherCoreSimTop`;
- reach `main()` and emit the boot signature;
- validate startup, BSS, stack, `gp`, `mtvec` and UART output;
- require `exitValid` with `exitCode=0` after the final PASS signature.

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

1. **Host gate:** branch pushes run static contracts, a Chisel compile and a real `west build` on `ubuntu-latest`. The job has a 30-minute cold-start hard timeout, uses only SDK 0.16.9 `riscv64-zephyr-elf`, caches ccache/SDK state and cancels obsolete runs.
2. **Observable push workflow:** the hosted job writes a standard commit status named `zephyr/host-gate`. The status is `pending` while the job runs and becomes `success`, `failure` or `error` at completion. Its target URL is the exact Actions run, allowing logs to be fetched without opening a PR.
3. **No automatic self-hosted PR gate during exploration:** the Zephyr branch stays without a PR until Z1 has a reproducible build contract. This prevents the repository-wide Fast Gate from consuming the runner for every metadata edit.
4. **Stage gate:** RTL execution is requested only for a coherent milestone. It uses one cached toolchain, one cached Zephyr workspace, incremental simulator outputs and `cancel-in-progress` concurrency.
5. **Failure locality:** a stage gate stops at the first failed layer and emits the exact command, output signature and artifact hashes. It does not continue into unrelated CPU regressions.
6. **Full gate:** the historical FreeRTOS/RV32/RV64/CoreMark/Embench/littlefs gate runs only at a milestone freeze, never for ordinary Zephyr iteration.
7. **Front-end progress continues:** while a stage run executes, repository analysis, Devicetree/Kconfig work and the next small patch continue independently; no workflow is used as a substitute for design work.

## Hosted Z1 evidence

The hosted build uses `tools/ci/zephyr_host_build.sh` as the single reproducible command path. It requires and archives:

```text
build/zephyr-host/zephyr/zephyr.elf
build/zephyr-host/zephyr/zephyr.bin
build/zephyr-host/zephyr/zephyr.map
build/zephyr-host/zephyr/zephyr.dts
build/zephyr-host/zephyr/.config
build/zephyr-host/evidence/result.txt
build/zephyr-host/evidence/artifacts.sha256
```

The build fails closed if Zephyr enables unsupported RISC-V A or C extensions, loses RV32IM/Zicsr/Zifencei, changes the board/SoC selection, drops the generic RISC-V linker script or exit service, or no longer emits the frozen RAM/UART/exit/PLIC/timer nodes. Hidden files are explicitly retained so `.config` remains part of the evidence artifact.

## Persistent cache layout

The eventual self-hosted stage gate will reuse:

```text
~/.cache/aethercore/zephyr/
├── zephyr-v3.7.2/
├── modules-v3.7.2/
├── sdk-0.16.9/
├── host-tools/
└── builds/
    └── <stage-contract>/
```

Downloads are checksum-pinned. A cache miss may provision a dependency, but a normal source-only iteration must not redownload Zephyr, its modules, the compiler, Verilator or NEMU.

## Exit rule

A milestone is complete only when its positive workload, negative probe, deterministic output contract and relevant previous-stage regression all pass. Passing a download, compile, boot message or one scheduler iteration alone is not a milestone.