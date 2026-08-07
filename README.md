# MiniCore / AetherCore

A correctness-first RISC-V processor project written in Chisel and driven by executable software workloads and real operating systems.

The current frozen architecture checkpoint is **Supervisor Mode V1** on `freeze/smode-v1-minimal-trap`. It extends the existing RV32/RV64 pipeline, FreeRTOS, Zephyr, NuttX protected-userspace, RV32A, U-mode and PMP work with a bounded physical-address **M/S/U Supervisor trap/delegation loop**. Sv32 is intentionally not part of this checkpoint.

## Current core

```text
IF -> ID -> EX -> MEM -> WB/commit
```

Verified foundations include:

- five-stage in-order RV32/RV64 integer pipeline;
- RV64I/RV64M and the qualified RV32IM software profiles;
- RV32A word atomics required by real protected NuttX userspace;
- forwarding, load-use interlock and branch/jump recovery;
- precise synchronous traps and xRET redirection at the architectural boundary;
- Machine timer and external interrupt paths, PLIC and WFI;
- U-mode execution, ECALL/syscall return and PMP isolation;
- protected NuttX userspace with independent kernel exception stacks and isolated user faults;
- Supervisor-mode CSR state, synchronous exception delegation and SRET;
- commit-level differential testing against pinned NEMU references;
- real software gates including FreeRTOS, Zephyr, NuttX, CoreMark, Embench and littlefs.

## Supervisor Mode V1

The frozen V1 profile adds:

```text
M-mode
  -> MRET -> S-mode
  -> delegated synchronous trap -> S trap handler
  -> SRET -> S/U
```

Qualified Supervisor state includes `sstatus`, `stvec`, `sscratch`, `sepc`, `scause`, `stval`, `sie`, `sip`, plus `medeleg`/`mideleg` at the Machine level.

The real executable qualification runs:

```text
M -> S -> ECALL(S) -> S trap -> SRET -> S
  -> SRET -> U -> ECALL(U) -> S trap -> SRET -> U
```

V1 deliberately keeps `satp`, Sv32 page tables, TLBs, `sfence.vma` and S-level timer/external interrupt sources out of scope. `mideleg` is WARL-zero until genuine S-level interrupt sources are implemented.

The exact qualification commit, CI runs, artifact digest, binary hashes and requalification rules are recorded in [`docs/SMODE_V1_FREEZE.md`](docs/SMODE_V1_FREEZE.md).

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
  -> Sv32 / real S-mode OS
  -> OpenSBI / Linux
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
```

The consolidated GitHub Full Gate remains the authoritative whole-project regression path.

## Development policy

- Real programs and operating systems are design inputs, not final demos.
- Frozen executable hashes are not silently regenerated during microarchitectural changes.
- A feature is not considered frozen until its focused gate and the complete regression gate both pass on the exact qualified source head.
- Freeze branches may contain documentation-only commits after qualification; the executable qualification commit must remain explicitly recorded.
- New architecture work starts on a bounded branch from a frozen checkpoint rather than widening the freeze branch.

See [`CHECKPOINT.md`](CHECKPOINT.md) for the current architecture boundary and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the next stages.