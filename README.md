# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel and driven by executable software workloads and real operating systems.

The current frozen architecture checkpoint is **Supervisor Sv32 V2** on `freeze/sv32-v2-minimal-translation`. It extends the frozen physical-address M/S/U Supervisor V1 boundary with 34-bit Sv32 instruction/data translation, precise page faults, a correctness-first TLB, `SFENCE.VMA`, and page-fault delegation to S-mode.

## Current core

```text
IF -> ID -> EX -> MEM -> WB/commit
```

Verified foundations include:

- five-stage in-order RV32/RV64 integer pipeline;
- RV64I/RV64M and qualified RV32IM software profiles;
- RV32A word atomics required by real protected NuttX userspace;
- forwarding, load-use interlock and branch/jump recovery;
- precise synchronous traps and xRET redirection at the architectural boundary;
- Machine timer and external interrupt paths, PLIC and WFI;
- U-mode execution, ECALL/syscall return and PMP isolation;
- protected NuttX userspace with independent kernel exception stacks and isolated user faults;
- M/S/U execution with Supervisor CSR state, synchronous exception delegation and SRET;
- RV32 Sv32 two-level page-table walking with full 34-bit physical addresses;
- translated S/U instruction fetch and Load/Store paths;
- precise instruction/load/store page faults and delegated U -> S page-fault entry;
- small correctness-first translation caches and globally over-fencing `SFENCE.VMA`;
- commit-level differential testing against pinned NEMU references;
- real software gates including FreeRTOS, Zephyr, NuttX, CoreMark, Embench and littlefs.

## Supervisor Sv32 V2

The frozen V2 profile adds:

```text
M-mode setup
  -> satp.MODE=Sv32
  -> MRET -> S/U virtual execution
  -> I/D virtual address
  -> TLB hit or two-level page-table walk
  -> 34-bit physical address
  -> imem / dmem
```

Qualified VM state and mechanisms include:

- `satp` Bare/Sv32 with ASIDLEN=0;
- 4 KiB pages and aligned 4 MiB megapages;
- R/W/X/U permission checks plus `SUM` and `MXR`;
- fail-closed A/D handling without hardware PTE mutation;
- shared read-only PTW physical memory port;
- cancellable speculative instruction walks;
- instruction/load/store page-fault causes 12/13/15 with the original VA in the trap value;
- I/D translation caches with 4 KiB and 4 MiB entries;
- precise `SFENCE.VMA`, conservatively implemented as a global I/D TLB flush;
- page-fault `medeleg` support only on the Sv32 profile;
- real U-mode Load page fault delegation into an S-mode handler.

V2 deliberately does **not** claim selective `SFENCE.VMA`, nonzero ASIDs, hardware A/D updates, a combined Sv32+PMP profile, translated RV32A AMOs, S-level interrupt delivery, or a real supervisor OS using the MMU yet.

The exact qualification head, Fast/Full Gate evidence, artifact digest, architectural boundary and requalification rules are recorded in [`docs/SV32_V2_FREEZE.md`](docs/SV32_V2_FREEZE.md).

## Supervisor Mode V1

The previous frozen V1 checkpoint remains the physical-address M/S/U trap/delegation baseline on `freeze/smode-v1-minimal-trap`:

```text
M-mode
  -> MRET -> S-mode
  -> delegated synchronous trap -> S trap handler
  -> SRET -> S/U
```

Its exact evidence remains in [`docs/SMODE_V1_FREEZE.md`](docs/SMODE_V1_FREEZE.md). V1 deliberately has no `satp`, Sv32, TLB or page-fault delegation bits, so it remains a clean no-VM regression profile.

## Software-driven architecture ladder

AetherCore is developed by increasing software pressure rather than implementing ISA features speculatively:

```text
small directed programs
  -> compiler-produced programs
  -> CoreMark / Embench / littlefs
  -> FreeRTOS
  -> Zephyr
  -> NuttX flat
  -> NuttX protected U-mode + PMP
  -> Supervisor V1
  -> Sv32 V2
  -> real S-mode OS using page tables
  -> firmware/SBI boundary
  -> Linux-class software
```

Real workload failures are reduced into focused permanent regressions before the architecture is widened.

## Differential verification

The project uses pinned NEMU references for deterministic retirement comparison. Depending on the qualification path, the harness checks architectural PC/register state, Store effects, CSR/trap state, interrupts and workload-specific invariants. Deliberate mismatch probes are retained to prove that the checkers fail closed.

## Build

Core verification prerequisites include Java 21, C++, Python 3, Verilator, Bison, Flex and the pinned RISC-V toolchains used by each qualification path.

Representative commands:

```bash
chmod +x mill
make python-test
make test
make rtl
make run-smoke

make -f Makefile.rv32im-supervisor-v1 run-local
./mill aethercore.test.testOnly aethercore.Sv32InstructionFetchCoreSpec
./mill aethercore.test.testOnly aethercore.Sv32SfenceCoreSpec
./mill aethercore.test.testOnly aethercore.Sv32PageFaultDelegationSpec
```

The consolidated GitHub Full Gate remains the authoritative whole-project regression path.

## Development policy

- Real programs and operating systems are design inputs, not final demos.
- Frozen executable hashes and qualification heads are not silently regenerated during microarchitectural changes.
- A feature is not considered frozen until its focused gate and complete regression gate both pass on the exact qualified source head.
- Freeze branches may contain documentation-only commits after qualification; the executable qualification commit must remain explicitly recorded.
- New architecture work starts on a bounded branch from a frozen checkpoint rather than widening the freeze branch.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the current architecture boundary and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the next stages.
