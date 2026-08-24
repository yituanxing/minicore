# AetherCore v2 supported architectural profiles

Status: A8 architecture-closure contract; common-RVC implementation in qualification.

Base reference for the supported-profile closure: frozen #161 `ed3580ba59b8c8238f4123bd8f139054b53dde9c`.
Common-RVC work is stacked on the exact #164 profile-matrix head `caee74a97da8d2cbd787c9b9b6a5f77b06be9696`.

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
| RV64 machine/software | `rv64imCurrent` | 64 | IM + Zicsr + Zifencei | LP64 | M | bare | none | no | RV64 machine/software bring-up profile; C remains absent until executable RV64C qualification closes |
| RV64 application OS | `rv64imasuSv39PmpSoftware` | 64 | IMA + Zicsr | LP64 | M/S/U | Sv39 | PMP16 | no | Current Linux/OpenSBI-class RV64 application profile; C remains absent until executable RV64C qualification closes |

These rows are representative named support points, not the only historical regression configurations. Intermediate `CoreProfiles` remain useful for focused qualification but do not imply an arbitrary supported cross-product.

## 3. Current architecture gaps / explicit non-claims

### RV64C implementation exists; qualification is still pending

The common-RVC architecture slice removes the former `CoreConfig` RV32-only C restriction and uses one XLEN-aware decompression/parcel contract for RV32 and RV64. It does **not** yet add C to either named RV64 qualified profile.

The implementation contract covers:

- shared RV32C/RV64C encodings through one decompressor;
- the XLEN alias at quadrant-1/funct3=001: RV32 `C.JAL` versus RV64 `C.ADDIW`;
- RV64-only `C.LD`, `C.SD`, `C.LDSP`, `C.SDSP`, `C.SUBW`, and `C.ADDW`;
- RV64 six-bit compressed shift amounts while preserving the RV32 custom/reserved shamt[5] boundary;
- canonical 32-bit semantic instruction delivery after decompression;
- common 16-bit parcel lifetime with `instBytes = 2`, including precise PC+2 second-parcel faults;
- cross-XLEN illegal/reserved encoding checks;
- compatibility wrappers for the already-qualified RV32C source/test surface.

The remaining qualification gate before a named RV64 profile gains C is **real compiler-produced RV64C executable evidence on the production core path**, followed by the normal exact-head regression gates. Until that evidence is frozen, RV64C is an implementation capability rather than a supported-profile claim.

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
| supported architectural-profile matrix | complete and exact-head qualified on #164 |
| common RV32C/RV64C decompression/alignment | implementation complete in this slice; focused and executable qualification pending |
| SchedulerWindow contract | complete |
| completion contract/backpressure seam | complete for the current one-accepted-completion selective-OoO stage; wider bandwidth remains measurement-driven |

The immediate architecture task is therefore to qualify the common-RVC implementation with focused regressions and a real compiler-produced RV64C workload. Only after that evidence is green should a named RV64 support profile gain C or the project proceed to P8.2 Branch OoO.

## 5. Sequencing after A8

Once common RVC is qualified and the RV64 application profile can legitimately include C, return to the selective-OoO progression:

1. measure pre-head Branch readiness/exposure;
2. if material, generalize recovery to arbitrary-age branch completion;
3. younger-only ROB squash at the branch cut point;
4. bounded sequential RAT/producer rebuild from committed state plus surviving ROB entries;
5. then enable oldest-ready Branch issue;
6. only afterward revisit conservative non-blocking memory / MLP, and only widen ROB/IQ/issue when measurements justify it.

This preserves the project target: parameterized RV32/RV64 software-visible architecture, small-window selective OoO, precise in-order Commit, conservative memory semantics and FPGA-first bounded complexity.
