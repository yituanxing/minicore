# AetherCore v2 supported architectural profiles

Status: A8 architecture-closure contract.

Base reference for this closure branch: frozen #161 `ed3580ba59b8c8238f4123bd8f139054b53dde9c`.

This document closes the post-F7 requirement to formalize a supported architectural-profile matrix. It deliberately separates:

1. **ISA-description capability** — combinations that `IsaConfig` can describe;
2. **core implementation capability** — combinations that `CoreConfig` accepts and for which production RTL exists;
3. **qualified profiles** — named combinations exercised by permanent executable software/regression evidence.

A parameter being expressible is not a product-support claim. A cross-product of all `IsaConfig` axes is not implied.

## 1. Architectural axes

The software-visible axes are owned by `IsaConfig`/`CoreConfig`:

- XLEN: RV32 or RV64;
- base/standard extensions currently integrated by AetherCore: I, M, A, C;
- multi-letter extensions currently integrated: Zicsr, Zifencei;
- privilege modes: M, S, U;
- virtual memory: Sv32 on RV32, Sv39 on RV64;
- PMP: production implementation bounded to PMP16;
- supervisor timer capability: current bounded Sstc implementation is RV32-only;
- ABI derived from XLEN for the current integer profiles: ILP32 on RV32, LP64 on RV64.

Microarchitecture geometry is intentionally not part of this matrix. ROB depth, scheduler shape, issue width, cache geometry, predictor and LSU speculation policy belong to implementation profiles, not ISA profiles.

## 2. Qualified representative profiles

| Profile role | `CoreProfiles` owner | XLEN | ISA / Z extensions | ABI | Privilege | VM | PMP | C | Notes |
| --- | --- | ---: | --- | --- | --- | --- | --- | --- | --- |
| RV32 machine/RTOS | `rv32imcSoftware` | 32 | IMC + Zicsr | ILP32 | M | bare | none | yes | Real RV32IMC software/FreeRTOS line; compressed execution is qualified on RV32 |
| RV32 application OS | `rv32imacsuSv32PmpSoftware` | 32 | IMAC + Zicsr + Zifencei | ILP32 | M/S/U | Sv32 | PMP16 | yes | Highest integrated RV32 application profile; includes current RV32 Sstc capability |
| RV64 machine/software | `rv64imCurrent` | 64 | IM + Zicsr + Zifencei | LP64 | M | bare | none | no | RV64 machine/software bring-up profile |
| RV64 application OS | `rv64imasuSv39PmpSoftware` | 64 | IMA + Zicsr | LP64 | M/S/U | Sv39 | PMP16 | no | Current Linux/OpenSBI-class RV64 application profile |

These rows are representative named support points, not the only historical regression configurations. Intermediate `CoreProfiles` remain useful for focused qualification but do not imply an arbitrary supported cross-product.

## 3. Current architecture gaps / explicit non-claims

### RV64C is not closed yet

`CoreConfig` currently rejects `C` when `XLEN == 64`. This is deliberate fail-closed behavior, not a statement that the RISC-V C extension is RV32-only.

A8 must replace the RV32-only decompression/frontend restriction with one common XLEN-aware RVC contract before AetherCore claims mature RV32/RV64 architectural symmetry.

The common-RVC closure must cover at least:

- shared RV32C/RV64C encodings;
- RV32-only `C.JAL` legality only at XLEN32;
- RV64-only `C.ADDIW`, compressed LD/SD forms and other XLEN64 forms only at XLEN64;
- canonical 32-bit semantic instruction delivery after decompression;
- `instBytes = 2` lifetime/PC semantics;
- cross-XLEN illegal/reserved encoding tests;
- real compiler-produced RV64C executable evidence before the RV64 application profile gains C.

### Floating point is not claimed

`IsaConfig.mabi` currently selects integer ABIs (`ilp32` / `lp64`). A toolchain package name containing `lp64d` is not evidence that AetherCore implements F/D or the LP64D calling convention.

F/D are outside the current capability set and must remain fail-closed until an explicit architecture phase adds architectural FP state, instructions, CSRs/context semantics and software qualification.

### Other explicit non-claims

The current production capability surface does not claim:

- B / bitmanip as a monolithic extension or its Zb subsets;
- H / hypervisor mode;
- Sv48;
- arbitrary PMP counts beyond the qualified PMP16 implementation;
- arbitrary combinations of Sstc, machine-provided supervisor timer delivery and privilege profiles.

## 4. A8 closure status

The post-F7 audit defined A8 before further aggressive optimization.

| A8 item | Current status |
| --- | --- |
| post-F7 audit | complete |
| lifetime generation/epoch safety | complete; widened RobToken generation and late-response identity handling are in the v2 line |
| supported architectural-profile matrix | **this closure branch** |
| common RV32C/RV64C decompression/alignment | **next architecture task** |
| SchedulerWindow contract | complete |
| completion contract/backpressure seam | complete for the current one-accepted-completion selective-OoO stage; wider bandwidth remains measurement-driven |

Therefore the next architecture milestone after this matrix is **common RVC / RV64C closure**, not another local performance experiment and not yet P8.2 Branch OoO.

## 5. Sequencing after A8

Once common RVC is qualified and the RV64 application profile can legitimately include C, return to the selective-OoO progression:

1. measure pre-head Branch readiness/exposure;
2. if material, generalize recovery to arbitrary-age branch completion;
3. younger-only ROB squash at the branch cut point;
4. bounded sequential RAT/producer rebuild from committed state plus surviving ROB entries;
5. then enable oldest-ready Branch issue;
6. only afterward revisit conservative non-blocking memory / MLP, and only widen ROB/IQ/issue when measurements justify it.

This preserves the project target: parameterized RV32/RV64 software-visible architecture, small-window selective OoO, precise in-order Commit, conservative memory semantics and FPGA-first bounded complexity.
