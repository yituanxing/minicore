# NuttX P3 protected-isolation freeze

This file freezes the Apache NuttX 13.0.0 protected-userspace P1-P3 qualification on AetherCore.

## Frozen reference

- Qualification commit: `09d1ecfa467bb54e7ed657f85fa2e3ae6083f76b`
- Qualification branch: `agent/nuttx-protected-p3-fault-isolation`
- Freeze branch: `freeze/nuttx-13.0.0-p1-p3-protected`
- Protected workflow run: `31190880777`
- Flat N1-N4 non-regression run: `31190879008`
- Final commit statuses:
  - `nuttx/stage = success`
  - `umode/nuttx-protected = success`
  - `umode/nuttx-p3 = success`

The freeze branch may contain documentation-only commits after the qualification commit. The executable qualification basis remains the exact commit above.

## What P3 proves

P3 extends the already-qualified P1/P2 RV32IMA protected path with an independent trusted kernel stack and a deliberate malicious-userspace PMP fault.

The qualified configuration remains pure M-mode kernel + U-mode userspace with PMP. It does not enable an address environment, MMU, or S-mode:

- `CONFIG_BUILD_PROTECTED=y`
- `CONFIG_ARCH_USE_MPU=y`
- `CONFIG_RISCV_PERCPU_SCRATCH=y`
- `CONFIG_ARCH_KERNEL_STACK=y`
- `CONFIG_ARCH_KERNEL_STACKSIZE=1568`
- `CONFIG_ARCH_ADDRENV` disabled
- `CONFIG_ARCH_USE_MMU` disabled
- `CONFIG_ARCH_USE_S_MODE` disabled
- ISA profile: `rv32ima_zicsr_zifencei`
- implemented PMP entries: 4

NuttX 13.0.0 already contains RISC-V exception-entry/return machinery that switches through the per-CPU scratch `ksp` when `CONFIG_ARCH_KERNEL_STACK` is enabled. The P3 overlay narrowly exposes and wires that existing kernel-stack path for the pure protected/PMP configuration without enabling the unrelated ADDRENV/MMU/S-mode lifecycle.

## P3-A — independent kernel stack

P3-A contract:

`nuttx-13.0.0-aethercore-p3a-independent-kernel-stack-build-v1`

Final artifact:

`aethercore-nuttx-p3a-kstack-31190880777-1`

Artifact archive digest:

`sha256:84ca5c1bee913b51315f98345a98f7b7cedbea083605e96d63a0d13784952075`

The result records:

- `kernel_stack=enabled`
- `kernel_stack_size=1568`
- `kernel_stack_machine_path=qualified`
- U-mode userspace / M-mode kernel
- no ADDRENV, MMU, or S-mode

Frozen P3-A file hashes:

- kernel ELF: `c48218d63fff3625ffbb2b4e234e807978324799d57a357c4984351b9583d070`
- user ELF: `edd2bc8f79ba5fe0af1240cf1fdd2eaa207d6bb73f2ec2916629b70977332226`
- kernel flat image: `b4bc63ce697996c4c7ff218befe5ddada849d68e998884c29bba285c8d5cb8dc`
- user flat image: `927ae35da73f07e639729c177ca022b76879555a2e52a8a632e61f15eeda6729`
- combined protected image: `b557fd8be96e236c080741b006c2d4b2ed596f15b82ff23416b80af735090a1a`
- resolved config: `2ace4477acdcfd53d3f9a13a1d05a4cbfa11b230a8d640f31ce27f07c725b059`

## P3-B — PMP fault isolation and shell recovery

P3-B contract:

`nuttx-13.0.0-aethercore-p3b-pmp-fault-isolation-v1`

Final artifact:

`aethercore-nuttx-p3b-fault-isolation-31190880777-1`

Artifact archive digest:

`sha256:67e60f73e26cf774c8fd6227aa04d185be0d3b65ef04e2c5893a37d4eab037d6`

The test replaces the pinned `hello` application with a bounded fault probe. From real U-mode it deliberately loads from kernel flash address `0x80000000`. The required chain is:

1. `P3_FAULT_BEGIN address=0x80000000` is printed by the U-mode task.
2. AetherCore raises exactly one U-mode load access fault: `mcause=5`, `mtval=0x80000000`.
3. Exception entry uses the trusted kernel-stack path.
4. NuttX reports the precise load access fault.
5. NuttX reports `Segmentation fault in hello` and cancels the offending task.
6. The fault does not reach the post-fault `P3_FAULT_SURVIVED` sentinel.
7. There is no kernel panic, unexpected ISR, timeout, or no-retirement stall.
8. NuttShell returns to a new `nsh>` prompt.
9. The command interval contains real U-mode retirement, ECALL-from-U, and MRET evidence.

Both deterministic memory timing profiles passed.

### stall-period=0

- fault PC: `0x8004f2cc`
- `mcause=5`
- `mtval=0x80000000`
- NuttX: `Segmentation fault in hello (PID 3: hello)`
- total evidence: `user-commits=12750`, `u-ecalls=36`, `mrets=78`
- command-phase evidence: `user-commits=9002`, `u-ecalls=32`, `mrets=62`
- returned to NuttShell at 425,513 cycles

### stall-period=3

- fault PC: `0x8004f2cc`
- `mcause=5`
- `mtval=0x80000000`
- NuttX: `Segmentation fault in hello (PID 3: hello)`
- total evidence: `user-commits=12750`, `u-ecalls=36`, `mrets=82`
- command-phase evidence: `user-commits=9002`, `u-ecalls=32`, `mrets=64`
- returned to NuttShell at 478,079 cycles

Frozen P3-B file hashes:

- combined protected image: `b28a68588d6bd62b705787ac082d8307c4ac748d56fd61f0c920b5d16076f884`
- kernel ELF: `a1d3864a6be9ba50c6161fa95b0d4e61d8d66a0d91821f58d110c922da0802f1`
- user ELF: `016c556d10b5a35b0a2eb6b3f8f06e4820ede6055f06eeb545173ffe2995f99d`
- cached protected simulator: `1a0c040e0a155930cf1be30cb68d561285ea75294c36d5166894aa1ca60c2296`
- stall=0 log: `742215c5e7180d9f76052f5754a679d884907c8e4278d8d50d51152806cad670`
- stall=3 log: `981f29239069a3a5bcb7bbca7879235569c0adf42164745bf9e59b76cdce88d6`

## Final regression evidence

The same qualification commit also re-ran the frozen N1-N4 stage in workflow run `31190879008`; N1 build, N2 NSH, N3 timer/scheduler, and N4 UART RX through PLIC all passed.

Within protected run `31190880777`, the complete sequence also passed in order:

- static protected/P3 contracts
- pinned source verification and host tools
- P1 protected RV32IMA build
- P2 real NuttX U-mode NSH + `hello`
- P3-A independent kernel-stack build
- P3-B fault isolation and NSH recovery

## Maintenance rule

This freeze proves the current single-hart AetherCore/NuttX 13.0.0 protected contract. It is not permission to weaken the security assertions or silently move the evidence reference.

A new qualification is required before this freeze may move if a change affects any of the following:

- privilege/trap/MRET semantics
- PMP matching or access-fault behavior
- RV32A atomics used by protected NuttX
- per-CPU scratch or kernel-stack entry/return logic
- protected NuttX overlays or P3 fault probe
- scheduler/task cancellation behavior relevant to fault recovery
- protected simulator memory/timer/PLIC/UART behavior
- pinned NuttX/apps sources or resolved configuration

Future protected milestones must build on this frozen P3 contract rather than retroactively changing P1/P2/P3 evidence.