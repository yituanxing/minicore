# Apache NuttX bring-up on AetherCore

This directory records the bounded Apache NuttX 13.0.0 qualification contract for AetherCore. The upstream NuttX and apps trees are pinned together in `manifest.env`; CI must never qualify an unpinned branch tip.

The Zephyr baseline remains frozen at `freeze/zephyr-v3.7.2-z1-z4`. The flat NuttX line is frozen at `freeze/nuttx-13.0.0-n1-n4`. The protected-userspace line through fault isolation is frozen at `freeze/nuttx-13.0.0-p1-p3-protected`.

## Flat NuttX stages

- **N1 — pinned host build:** build NuttX 13.0.0 and matching apps from the upstream `rv-virt:nsh` configuration, force `rv32im_zicsr_zifencei`, and keep A/C/F/D/V, HostFS, and semihosting disabled.
- **N2 — AetherCore boot and console:** link at `0x80000000`, enter `nx_start()`, initialize the native polling UART, start NuttShell, and reach `nsh>`.
- **N3 — timer and scheduler:** connect the machine timer and pass the bounded timed-task/`ostest` qualification under deterministic stalls.
- **N4 — external interrupts and driver path:** receive injected UART bytes through PLIC source 1, execute an NSH command through the serial file-descriptor path, complete the interrupt, and return to a second prompt.

### Frozen N1-N4 platform contract

This flat line remains RV32IM even though the protected line uses RV32IMA.

- ISA: RV32IM + Zicsr + Zifencei; no A, C, F, D, or V.
- RAM: `0x80000000 .. 0x83fffff7` (`0x03fffff8` bytes).
- UART TX: `0x10000000`.
- UART RX block: `0x10000100`.
- simulation exit: `0x10000008`.
- CLINT-compatible timer: `mtimecmp=0x02004000`, `mtime=0x0200bff8`.
- PLIC base: `0x0c000000`; source 1 is UART RX.
- The PLIC profile has one M-mode 32-bit enable word at offset `0x2000`; software must not access the wider QEMU enable word at `0x2004`.

The original N1-N4 qualification is frozen as `nuttx-13.0.0-aethercore-n4-uart-rx-plic-nsh-v1`, based on commit `6326c621c9307a5785a58597dd382ae5ca506d60`, workflow run `31113948586`, and artifact `aethercore-nuttx-n4-31113948586-1` with archive digest `sha256:7cca849c1086195332c57986130607708d3a08b61b8f0ff2542dd46f4c9e1216`.

The final P3 qualification commit also reran the complete flat stage as workflow run `31190879008`; N1, N2, N3, and N4 all remained green.

## Protected userspace stages

The protected line is separate from the flat N1-N4 line. NSH here is NuttX's own **NuttShell**, not Linux `/bin/sh` or BusyBox `ash`.

- **P1 — separated RV32IMA images:** build an M-mode `nuttx` kernel and a distinct U-mode `nuttx_user` image from upstream `rv-virt:pnsh`. User flash is `0x80040000..0x8007ffff` (RX) and user RAM is `0x80200000..0x802fffff` (RW). The profile is `rv32ima_zicsr_zifencei`, with four implemented PMP entries.
- **P2 — real OS-backed U-mode execution:** boot the protected image, reach real NuttShell, inject `hello`, require `Hello, World!!`, return to a second prompt, and prove the command interval contains U-mode retirements, ECALL-from-U traps, and MRET transitions. Both `stall-period=0` and `3` must pass.
- **P3-A — independent trusted kernel stack:** enable NuttX's existing RISC-V per-task kernel-stack machinery for the pure protected/PMP configuration while keeping ADDRENV, MMU, and S-mode disabled. Exception entry/return must use the machine-mode trusted kernel-stack path.
- **P3-B — PMP fault isolation and recovery:** deliberately execute a U-mode load from kernel flash `0x80000000`, require one precise load-access fault (`mcause=5`, `mtval=0x80000000`), require NuttX to cancel only the offending task, forbid the post-fault survivor sentinel and kernel panic, and require NuttShell to return. Both `stall-period=0` and `3` must pass.

### Why protected NuttX added RV32A

The protected line initially entered genuine NuttX U-mode as RV32IMU, then failed inside `__atomic_compare_exchange_4`. With A disabled, NuttX libc selected a software atomic fallback which ultimately tried to manipulate machine interrupt state through `mstatus` from U-mode. AetherCore correctly raised an illegal instruction.

That real OS failure drove the RV32A implementation. AetherCore now implements the word operations required by the current NuttX userspace: `LR.W`, `SC.W`, and `AMOSWAP/ADD/XOR/AND/OR/MIN/MAX/MINU/MAXU.W`. The current platform is single-hart, single-master, in-order, with no cache hierarchy, so reservation and AMO read-modify-write sequencing remain internal to MEM. `aq`/`rl` encodings are accepted on the current strongly ordered path. This is the qualified atomic contract for this platform, not a claim about future coherent multi-hart systems.

The pre-atomic `rv32imuPmpOsSoftware` profile remains available as a regression profile. Active protected NuttX uses `rv32imauPmpOsSoftware` (`rv32ima_zicsr_zifencei`, M+U, PMP4).

