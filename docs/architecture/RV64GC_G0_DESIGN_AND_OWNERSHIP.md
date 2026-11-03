# RV64GC 产品 G0：数据流、唯一状态所有权与同拍事件契约

> Status: **G0 proposed / reviewable — not a production RTL qualification**  
> Source baseline: [main@7ff35c6](https://github.com/yituanxing/minicore/commit/7ff35c6acdc3068754292bba814e4ec52ba4ec50)  
> Product input: 《RV64 FPGA Linux 最终产品目标与系统方案 v1.1》（2026-11-02）  
> Machine-readable companion: [rv64gc_g0_contract.json](rv64gc_g0_contract.json)  
> Executable spec guards: [test_product_g0_contract.py](../../../tests_py/test_product_g0_contract.py)  
> Scope: **new product core architecture**, not immediate edits to v0's qualified RTL.

## 1. What is already real, and what is not

### Production v2 / frozen AetherSoC v0 — evidence

Current \`TinyPagedCore\` (production \`main\`) instantiates \`TinyLoadQueueMemoryBackend\` and the shared \`TinySemanticDecode\`/fetch/PTW/PMP substrate. This is NOT the historical oldest-only first prototype, and NOT a nonexistent CPU. It has a working SoC with real Chisel-generated RTL and virtual FPGA Linux PID1 evidence.

\`\`\`text
PC / RVC parcel / fetch translation + PMP
    -> TinySemanticDecode (one dispatch uOp)
    -> TinyDependencyBackend
         -> TinyRob (4 entries, one retire)
         -> TinyDependencyState (ProducerTag/ROB-value-backed dependency)
    -> [exact-head Branch | selective Integer/MulDiv | ordinary LoadQ2]
    -> [System | Execution | LSU/LoadQ2 completion arbiters] (one accept)
    -> TinyRob complete / precise head recovery
    -> V2Commit + MachineCsrFile (architectural effects)
    -> AetherMem transactions -> CPU cache -> SoC fabric -> AXI / UART / PLIC / ACLINT
\`\`\`

Read source:
- \`core/v2/TinyPagedCore.scala\` and \`TinySemanticDecode.scala\`: PC/fetch, front-end blocking, serializing operations, RVC, dispatch.
- \`TinyRobCommit.scala\`: \`TinyRobGeometry.Entries = 4\`, one \`Decoupled[RobRetirement]\`, full token-completion validation and head-only branch/privileged recovery.
- \`TinyDependency.scala\`, \`TinyPhysicalIssue.scala\`: ROB/ProducerTag/ValueRef abstractions; physical-slot scheduling avoids some width-heavy age MUX but still uses the tiny ROB.
- \`TinyMemoryBackend.scala\`, \`TinyLoadQueueMemoryBackend.scala\`: 3-way top-level completion, real two-slot ordinary Load overlap; Store/SC/AMO exact-head; shared translation/PMP.
- \`memory/AetherMemLink.scala\`, \`soc/AetherCoreV2FpgaSoC.scala\`: semantic physical transaction with independent txnId; board-neutral AXI and serial-pin boundary.
- \`docs/AETHERSOC_V0_FREEZE.md\`: v0 Linux 6.6.143, OpenSBI, STIP/SEIP/serial and board-neutral AXI evidence.
- \`config/CoreConfig.scala\`: current implementation capabilities include I/M/A/C; **F/D are not implemented**.

The v2 source and \`docs/AETHERCORE_V2_ARCHITECTURE_PHILOSOPHY.md\` were designed to be *selective small-window OoO*, not a BOOM/XiangShan-style fully renamed large OoO. It is incorrect to label it either "no OoO" or "already the target OoO."

### Separate product destination — target, NOT tested implementation

The target design is **single-hart RV64GC/LP64D, Debian riscv64, M/S/U, Sv39, 2 decode / 2 retire, FPGA-compliant memory/interrupt/DDR, persistent rootfs**. ROB64 and IQ16/32 are candidate sizes, not forced source constants. The user's product design file's statement that the *new product CPU* has no implementation is compatible with the fact that the older AetherCore v2 already exists and runs Linux.

**G0 is architectural protocol closure, not permission to rewrite the v0 or to claim new CPU Linux PASS.**

## 2. One product data flow; one retired instruction history

\`\`\`text
          instruction fetch / RVC align / prediction
                            |
                       FetchGroup
                            v
                        Decode x2
                            |
          atomic Rename+ROB+IQ+LQ/SQ+PRF group reserve
                 /         |         \
                v          v          v
         speculative RAT  ROB64      IQ / LSQ
                |          |          |
            phys tags      |     ready-issue / age barrier
                |          |          |
               PRF <--- tagged FU result / load completion
                            |
                      completion fabric
                     verify all lifetimes
                            |
                     ROB ordered head
                            v
                   Commit 1..2 prefix
                     /      |       \
          physical releases CSR/FCSR   SQ commit permit
                            |
                  architectural trace / recovery

 LSQ / cache / PTW / TLB -- AetherMem txnId --> AXI / board DDR
                                   ^
                  PMA, physical device/IRQ ownership in platform
\`\`\`

The data flow never requires IQ to own ROB lifetime, CSR to own the producer table, or AXI to become an instruction-level signal. The independent Spike/difftest oracle is an **external read-only sink** of ArchitectureTrace, not a CPU state owner. A forwarding/kill/control edge may run backwards, but it **must not create a second owner** of any mutable state.

### Ownership table (the machine-readable JSON is normative for G0 review)

| State group | Sole mutable owner | Other consumers get |
| --- | --- | --- |
| PC, parcel alignment, predictor speculative state, fetch epoch | Frontend | ordered fetch group with PC/size/fault/epoch |
| RAT (speculative + committed), FreeList, rename restoration metadata | Rename | physical source/destination tags, allocation grants |
| ROB head/tail, age, completion and precise exception | ROB | live token validation, read-only scheduling metadata, ordered retire prefix |
| issue wait bits and resource requests | IQ | tagged request, never duplicated ROB order |
| integer/FP physical values | PRF | tag-indexed reads; writes only for valid live producers |
| FU work and held completion | Execution | Decoupled response, held until transfer |
| cutoff/redirect generation | Recovery controller, validated by ROB | younger-squash mask and restart PC |
| load age, store age, known addresses, data, drain state | LSQ | safe read requests; committed Store drain |
| TLB, walker, Cache, MSHR, translation epoch | MMU/cache | resolved/fault-tagged memory response |
| external request/response lifetime, TxnId | Memory transport | aligned response ownership despite ROB recycle |
| architectural CSR, privilege, trap/FFLAGS/FCSR | Commit/CSR | read-only context and retirement-ordered changes |
| PMA/MMIO map, UART, PLIC, ACLINT, board DDR clock/reset | Platform/board | physical interrupt lines and AetherMem response |

**Important:** The proposed product PRF, RAT, FreeList and SQ are new responsibilities; the existing \`TinyDependencyState.rename = Mem(32, ProducerTag)\` plus ROB value field is *not* equivalent to the product design. The new CPU must not grow by simply replacing \`TinyRobGeometry.Entries = 4\` with 64.

## 3. Lifetimes — six independent identities

1. **RobToken(index,generation)** — allocated only with an actual ROB slot. Used for ordering, precise faults and kill. Slot number alone never validates a response. On retire/squash the old lifetime ends; finite generation wrap must be safe by cancel/drain or epoch/quarantine, not by assuming 8 bits are globally unique.
2. **ProducerTag** — dependency wakeup identity; newly allocated by Rename for each writing uOp, except x0. ROB index may equal physical ID in the tiny reference but must not be inferred as equivalent in the product.
3. **ValueRef / physical destination** — PRF storage identity. Allocated at rename, available after validated writeback, old physical register freed once after replacement commits, killed *new* destinations returned on recovery. An old source still referenced by survivors cannot be freed.
4. **MemTxnId** — transport identity assigned when an external physical request is accepted. Memory may receive a response after its originating ROB uOp is dead. The table/MSHR owns draining, late fault handling and ID reuse safety independently of ROB generation.
5. **BranchEpoch** — fetch/recovery scope; a redirect invalidates speculative fetch and younger execution without cancelling surviving older work. Its representation (checkpoint, undo log, survivor replay) is **unresolved** until PPA and recovery latency are evaluated.
6. **FpEffect** — a completed FP uOp carries result/exception flags under its valid ROB lifetime. No speculative \`fflags\`, \`FS\` dirty or FCSR mutation; apply at precisely ordered retirement.

Invariant: \`RobToken != ProducerTag != ValueRef != MemTxnId\` as **semantics**, even when a tiny physical implementation temporarily shares integer fields.

## 4. Required handshakes and resource accounting

### 4.1 Fetch -> Decode -> Rename

Two incoming valid uOps are *one group* at G2+. Define a single group-accept signal after checking:
- ROB available slots; IQ/LSQ target entries; PRF/free-list destinations; possible branch restore metadata;
- lane0 then lane1 source mapping, especially internal RAW/WAW and x0;
- front-end redirect/interrupt/serialization status;
- physically available rename/PRF read and update ports.

**The group either allocates all valid lanes or none.** If only lane0 is valid, accepting one is allowed. It is **forbidden** to update RAT, free list or ROB for lane0 and silently reject a valid lane1 while the consumer treats both as accepted. An explicitly separate future prefix-partial protocol would require its own unambiguous lane acceptance mask and tests; it is not this G0 contract.

### 4.2 Issue -> FU -> completion

- IQ has no architectural write authority. It may issue only dependency-ready, non-killed work subject to the target FU's Decoupled grant.
- All FUs retain valid response bits and identity through backpressure. A response is validated by ROB lifetime **before** PRF wakeup, FFLAGS accumulation or recovery trigger. Killed responses are ignored or drained.
- One completion port is valid for current v2, but completion bandwidth of the new product core is an **implementation/PPA decision**, not an implicit reuse of the tiny arbiter.
- No combinational loop from accepted completion/recovery back through ready/issue into the same response.

### 4.3 ROB -> ordered Commit -> architectural side effects

G1 handles one oldest uOp; G2 may commit a contiguous prefix of length 0, 1 or 2. Eligible means completed, surviving, no unprocessed synchronous fault, and any serialization/write-like commit permission is available.

- \`[normal, normal]\` may retire two in order.
- \`[fault, normal]\`: no architectural retirement, fault/trap at head, younger killed.
- \`[normal, fault]\`: older may retire, faulting younger *never* commits; implementation may defer younger trap to a following cycle if required by its architectural boundary.
- \`[stalled, ready]\`: neither retires.
- Contending CSR/FCSR writes, same-cycle fence/store, xRET and privilege changes must serialize or be applied in exact program order; **"two retire" is a capacity, not permission to disregard hazards**.

Only Commit applies pending integer/FP/CSR state changes and irrevocable store permission. Store execution may calculate address/data early; speculative store data must stay internal.

### 4.4 LSQ -> MMU/Cache -> AetherMem -> AXI

- Unknown-address older Store blocks a potentially aliasing younger Load; known disjoint Load may proceed.
- Exact full-byte overlap with ready older data may forward. Partially covered bytes initially stall (no speculative reconstruction without verification); after a later design implements mixed forwarding, it requires a new proof.
- MMIO and other side-effecting/non-idempotent accesses are never executed on the wrong path. Store/SC/AMO cannot physically externalize until in-order commit permission; committed buffer drains in order.
- \`aq/rl\`, LR/SC reservations, FENCE ordering, FENCE.I instruction visibility, SFENCE.VMA TLB invalidation, memory PMA and future noncoherent DMA require *distinct* documented checks.
- PTW's PTE reads/updates must observe D-cache-dirty PTE state. It is not enough for Sv39 functional tests to pass with zero-latency DRAM.
- External txnId, MSHR and AXI reorder semantics must not leak into Decode/ROB.
- v0's existing AetherMem/AXI separation is reusable; \`busDataBits == XLEN\` in the existing core is a bounded historical assumption to remove behind memory adaptation, not by introducing AXI into ROB.

## 5. same-cycle event priority (architectural, not a combinational implementation)

Priority must be defined by **oldest non-reorderable program event and stage ownership**, rather than a global hard-coded "branch always wins" or "interrupt always wins" wire. A younger branch redirect is speculative until all older architectural effects are respected. The following are normative cases for next-stage RTL tests:

| Collision | Decision and guarantee |
| --- | --- |
| Alloc2 vs insufficient one resource | No acceptance, no RAT/PRF/ROB/IQ/LSQ resource mutation |
| Commit lane0 normal; lane1 fault | Lane0 may retire, lane1 and younger never retire; accurate lane1 PC/cause |
| Commit lane0 fault; lane1 normal | No retirement; lane0 trap takes control |
| Validated older Branch vs younger in flight | Keep older/Branch, kill only younger by order; late responses cannot hit reused tags |
| Younger Branch redirect then older delayed fault | Older fault eventually supersedes younger speculative redirect; wrong-path state never becomes architectural |
| WFI/CSR/xRET/Fence vs two-wide retirement | Hold/serialize at boundary; no context-crossing younger side effects |
| STIP/SEIP vs retire group | G1 conservatively waits for clean ROB boundary; G2 may use a precise cut but never split a retired uOp |
| FP flags vs redirect or exception | Only surviving retired FP updates FCSR/fflags/FS |
| LR/SC/AMO vs Load/Store/MMIO | Reservation/order/externalization must satisfy PMA and precise commit |
| Redirect vs already-issued PTW/Cache/AXI txn | Track independent external lifetime; drain/ignore stale response without corrupting newly allocated identity |

No **same-cycle** event is allowed to bypass a still-live older instruction's precise exception or an irreversible side-effect barrier. The current \`TinyRob\` only accepts head Branch recovery and head synchronous exceptions; generalizing to arbitrary-age Branch and two retirement lanes must be implemented and measured, not assumed.

## 6. Verification attached to G0

\`docs/architecture/rv64gc_g0_contract.json\` records:
- 13 current proposed sole state owners;
- 51 uniquely named mutable state groups;
- six non-interchangeable lifetime identities;
- eight required typed/protocol interfaces;
- 12 invariants and seven priority categories;
- ten executable example vectors (five dispatch, five retire);
- six unresolved design decisions that **must not** silently turn into hardware parameters.

\`tests_py/test_product_g0_contract.py\` checks JSON well-formedness, exactly one owner per state, valid interface endpoints, identity semantics, disallowed claims and dispatch/retire example traces. It also pins today's production \`main\` facts in source (ROB4, single retire, LoadQ2, C support without F/D, independent AetherMem txnId). This is executable *architecture-contract lint*, **not proof of future RTL**. G1 must add ChiselTest/formal directed assertions and an independent Spike/retire-trace oracle.

### First G1 proof matrix (not yet executed)

- atomic 2-uOp allocation with injected ROB/IQ/PRF/LSQ exhaustion;
- group-internal RAW/WAW and x0;
- simultaneous 2-port wakeup, PRF write collision and ROB completion backpressure;
- recover older Branch, kill younger MUL/DIV/LOAD; then send stale response after slot reuse;
- retire [normal,normal], [normal,trap], [trap,normal], [not-ready,ready];
- commit two Store/CSR/FP instructions with ordering conflict; no speculative device write;
- Sv39/ITLB/DTLB page-cross RVC and dirty-PTE-with-DCache cases;
- LP64D FCSR/FP register context switching and floating-point exception rounding tests;
- compare every retired instruction and full memory bytes against independent software reference, not only final UART digest.

## 7. Geometry presets: proposals, not a finalized FPGA chip

| Named profile | Decode / Dispatch / Issue / Retire | ROB / IQ / LoadQ / StoreQ | Integer PRF | Purpose |
| --- | --- | --- | --- | --- |
| G1-minimal-ooo | 1 / 1 / 1 / 1 | 8 / 4 / 2 / 2 | 48 | Build ownership correctness with full rename/ROB/kill |
| G2-width2 | 2 / 2 / 1 / 2 | 16 / 8 / 4 / 4 | 64 | Validate atomic paired decode and retirement; do not prematurely widen FU |
| G3-product-candidate | 2 / 2 / 2 / 2 | 64 / 16 / 16 / 16 | 64 | RV64GC/LP64D and performance / PPA candidate |

**IQ32** is a measured alternative; **FP PRF and FU counts, predictor geometry and board clocks are unresolved**, not accidentally omitted. Naming geometry does not force an arbitrary cross-product generator. Maintain one product microarchitecture implementation with named compatible presets and a small always-qualified reduced profile; keep v0's old v2 as independent reference/diagnostic line.

## 8. Deliberately unresolved G0 review decisions

| ID | What must be decided | Gate |
| --- | --- | --- |
| D01 | Concrete FPGA, DDR/IP, clock/timing and license budget | G4 board |
| D02 | RAT checkpoint versus replay; PRF read/write geometry and FP mapping | **before G1 RTL** |
| D03 | Resource widths, ROB/IQ/LSQ/issue/completion port costs, actual PPA | G2 |
| D04 | DMA noncoherence, dirty-PTE/PTW and I-cache fence visibility | G3 |
| D05 | FP unit / FCSR / FS context management | G3 |
| D06 | New product package versus carefully selected v2 reuse and test ownership | **before G1 RTL** |

G0 is **reviewable, not auto-approved**. In particular, the decision whether to reuse v2 backend implementation or only its semantics/tests must be explicit and evidenced. D02/D06 are *blocking* for G1; D01 can proceed in a separate board effort. No new CPU RTL is authorized by this document alone.

## 9. Workstreams after G0

**CPU product line:** one coherent implementation group for Rename/RAT/FreeList/PRF + ROB/Commit + RecoveryCut with contract tests. Never do a tiny resize of \`TinyRob\` and then patch the rest one error at a time. Freeze G0 ownership and prove the smallest *same-source* configuration before enabling full widths.

**SoC board line:** keep the proven v0 \`AetherCoreV2FpgaSoC\` and its AXI, cache, PMA, UART/PLIC/ACLINT/DT as a separate golden reference. Select board and clock/DDR IP, constrain paths, verify ROM/UART first, then OpenSBI/Linux and real DDR persistent storage. This can proceed **in parallel** with G1 without making v0's small selective backend the product promise.

**GSIM line:** performance research is closed; 1.42692× full PID1 is the qualified historical same-host GSIM↔Verilator result. Profiling/PGO/ThinLTO parameter experiments are not a reason to delay CPU or board development.
