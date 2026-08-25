# AetherCore v2 CPU / platform / bus / peripheral ownership closure

Status: final architecture-structural closure stacked on the PTW/SFENCE candidate exact head `aecb4705f7621f919b1ee847e49f8c73941b5afc`.

This closure is intentionally **contract-only**. It changes no production RTL, address map, PMA classification, MMIO behavior, interrupt topology, memory timing, scheduling, retirement or performance policy.

The purpose is to freeze the already-existing physical-memory seam before P8.3 completion overlap and P8.4 conservative non-blocking memory increase concurrency across it.

## 1. CPU ownership ends at semantic physical memory

The v2 CPU owns architectural and microarchitectural CPU semantics up to a physical-memory transaction:

- ISA load/store/atomic meaning;
- virtual-to-physical translation and translation faults;
- PMP authorization;
- memory ordering intent carried by the backend;
- precise Commit permission for externally visible writers;
- `AetherMemRequest` / `AetherMemResponse` transaction lifetime and identity;
- the resolved physical address presented for PMA classification.

The CPU does **not** own a UART, PLIC, timer, exit-device or board RAM decoder.

The stable boundary is:

```text
semantic CPU operation
        |
translation + PMP + ordering
        |
AetherMemRequest / AetherMemResponse
        |
        +---- resolved physical address ----> platform PMA classification
                                             |
                                             v
                              RAM / MMIO / interconnect / peripherals
```

## 2. `AetherMem` is not an external bus protocol

`AetherMemRequest` is the core-internal physical-memory operation contract. It carries:

- independent `txnId` transport lifetime;
- semantic operation (`Read`, `Write`, `Atomic`);
- physical address;
- semantic size;
- write data/mask;
- atomic operation;
- PMA-like `MemoryAttributes`.

`AetherMemResponse` returns the same transport identity plus data/fault/final-beat state.

AXI, TileLink or a future FPGA fabric adapter belongs **below/outside** this contract. A future external bus may widen, split, buffer or reorder transport behind a memory-system adapter without teaching decode, ROB, Commit or the LSU about AXI channel semantics.

This is important for P8.4: MSHR/cache/interconnect work may evolve behind the AetherMem seam instead of becoming a second CPU memory API.

## 3. PMA is platform-owned

`MemoryAttributes` is the PMA-like classification crossing the core/memory boundary:

- `cacheable`;
- `idempotent`;
- `sideEffecting`;
- `ordered`;
- `executable`;
- `supportsAtomic`;
- `supportsPartial`.

The CPU exposes `resolvedPhysicalAddress`; the platform classifies that address and drives `resolvedAttributes`.

Therefore RAM-vs-device classification and future cacheability/ordering regions must not become ad-hoc address comparisons inside `core/v2`.

The current OpenSBI v2 simulation shell already follows this rule: RAM receives ordinary/idempotent/cacheable/executable/atomic-capable attributes, while device/unknown addresses are side-effecting/ordered and atomics fail closed before MMIO externalization.

## 4. Board memory map belongs to platform composition

Software-visible board topology is platform state, not CPU state. This includes:

- RAM ranges;
- UART region/register model;
- timer/ACLINT-style registers;
- PLIC region, source count and source IDs;
- simulation exit device;
- any future ROM, CLINT, PCIe, framebuffer or accelerator window.

`PlatformConfig` currently carries several qualified board addresses together with reset/physical integration geometry. The OpenSBI simulation shell additionally owns board-local RAM/PLIC region constants. That representation is intentionally **not** refactored merely for cosmetic uniformity in this closure: all of those values already live outside `core/v2`, at the platform composition boundary.

A future reusable SoC/FPGA integration may extract a dedicated board-map object if multiple physical platforms need different maps. That is an implementation evolution behind this ownership boundary, not a prerequisite for P8.3/P8.4.

## 5. Peripheral state and interrupt topology belong to platform/peripherals

Peripheral modules/platform composition own:

- UART register behavior and RX/TX queues;
- timer register storage and compare logic;
- PLIC pending/enable/priority/claim-complete state;
- interrupt source IDs and source wiring;
- exit-device behavior;
- MMIO response data and device-side faults.

The CPU owns only architectural interrupt semantics after platform interrupt lines reach its architectural interrupt inputs:

- pending interrupt sampling;
- CSR/delegation/privilege legality;
- precise trap boundary;
- trap entry/return state;
- Commit/recovery interaction.

The CPU must not know which UART source ID is wired into a PLIC.

## 6. Reset/width integration fields are allowed at the core boundary

The current v2 composition may consume these platform integration properties:

- reset vector;
- physical-address width;
- current internal memory beat/data width.

These are construction/integration geometry, not device decode policy.

The existing `busDataBits == XLEN` restriction remains a bounded F6/F7 integration limitation, not an ISA identity. P8.4 may later place width adaptation behind the memory seam without creating separate RV32/RV64 CPU pipelines.

## 7. Mechanical invariants

`tests_py/test_v2_platform_ownership.py` freezes the following structural rules:

1. the AetherMem contract remains independent from external AXI/TileLink channel types;
2. PMA-like attributes remain explicit on the core/memory seam;
3. `TinyPagedCore` exposes resolved physical address + attributes and semantic AetherMem request/response rather than MMIO-specific ports;
4. production files under `core/v2` may not consume `uartAddress`, `exitAddress`, `mtimeAddress` or `mtimecmpAddress` and may not contain the qualified board's UART/PLIC/timer address literals;
5. the v2 OpenSBI platform shell owns PMA classification and RAM/MMIO/PLIC/timer/UART/exit routing;
6. interrupt source topology remains in the platform shell, outside the CPU backend.

The guard protects ownership, not a particular board map value.

## 8. Explicit non-goals

This closure does **not**:

- introduce AXI or TileLink;
- add a cache;
- add MSHRs;
- make memory non-blocking;
- move or rename qualified MMIO addresses;
- change PMA values;
- create a generalized SoC generator;
- split `PlatformConfig` only for naming aesthetics;
- change interrupt behavior;
- change Linux/OpenSBI software.

## 9. Structural closure stop line

After this contract is qualified on top of the frozen PTW/SFENCE head, the architecture-structural audit is complete:

```text
ISA semantics
  -> decoded/uOp boundary
  -> shared RV32/RV64 ownership
  -> VM traversal / PTE policy
  -> I/D PTW + PMP + SFENCE ownership
  -> CPU / PMA / platform / peripheral ownership
  -> STOP structural polishing
```

The next work returns to measured performance maturation:

1. P8.3 completion-overlap **re-audit** against the already-qualified A8 Decoupled/fair completion fabric;
2. only add completion bandwidth/buffering if collision/backpressure evidence justifies it;
3. P8.4 conservative non-blocking memory, beginning with an explicit memory-ordering/status seam before store queue, safe load overlap and small MSHR work.

Any further structural refactor must be justified by a concrete implementation blocker or measured performance/correctness need rather than by aesthetic cleanup alone.