## P1/P2 historical qualification

The first fully green RV32IMA protected P1/P2 qualification was workflow run `31150611994` on commit `f2aacbb95f43e2fb70c42a6f60a3b0bff5803700`.

Its P1 user ELF contained 19 audited A-extension instructions: 2 `amoadd.w`, 3 `amoadd.w.aq`, 1 `amoor.w.aq`, 1 `amoswap.w`, 6 `lr.w`, 1 `sc.w`, and 5 `sc.w.aq`.

Both P2 timing profiles reached real NuttShell, executed `hello`, printed `Hello, World!!`, and returned to a second prompt. That result qualified the privilege transition, PMP user code/data boundary, ECALL dispatch/return path, timer/interrupt coexistence, the RV32A libc path, and genuine U-mode application execution.

P3 supersedes the old P1/P2 limitation that system-call handling used the caller's user stack. P3 does **not** enable ADDRENV or an MMU; instead it narrowly exposes and wires NuttX 13.0.0's already-existing RISC-V kernel-stack storage and exception machinery for pure protected/PMP operation.

## Frozen P1-P3 protected qualification

The protected isolation checkpoint is frozen at executable qualification commit:

`09d1ecfa467bb54e7ed657f85fa2e3ae6083f76b`

Protected workflow run:

`31190880777`

Final status contexts on that exact commit:

- `umode/nuttx-protected = success`
- `umode/nuttx-p3 = success`
- `nuttx/stage = success` via flat non-regression run `31190879008`

The authoritative detailed freeze record is [`P3_FREEZE.md`](P3_FREEZE.md).

### P3-A frozen evidence

Contract: `nuttx-13.0.0-aethercore-p3a-independent-kernel-stack-build-v1`.

Artifact: `aethercore-nuttx-p3a-kstack-31190880777-1`.

Archive digest: `sha256:84ca5c1bee913b51315f98345a98f7b7cedbea083605e96d63a0d13784952075`.

Resolved contract:

- kernel stack enabled, 1568 bytes
- `kernel_stack_machine_path=qualified`
- U-mode userspace / M-mode kernel
- ADDRENV disabled
- MMU disabled
- S-mode disabled
- `rv32ima_zicsr_zifencei`
- PMP4

### P3-B frozen evidence

Contract: `nuttx-13.0.0-aethercore-p3b-pmp-fault-isolation-v1`.

Artifact: `aethercore-nuttx-p3b-fault-isolation-31190880777-1`.

Archive digest: `sha256:67e60f73e26cf774c8fd6227aa04d185be0d3b65ef04e2c5893a37d4eab037d6`.

Both timing profiles executed the same deliberate U-mode read of `0x80000000`, produced `mcause=5` and `mtval=0x80000000`, caused `Segmentation fault in hello (PID 3: hello)`, and returned to NuttShell without a kernel panic.

- `stall-period=0`: total `user-commits=12750`, `u-ecalls=36`, `mrets=78`; command phase `9002/32/62`; second prompt at 425,513 cycles.
- `stall-period=3`: total `user-commits=12750`, `u-ecalls=36`, `mrets=82`; command phase `9002/32/64`; second prompt at 478,079 cycles.

The faulting PC in both runs was `0x8004f2cc`. The post-fault `P3_FAULT_SURVIVED` sentinel was not reached.

Frozen P3-B hashes:

- combined protected image: `b28a68588d6bd62b705787ac082d8307c4ac748d56fd61f0c920b5d16076f884`
- kernel ELF: `a1d3864a6be9ba50c6161fa95b0d4e61d8d66a0d91821f58d110c922da0802f1`
- user ELF: `016c556d10b5a35b0a2eb6b3f8f06e4820ede6055f06eeb545173ffe2995f99d`
- cached protected simulator: `1a0c040e0a155930cf1be30cb68d561285ea75294c36d5166894aa1ca60c2296`
- stall=0 log: `742215c5e7180d9f76052f5754a679d884907c8e4278d8d50d51152806cad670`
- stall=3 log: `981f29239069a3a5bcb7bbca7879235569c0adf42164745bf9e59b76cdce88d6`

## Workflow and maintenance rules

Ordinary NuttX qualification remains bounded and self-hosted with persistent source, host-tool, and simulator caches. Fast and Full gates remain outside ordinary NuttX iteration unless a CPU/SoC change requires them.

Every qualification stage is fail-closed and stores resolved configuration, build logs, ELF metadata, image hashes, and the pinned upstream identity used for the result.

The flat N1-N4 freeze must be requalified before its reference moves if pinned sources, flat overlays, timer/IRQ/UART/PLIC contracts, simulator behavior, or relevant RTL change.

The protected P1-P3 freeze must be requalified before its reference moves if a change affects privilege/trap/MRET semantics, PMP matching or access faults, RV32A atomics, per-CPU scratch/kernel-stack entry and return, protected overlays, the fault probe, task cancellation/scheduler recovery, protected simulator devices, pinned sources, or resolved configuration.

Future protected milestones must build on the frozen P3 contract rather than retroactively weakening or rewriting P1/P2/P3 evidence.