# AetherCore Supervisor Sv32 V2 checkpoint

## Frozen state

```text
freeze branch: freeze/sv32-v2-minimal-translation
qualified commit: 43817d899cd335610a8a4467bc3dff6d4e405d98
functional/CI-content head before qualification sentinel: caeb34c1e0f54b9a167f23d8d340445056289762
base freeze: freeze/smode-v1-minimal-trap
```

The executable qualification basis is the exact qualified commit above. Documentation-only commits on the freeze branch do not change that basis.

## Architecture now qualified

The frozen architecture ladder now covers:

- RV32/RV64 five-stage in-order execution;
- RV64I/RV64M and qualified RV32IM software profiles;
- Zicsr Machine CSR behavior;
- precise synchronous traps and MRET;
- Machine timer/external interrupts, PLIC and WFI;
- preemptive context switching;
- U-mode execution and ECALL/syscall return;
- PMP isolation and isolated U-mode scheduling;
- RV32A word atomics driven by protected NuttX userspace;
- NuttX protected U-mode with trusted per-task kernel exception stacks;
- M/S/U privilege execution;
- Supervisor CSR state, synchronous exception delegation and SRET;
- RV32 Sv32 virtual memory with two-level page-table walking;
- translated instruction and Load/Store paths with 34-bit physical addresses;
- precise instruction/load/store page faults;
- correctness-first I/D translation caches;
- `SFENCE.VMA` invalidation and serialization;
- delegated U-mode page faults into S-mode.

## Sv32 V2 boundary

Qualified profile:

```text
rv32imsuSv32Software
XLEN=32
PA=34
bus=32
modes=M,S,U
Sv32=enabled
ASIDLEN=0
```

Implemented and qualified:

- `satp` Bare/Sv32 WARL state;
- `sstatus.SUM` and `sstatus.MXR`;
- two-level Sv32 walk;
- 4 KiB leaves and aligned 4 MiB megapages;
- R/W/X/U, SUM and MXR permission checks;
- fail-closed A/D handling without PTE mutation;
- M-mode/Bare physical bypass;
- S/U instruction and data translation;
- shared read-only PTW port with Data priority over speculative Fetch;
- cancellable speculative instruction page walks;
- full PA34 delivery to imem/dmem and commit memory metadata;
- page-fault causes 12/13/15 and original-VA trap values;
- implicit PTE-read access faults;
- bounded fully-associative translation caches supporting 4 KiB/4 MiB entries;
- `SFENCE.VMA` retirement, global I/D TLB flush and stale-walk cancellation;
- U-mode `SFENCE.VMA` precise illegal-instruction behavior;
- `medeleg` page-fault bits only on the Sv32 profile;
- real U Load page fault -> delegated S trap -> `scause/stval` validation.

Deliberately excluded from V2:

- nonzero ASIDs;
- selective VA/ASID `SFENCE.VMA` invalidation;
- hardware A/D-bit updates;
- Sv32 + PMP in the same profile;
- translated RV32A AMOs;
- S-level timer/external interrupt sources;
- a real S-mode OS using the page tables;
- SBI/OpenSBI and Linux boot.

The previous `freeze/smode-v1-minimal-trap` remains the no-VM physical-address M/S/U regression baseline, including its WARL-cleared page-fault delegation bits.

## Final qualification

Final Fast Gate:

```text
run: 31243947400
job: 93069344932
conclusion: success
head: 43817d899cd335610a8a4467bc3dff6d4e405d98
```

Final Full Gate:

```text
run: 31243947508
job: 93069345207
conclusion: success
head: 43817d899cd335610a8a4467bc3dff6d4e405d98
artifact: aethercore-full-gate-31243947508
artifact id: 9018110154
artifact digest: sha256:6a5bb8aedaf65e4442d605225f372b333aaba41b2d4f4376fefd459fe9cec972
```

The Full Gate passed:

- source/image contracts;
- FreeRTOS preemptive and Machine-external-IRQ workloads;
- exact FreeRTOS RV32 NEMU DiffTest;
- all Chisel unit tests including the complete Sv32 V2 suite;
- frozen Supervisor V1 executable qualification;
- RV64 RTL and NEMU differential matrices;
- RV32 GCC/NEMU, Zicsr, precise traps/MRET/timer interrupts;
- two-task preemptive scheduler;
- U-mode syscalls, PMP isolation and isolated scheduling;
- compiler-produced/pinned RV64 workloads;
- CoreMark;
- both Embench batches;
- littlefs;
- consolidated evidence upload.

See [`docs/SV32_V2_FREEZE.md`](docs/SV32_V2_FREEZE.md) for the authoritative detailed evidence record.

## Next checkpoint

V2 is frozen. The next architecture milestone should **not** be more synthetic Sv32 feature accumulation.

The next bounded line is to select and run a real S-mode workload that genuinely consumes the V2 mechanisms. The preferred progression is:

1. audit small RV32/Sv32-capable supervisor kernels and choose the smallest useful real target;
2. boot it in S-mode using real page tables and `satp`;
3. let its failures drive any missing supervisor interrupt/timer/SBI boundary;
4. reach a real U-mode process under an S-mode kernel;
5. deliberately fault that process through an unmapped/read-only/kernel virtual address and prove process isolation plus scheduler/kernel recovery;
6. only after that decide whether the next capability jump should be an SBI/OpenSBI layer, broader supervisor interrupts, or the RV64/Linux-class line.

The frozen V2 MMU is now a platform for real OS pressure, not a reason to add unrelated VM features speculatively.
