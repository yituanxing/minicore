# AetherCore Sv32 V2 freeze

## Frozen state

```text
freeze branch: freeze/sv32-v2-minimal-translation
qualification branch: agent/sv32-v2-minimal-translation
qualification head: 43817d899cd335610a8a4467bc3dff6d4e405d98
last functional/CI-content head before qualification sentinel: caeb34c1e0f54b9a167f23d8d340445056289762
base freeze: freeze/smode-v1-minimal-trap
```

`43817d899cd335610a8a4467bc3dff6d4e405d98` is the authoritative qualified source head. Its only change after the already-green functional head is `.github/full-gate-request`, used to request final qualification. Documentation-only commits may follow on the freeze branch, but executable RTL/tests/workflows must continue to refer back to this qualified head.

## Final qualification

Fast Gate:

```text
run: 31243947400
job: 93069344932
conclusion: success
head: 43817d899cd335610a8a4467bc3dff6d4e405d98
```

Full Gate:

```text
run: 31243947508
job: 93069345207
conclusion: success
head: 43817d899cd335610a8a4467bc3dff6d4e405d98
artifact: aethercore-full-gate-31243947508
artifact id: 9018110154
artifact digest: sha256:6a5bb8aedaf65e4442d605225f372b333aaba41b2d4f4376fefd459fe9cec972
```

The Full Gate passed source/image tests, FreeRTOS workloads and exact NEMU DiffTest, all Chisel tests including Sv32 V2, frozen Supervisor V1, RV32/RV64 differential and precise-trap regressions, U-mode/PMP scheduling, compiler-produced workloads, CoreMark, both Embench batches and littlefs before uploading the consolidated evidence artifact.

## Qualified architecture boundary

V2 extends the frozen physical-address M/S/U Supervisor V1 checkpoint with a correctness-first Sv32 virtual-memory implementation.

Qualified profile:

```text
profile: rv32imsuSv32Software
XLEN: 32
privilege modes: M/S/U
physical address width: 34 bits
memory bus data width: 32 bits
Sv32: enabled
ASIDLEN: 0
PMP + Sv32 in the same profile: deliberately unsupported in V2
RV32A + Sv32 in the same profile: deliberately deferred in V2
```

Implemented and qualified:

- `satp` Bare/Sv32 WARL behavior with full Sv32 root PPN and ASIDLEN=0;
- `sstatus.SUM` and `sstatus.MXR` only on the Sv32-capable profile;
- two-level Sv32 page-table walking;
- 4 KiB leaves and aligned 4 MiB megapages;
- full architectural 34-bit Sv32 physical addresses;
- R/W/X/U permission checks plus SUM and MXR;
- correctness-first Svade-style A/D handling: A=0 or a Store with D=0 faults rather than mutating the PTE in hardware;
- implicit PTE-read access faults distinguished from page faults;
- M-mode and Bare-mode physical-address bypass;
- S/U Load/Store virtual-address translation before `dmem`;
- S/U instruction virtual-address translation before `imem`;
- one shared read-only physical PTW port, with older Data walks taking deterministic priority over speculative Fetch walks;
- cancellable speculative instruction walks on redirects/traps/xRET/context changes;
- precise instruction/load/store page-fault causes 12/13/15;
- fault trap value preserves the original virtual address;
- page-faulting accesses do not issue the final physical memory transaction;
- small correctness-first fully-associative I/D translation caches through the common `Sv32TranslationUnit`;
- 4 KiB and 4 MiB TLB entries;
- TLB tags include root PPN, VPN, privilege, access type and SUM/MXR context to fail closed across permission contexts;
- `SFENCE.VMA` recognized for all rs1/rs2 forms and implemented conservatively as a global I/D TLB flush;
- `SFENCE.VMA` retires precisely, terminates stale speculative walks, squashes younger work and resumes at the following instruction;
- U-mode `SFENCE.VMA` is a precise illegal instruction;
- page-fault delegation bits 12/13/15 are WARL-visible only on the Sv32 profile;
- a real U-mode Load page fault can be delegated through `medeleg` to an S-mode `stvec` handler, with `scause=13` and `stval` equal to the original virtual address.

