# Supervisor Mode V1 Freeze

## Frozen executable basis

The executable Supervisor-mode V1 qualification is frozen at:

```text
branch: freeze/smode-v1-minimal-trap
qualified source branch: agent/smode-v1-minimal-trap
qualified commit: 9b80add28ef212cc9db2924926b2dc8f10f1a58f
base: freeze/nuttx-13.0.0-p1-p3-protected
base commit: 5f3789a28f2b751a4b51fe9031a56ea07e129baa
```

Any documentation-only commits after the qualified commit do not change the executable qualification basis.

## Architecture boundary

V1 adds the minimum Supervisor-mode architecture needed for a real M/S/U trap-return loop:

- M, S and U privilege execution;
- Supervisor CSRs: `sstatus`, `stvec`, `sscratch`, `sepc`, `scause`, `stval`, `sie`, `sip`;
- Machine delegation CSRs `medeleg` and `mideleg`;
- synchronous exception delegation from U/S to S;
- precise Supervisor trap entry with SIE/SPIE/SPP stacking;
- SRET retirement and return semantics;
- M-mode execution of SRET using Supervisor-return semantics;
- fail-closed privilege checks for Machine CSR access and U-mode SRET.

V1 deliberately does **not** include:

- `satp`;
- Sv32 page tables;
- page-table walking;
- TLBs;
- `sfence.vma`;
- S-level timer/external interrupt sources;
- Supervisor WFI behavior.

`mideleg` is therefore WARL-zero in this milestone. Existing MTIP/MEIP behavior remains Machine-mode behavior.

## Executable contract

```text
contract=rv32im-supervisor-v1-executable
march=rv32im_zicsr
modes=M,S,U
sv32=disabled
mideleg=WARL-zero
bytes=584
words=146
```

The real GCC-built workload executes:

```text
M -> S
S ECALL -> delegated S trap -> SRET -> S
SRET -> U
U ECALL -> delegated S trap -> SRET -> U
```

It also checks the architectural `scause`, `sepc`, `stval`, `sstatus` and `sscratch` state and fails closed through nonzero exit MMIO on an unexpected path.

## Full Gate qualification

Final consolidated qualification:

```text
workflow: AetherCore Full Gate
run: 31197747822
job: 92930051977
head: 9b80add28ef212cc9db2924926b2dc8f10f1a58f
conclusion: success
artifact: aethercore-full-gate-31197747822
artifact id: 9001953079
artifact digest: sha256:713be9f8934bb19f1f90c5ec03b352d8b6280a84ff129ced893f7976b1adffbb
```

The Full Gate passed every functional stage, including:

- source/image contracts;
- FreeRTOS workload and IRQ matrix;
- exact RV32 NEMU DiffTest and deliberate mismatch probe;
- complete Chisel tests;
- Supervisor V1 executable M/S/U qualification;
- RV64 smoke, precise pipeline regressions and NEMU matrices;
- RV32 GCC workload/DiffTest;
- CSR, synchronous trap, MRET and timer regressions;
- preemptive scheduler;
- legacy U-mode syscalls and PMP isolation;
- isolated U-mode scheduler;
- compiler-produced and pinned upstream workloads;
- CoreMark;
- Embench batches;
- littlefs.

No functional stage was skipped.

## Final Fast Gate

```text
workflow: AetherCore Fast Gate
run: 31197749504
job: 92930057425
head: 9b80add28ef212cc9db2924926b2dc8f10f1a58f
conclusion: success
```

The final Fast Gate re-passed:

- source contracts;
- focused configuration/privilege/RV32A/interrupt/WFI tests;
- the executable Supervisor V1 workload;
- FreeRTOS WFI workload;
- UART ISR and Tickless early-wake workload;
- exact FreeRTOS RV32 NEMU DiffTest.

## Frozen V1 artifacts

From the final Full Gate artifact:

```text
supervisor-v1.bin
sha256: 0c6dce9eb2f2c9fb0cdfe24e00335b47e215bfe4985d303c9d4c5195ab6e57e8

supervisor-v1.elf
sha256: 3bfcbd7fd469f846f0669ffc50d08abf826978db3a253e6ac8efacdae8ee6d81

supervisor-v1.dis
sha256: abf8273b5db1a365e78a6174d6e060cc0ec2b7dc8f52110a276736a9c7cee7f7

generated simulator runner
sha256: 9082cf1aa657f3936f65dee4a4fc57694ac68054015578c93c2aee22c4d23a0b
```

Runtime evidence:

```text
stall=0: PASS, 142 cycles, 97 committed instructions
stall=3: PASS, 143 cycles, 97 committed instructions
```

## Privilege boundary regressions

Permanent negative/edge coverage includes:

- M-origin exceptions cannot be delegated to S;
- an undelegated S-origin exception enters M;
- S-mode access to a Machine CSR raises illegal instruction;
- U-mode SRET raises illegal instruction and can itself be delegated to S;
- M-mode SRET remains legal and follows Supervisor-return semantics;
- no-S profiles retain the legacy Machine CSR exposure boundary.

## Requalification rule

Do not move this freeze or reuse the qualification claim without rerunning the exact V1 executable and complete privilege regressions if a change affects any of:

- privilege-mode state or transitions;
- Machine/Supervisor CSR decode or WARL behavior;
- trap target selection or delegation;
- exception cause/value/PC capture;
- MRET/SRET redirect or retirement semantics;
- pipeline flush / younger-side-effect suppression around traps and xRET;
- existing M/U/PMP/interrupt behavior used by the regression ladder;
- the V1 workload, simulator top, build script or CI qualification path.

## Next architecture milestone

The next architecture line may add Sv32, but it must be a separate bounded milestone. V1 itself remains the physical-address M/S/U trap/delegation checkpoint.