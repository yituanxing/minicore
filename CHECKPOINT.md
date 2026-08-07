# AetherCore Supervisor Mode V1 checkpoint

## Frozen state

```text
freeze branch: freeze/smode-v1-minimal-trap
qualified commit: 9b80add28ef212cc9db2924926b2dc8f10f1a58f
base freeze: freeze/nuttx-13.0.0-p1-p3-protected
base commit: 5f3789a28f2b751a4b51fe9031a56ea07e129baa
```

The executable qualification basis is the exact qualified commit above. Documentation-only commits on the freeze branch do not change that basis.

## Architecture now qualified

The processor has progressed beyond the earlier Machine-only and U-mode checkpoints. The frozen architecture ladder now covers:

- RV32/RV64 five-stage in-order execution;
- RV64I/RV64M and qualified RV32IM software profiles;
- Zicsr Machine CSR behavior;
- precise synchronous traps and MRET;
- Machine timer interrupts, external interrupts, PLIC and WFI;
- preemptive context switching;
- U-mode execution and ECALL/syscall return;
- PMP isolation and isolated U-mode scheduling;
- RV32A word atomics driven by protected NuttX userspace;
- NuttX protected U-mode with trusted per-task kernel exception stacks;
- M/S/U privilege execution;
- Supervisor CSR state, synchronous exception delegation and SRET.

## Supervisor V1 boundary

Implemented and qualified:

- `sstatus`
- `stvec`
- `sscratch`
- `sepc`
- `scause`
- `stval`
- `sie`
- `sip`
- `medeleg`
- `mideleg`
- M -> S transition through MRET
- delegated synchronous exception entry to S-mode
- SIE/SPIE/SPP trap-state stacking
- SRET from S-mode
- legal SRET execution from M-mode using Supervisor-return semantics
- precise fail-closed privilege faults

Deliberately excluded from V1:

- `satp`
- Sv32 translation
- page-table walker
- TLB
- `sfence.vma`
- page faults
- S-level timer/external interrupt sources
- Supervisor WFI qualification

Because the current interrupt sources are Machine timer/external interrupts, `mideleg` remains WARL-zero in V1 rather than falsely advertising S-level interrupt delivery.

## Real executable qualification

The real GCC-built RV32IM/Zicsr image performs:

```text
M -> S
S ECALL -> delegated S trap -> SRET -> S
SRET -> U
U ECALL -> delegated S trap -> SRET -> U
```

It validates exact Supervisor trap state and fails closed on unexpected paths.

Frozen image contract:

```text
contract=rv32im-supervisor-v1-executable
march=rv32im_zicsr
modes=M,S,U
sv32=disabled
mideleg=WARL-zero
bytes=584
words=146
binary sha256=0c6dce9eb2f2c9fb0cdfe24e00335b47e215bfe4985d303c9d4c5195ab6e57e8
ELF sha256=3bfcbd7fd469f846f0669ffc50d08abf826978db3a253e6ac8efacdae8ee6d81
```

Runtime matrix:

```text
stall=0  PASS  142 cycles  97 committed instructions
stall=3  PASS  143 cycles  97 committed instructions
```

## Privilege boundary coverage

Permanent focused regressions prove:

- M-origin exceptions are never delegated to S;
- an undelegated S-origin exception enters M;
- S-mode Machine-CSR access traps as illegal instruction;
- U-mode SRET traps as illegal instruction;
- that illegal SRET may itself be delegated to S when configured;
- M-mode SRET is legal and follows Supervisor return state;
- legacy profiles without S retain their old CSR exposure boundary.

## Whole-project regression

Final Full Gate:

```text
run: 31197747822
job: 92930051977
conclusion: success
artifact id: 9001953079
artifact digest: sha256:713be9f8934bb19f1f90c5ec03b352d8b6280a84ff129ced893f7976b1adffbb
```

It passed the Supervisor V1 executable stage and the complete historical regression ladder through FreeRTOS, RV32/RV64 NEMU tests, U-mode/PMP scheduling, CoreMark, Embench and littlefs.

Final Fast Gate:

```text
run: 31197749504
job: 92930057425
conclusion: success
```

It re-passed focused privilege tests, the real Supervisor V1 executable, FreeRTOS WFI/IRQ paths and exact RV32 NEMU DiffTest on the same source head.

See [`docs/SMODE_V1_FREEZE.md`](docs/SMODE_V1_FREEZE.md) for the authoritative evidence record.

## Next checkpoint

The next bounded architecture milestone is **Sv32 virtual memory**, not more V1 widening.

The intended order is:

1. add `satp` and a minimal Sv32 translation contract;
2. add precise instruction/load/store page-fault behavior;
3. add page-table walking and a small TLB;
4. qualify `sfence.vma` and stale-translation invalidation;
5. drive the implementation with a real S-mode OS workload;
6. only then broaden toward OpenSBI/Linux-class software.

Supervisor V1 remains frozen as the physical-address M/S/U trap/delegation baseline.