## Executable evidence

### Data translation

The core test configures `satp` in M-mode, executes MRET into S-mode, then performs real S-mode Load/Store accesses through Sv32. VA `0x40403024` is mapped to a 34-bit physical address above 4 GiB. The final `dmem` transaction and commit memory metadata use the translated PA rather than the virtual address.

Negative cases prove precise Load/Store page faults and implicit PTE-read access faults, preserving the original VA and suppressing the final data-bus request.

### Instruction translation

The instruction-fetch qualification maps the virtual `0x80000000` megapage to physical `0x100000000`. S-mode code at virtual `0x80000040` therefore executes from physical `0x100000040`, proving that the instruction path consumes the full PA34 result rather than accidentally preserving the legacy RV32 physical-address wiring.

Negative cases prove instruction page fault cause 12 and instruction access fault behavior from failed implicit PTE reads.

### TLB and SFENCE.VMA

The translation-unit regression proves:

```text
first access  -> page walk -> refill
second access -> TLB hit -> no PTW traffic
flush         -> invalidate cache / abort stale walk
next access   -> page walk again
```

The core-level stale-translation test changes a data PTE after the first Load. A second Load before `SFENCE.VMA` intentionally observes the cached old mapping. After `SFENCE.VMA` retires, the next Load must walk again and observe the new mapping.

### Page-fault delegation

The final focused chain proves:

```text
M configures satp + stvec + medeleg[13]
  -> MRET -> U-mode
  -> U-mode lw from unmapped virtual address
  -> Load page fault cause 13
  -> delegated to S-mode
  -> S handler reads scause == 13
  -> S handler reads stval == original faulting VA
  -> handler continues executing
```

No final `dmem` request is emitted for the faulting Load.

The frozen no-VM Supervisor V1 profile continues to WARL-clear page-fault delegation bits 12/13/15.

## Incremental qualification history

The final qualification supersedes these intermediate slices, which are retained for diagnosis:

| Slice | Evidence |
| --- | --- |
| V2-A page-table walker | Full `31200935819`, Fast `31200935792` |
| V2-B `satp` | Fast `31202844864` |
| V2-C Supervisor VM CSR boundary | Fast `31204346541` |
| V2-D TranslationUnit composition | Fast `31205149388` |
| V2-E real Data VA -> PA34 | Fast `31206423551` |
| V2-F cancellable Fetch/PTW helper | Fast `31207794483` |
| V2-F real instruction VA -> PA34 | Fast `31241673825` |
| bounded standalone TLB | Fast `31242018958` |
| TLB integrated into TranslationUnit | Fast `31242488320` |
| `SFENCE.VMA` core semantics | Fast `31243140467` |
| page-fault delegation | Fast `31243642551` |
| final exact-head Fast Gate | Fast `31243947400` |
| final exact-head Full Gate | Full `31243947508` |

## Explicit non-goals of V2

V2 does not claim:

- selective `SFENCE.VMA` invalidation by VA/ASID; the qualified implementation legally over-fences globally;
- nonzero ASIDs;
- hardware A/D-bit updates;
- a combined Sv32 + PMP profile;
- translated RV32A AMOs;
- S-level timer or external interrupt delivery;
- a real supervisor OS using Sv32 page tables;
- OpenSBI or Linux boot.

Those are future workload-driven boundaries, not hidden partial features of this freeze.

## Requalification rules

A fresh focused + complete qualification is required if a change affects any of the following:

- `satp`, `sstatus.SUM/MXR`, privilege transitions or delegation;
- Sv32 walker permission/fault/address-generation logic;
- PTW arbitration or implicit-memory-fault behavior;
- instruction or data translation adapters;
- TLB tags, refill, replacement or invalidation;
- `SFENCE.VMA` recognition, retirement or serialization;
- instruction/load/store page-fault generation or trap values;
- PA34 transport through imem/dmem/commit metadata;
- any frozen historical privilege/PMP/interrupt behavior touched by the shared core pipeline.

Documentation-only commits on `freeze/sv32-v2-minimal-translation` do not move the executable qualification basis above.
