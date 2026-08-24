# AetherCore v2 supported architectural profiles

Status: A8 architecture-closure contract; common-RVC implementation qualified and RV64IMC machine profile published.

Base reference for the supported-profile closure: frozen #161 `ed3580ba59b8c8238f4123bd8f139054b53dde9c`.
Common-RVC implementation qualification is frozen on #165 exact head `bb8192f03f683d78ca3e005b67678fb16f9a0667`.

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
| RV64 legacy machine/software | `rv64imCurrent` | 64 | IM + Zicsr + Zifencei | LP64 | M | bare | none | no | Existing non-C machine/software support point retained for compatibility/regression |
| RV64 compressed machine/software | `rv64imcSoftware` | 64 | IMC + Zicsr | LP64 | M | bare | none | Compiler-produced `rv64imc_zicsr/lp64` binary is executed on production v2 RVC frontend; mixed 16/32-bit retirement qualified |
| RV64 application OS | `rv64imasuSv39PmpSoftware` | 64 | IMA + Zicsr | LP64 | M/S/U | Sv39 | PMP16 | no | Current Linux/OpenSBI-class RV64 application profile; C remains absent pending a dedicated privileged/VM/PMP cross-product qualification |

These rows are representative named support points, not the only historical regression configurations. Intermediate `CoreProfiles` remain useful for focused qualification but do not imply an arbitrary supported cross-product.

## 3. RV64C publication boundary

### Machine profile: qualified

The common-RVC architecture slice removed the former RV32-only C restriction and introduced one XLEN-aware decompression/parcel contract for RV32 and RV64. #165 then qualified that implementation on production `TinyPagedCore`, including:

- shared RV32C/RV64C encodings through one decompressor;
- the XLEN alias at quadrant-1/funct3=001: RV32 `C.JAL` versus RV64 `C.ADDIW`;
- RV64-only `C.LD`, `C.SD`, `C.LDSP`, `C.SDSP`, `C.SUBW`, and `C.ADDW`;
- RV64 six-bit compressed shift amounts while preserving the RV32 custom/reserved shamt[5] boundary;
- canonical 32-bit semantic instruction delivery after decompression;
- common 16-bit parcel lifetime with `instBytes = 2`, including precise PC+2 second-parcel faults;
- cross-XLEN illegal/reserved encoding checks;
- compatibility wrappers for the already-qualified RV32C source/test surface.

The permanent compiler workload adds the publication evidence for `CoreProfiles.rv64imcSoftware`:

- pinned Bootlin GCC 13.3.0;
- compiler target `rv64imc_zicsr`, ABI `lp64`;
- objdump proof of real compressed instructions in a mixed 16/32-bit stream;
- production `TinyPagedCore` execution to the expected architectural result (`a0 = 94`);
- compressed and ordinary 32-bit instructions both observed at retirement;
- execution remains in Machine mode.

`TinyPagedCore` requires an S/U + Sv39 construction shell, so the executable harness adds those frontend transport axes while preserving the exact extension/Z-extension/ABI contract of `rv64imcSoftware`. The workload itself stays M-mode with bare SATP. This is sufficient to publish the **machine profile**, but it is intentionally not evidence for an RV64C application-OS profile.

### Application profile: still conservative

`CoreProfiles.rv64imasuSv39PmpSoftware` remains C-free. No claim is made yet for the cross-product of:

- RV64C;
- A-extension;
- M/S/U transitions;
- Sv39 instruction/data translation;
- PMP16;
- Linux/OpenSBI execution.

That combination should gain C only after its own executable qualification proves compressed code across the privileged/VM/PMP path. Machine-profile qualification is not reused as a shortcut for those independent axes.

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
| supported architectural-profile matrix | complete and exact-head qualified on #164, extended with evidence-backed RV64IMC publication |
| common RV32C/RV64C decompression/alignment | complete and exact-head qualified on #165 |
| compiler-produced RV64C machine workload | complete; pinned GCC image executes on production TinyPagedCore with real compressed retirement |
| SchedulerWindow contract | complete |
| completion contract/backpressure seam | complete for the current one-accepted-completion selective-OoO stage; wider bandwidth remains measurement-driven |

The common-RVC implementation and machine-profile publication are therefore closed. A future RV64C application-OS profile is a separate qualification task rather than unfinished machine-profile work.

## 5. Sequencing after A8

With common RVC and the RV64IMC machine profile qualified, return to the selective-OoO progression rather than widening the speculative machinery by default:

1. measure pre-head Branch readiness/exposure;
2. if material, generalize recovery to arbitrary-age branch completion;
3. younger-only ROB squash at the branch cut point;
4. bounded sequential RAT/producer rebuild from committed state plus surviving ROB entries;
5. then enable oldest-ready Branch issue;
6. only afterward revisit conservative non-blocking memory / MLP, and only widen ROB/IQ/issue when measurements justify it.

A separate RV64C application-OS qualification may be scheduled when software value justifies it, but it should not block the measured P8.2 branch experiment.

This preserves the project target: parameterized RV32/RV64 software-visible architecture, small-window selective OoO, precise in-order Commit, conservative memory semantics and FPGA-first bounded complexity.
