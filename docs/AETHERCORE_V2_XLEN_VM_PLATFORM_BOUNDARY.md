# AetherCore v2 XLEN / VM / platform boundary closure

## Status

Architecture structural-closure record stacked after ISA semantic ownership and the decode -> backend-uOp boundary.

This closure is intentionally **not** a rewrite. The audit found that the current v2 already shares the important implementation owners across RV32/RV64 and already composes the generic VM/PTW primitives. The purpose of this record and its source-contract tests is to prevent later performance work from silently reintroducing forks or platform knowledge into the core.

## 1. RV32 / RV64: one parameterized implementation

The production architecture remains one parameterized AetherCore lineage. RV32 and RV64 are profiles/geometries, not separate backend implementations.

### Architectural differences that are allowed

These differences are intrinsic to the ISA/profile and may depend on XLEN or the selected page-table geometry:

- architectural register/data width (`XLEN`);
- RV64 `*W` integer semantics and sign-extension rules;
- legal shift-immediate width;
- CSR field/register width where the architecture differs;
- SATP encoding and page-table geometry;
- virtual-address canonicality rules;
- PTE width, VPN/PPN partition and page-table level count;
- architectural physical-address/PMP geometry;
- AMO W/D width legality;
- explicitly qualified profile capabilities such as the current Sstc surface.

These differences must be expressed through `IsaConfig`, `CoreConfig`, `PageTableGeometry` or small semantic helpers. They do **not** authorize duplicate ROB, dependency, scheduler, execution, recovery, commit or memory-backend implementations.

### Implementation owners that must remain shared

- `DecodedInstruction` / `BackendUop` contracts;
- ROB identity/lifetime and precise commit ownership;
- dependency tracking and scheduling window;
- Integer/MulDiv/Branch execution ownership;
- recovery/epoch/lifetime rules;
- memory transaction identity and LSU/backend contracts;
- `CommitTrace` retirement oracle;
- translation/TLB/PTW composition;
- physical memory/bus seam.

The existing cross-XLEN qualification already instantiates the same v2 foundation/ROB contracts at 32 and 64 bits. Future XLEN support must extend parameterization before considering a fork.

## 2. VM decomposition is geometry-driven

The current VM stack already has the intended ownership split:

```text
InstructionFetchAdapter ----\
                             -> TranslationUnit -> TranslationTlb
DataPathAdapter ------------/                    -> PageTableWalker

instruction PTW request ----\
                              -> PtwArbiter -> PTW PMP -> physical PTW port
data PTW request -----------/
```

### Ownership

`PageTableGeometry`
: owns architectural VM shape: XLEN, VA/PA/PTE geometry, VPN/PPN partition and level count.

`TranslationUnit`
: owns request/response lifecycle, translation bypass rules, TLB lookup/miss/refill flow and kill/flush handling. It does not own one hard-coded Sv32/Sv39 traversal.

`TranslationTlb`
: owns cached translation entries and lookup/refill/flush semantics.

`PageTableWalker`
: owns page-table traversal and PTE permission/leaf interpretation using the selected geometry.

`InstructionFetchAdapter`
: owns instruction-side translation handshake/cancellation only.

`DataPathAdapter`
: owns data-side translation-to-physical-memory handshake and separates translation permission intent from physical bus direction.

`PtwArbiter`
: owns the shared I-side/D-side PTW physical read arbitration. The qualified policy gives data translation deterministic priority because it services an older architectural memory operation; speculative fetch may wait/cancel.

`TinyPagedCore`
: composes those generic owners, applies instruction/PTW PMP at the appropriate boundary, and routes translation-fence context changes. It must not introduce a second Sv32/Sv39-specific walker/TLB/arbiter.

### SFENCE.VMA

`SFENCE.VMA` semantic identity and rs1/rs2 dependency facts terminate above the backend at `DecodedInstruction`. Retirement remains the precise owner of the translation-fence consequence. Translation units consume the resulting flush boundary; they do not re-decode the instruction.

The current implementation may conservatively flush more than a future selective VA/ASID implementation. That policy can evolve without moving instruction encoding knowledge into the VM backend.

## 3. Frontend/data translation and shared PTW

The v2 composition is frozen around the following rules:

- instruction translation uses `InstructionFetchAdapter(geometry, ...)`;
- data translation uses the shared data adapter/translation stack owned below the LSU;
- both sides ultimately use the same geometry-driven walker/TLB abstraction;
- I-side and D-side PTE reads meet only at `PtwArbiter`;
- PTW physical access passes through PMP before leaving the core boundary;
- redirects/interrupt holds/translation fences may kill speculative instruction translation without corrupting the older data translation lifetime.

No frontend performance feature (fetch queue, predictor, I-cache) may create a private page-table walker merely for convenience.

## 4. CPU / platform ownership

The CPU must not know software-visible board addresses or implement UART/PLIC/timer/exit devices.

### Core may know

Only hardware interface geometry and architectural inputs required to construct ports, such as:

- physical address width;
- bus data width;
- reset vector;
- interrupt wires;
- PMA attributes supplied for a resolved physical address;
- physical instruction/data/PTW transport contracts.

### Platform/simulation shell owns

- RAM ranges;
- UART address/register behavior;
- exit/test device address;
- ACLINT/mtime/mtimecmp address/state;
- PLIC address map/context/source wiring;
- PMA region classification;
- host RAM/MMIO termination;
- board-specific interrupt routing.

`AetherCoreV2OpenSbiRV64SimTop` currently satisfies this rule: `TinyPagedCore` speaks physical instruction/PTW/AetherMem/interrupt contracts, while the simulation shell owns RAM/MMIO/PMA topology.

A future FPGA/SoC top may replace the simulation shell with real interconnect/peripherals without modifying the CPU pipeline or semantic backend.

## 5. Non-goals

This closure does not:

- remove compatibility wrappers solely because their historical name contains `Sv32`;
- generalize unqualified VM modes merely to make a matrix larger;
- change TLB size/replacement, PTW arbitration policy or page-walk performance;
- change PMA/PMP semantics;
- move device models into production core RTL;
- change performance behavior.

## 6. Regression contract

`tests_py/test_v2_architecture_boundaries.py` freezes the structural facts that are easy to regress accidentally:

- no RV32/RV64-named fork appears inside `core/v2`;
- v2 paged composition uses generic `InstructionFetchAdapter` and `PtwArbiter`;
- fetch/data adapters both compose generic `TranslationUnit`;
- `TranslationUnit` composes geometry-driven `PageTableWalker` and `TranslationTlb`;
- PTW arbitration remains centralized with data priority;
- v2 core/backend contain no UART/PLIC/timer/exit MMIO-map knowledge;
- board addresses/PMA/MMIO termination remain in the simulation platform shell.

Existing Scala gates remain the semantic proof: cross-XLEN foundation/ROB tests, VM/TLB/PTW/PMP tests, F7 semantic/frontend tests and real software/OS qualification.

## Closure decision

The RV32/RV64, VM/PTW and CPU/platform architecture does **not** require a broad rewrite at this point. The important shared owners are already present. Structural closure therefore means freezing these seams and spending future complexity budget on measured bottlenecks rather than replacing correct generic infrastructure.
