# AetherCore S1.3 checkpoint

## Status

S1.3 adds the first pinned, unmodified upstream application to the verified
software-driven RV64IM core. CPU RTL is unchanged from S1.2.

The complete software path is now:

```text
repository-owned and pinned upstream C
  -> riscv64-unknown-elf-gcc
  -> repository-owned bare-metal port + crt0.S + linker.ld
  -> ELF + flat binary + map + disassembly
  -> Verilated AetherCore
  -> one NEMU step per normal DUT retirement
```

## Core baseline

- IF/ID/EX/MEM/WB five-stage in-order pipeline.
- Complete RV64I and all thirteen RV64M register instructions.
- EX/MEM and MEM/WB forwarding, load-use interlock and branch recovery.
- Forwarded EX operands preserved across memory backpressure.
- Host-backed 64 MiB RAM, UART MMIO at `0x10000000`, exit MMIO at `0x10000008`.
- Architectural commit trace and precise suppression of younger memory side effects at fault retirement.
- Pinned OpenXiangShan/NEMU commit-level DiffTest.

The multiply/divide implementation remains combinational and correctness-first.
Performance and timing work is intentionally deferred until the software ladder
is substantially complete.

## Frozen software gates

S1.1/S1.2 retain twelve GCC-produced freestanding programs covering calls,
stack frames, memory, RV64M arithmetic, sorting, hashing, CRC, matrix work and
`-O0/-O2/-Os` code shapes.

S1.3 adds EEMBC CoreMark pinned at:

```text
eembc/coremark
1f483d5b8316753a742cbf5590caf5bd0a4e4777
```

The five upstream algorithm files are unmodified. The repository supplies only
its bare-metal port and an exit-status wrapper. Synthetic timing is used solely
to execute CoreMark's deterministic CRC validation path; this is not a
published benchmark score.

Verified CoreMark result from workflow `30704503644`:

```text
optimization: -O2
bytes:        10,740
cycles:       960,387
retirements:  737,070
DiffTest:     737,070
stall-period: 5
exit:         0
sha256:       cda5d505711d55e4355c6d62e4eaf4b27ef1168f0a3229011b56968a6a24dfb2
```

Artifact:

```text
upstream-workloads-30704503644
ZIP SHA-256: dcd1137998a0a34b6e3d0eef6c5cc01ced1b4ddc9a07b02590d9128889a5f9d5
```

## Verification scale

```text
frozen directed/generated architecture:       3,119
S1.1/S1.2 compiler-produced corpus:          204,218
S1.3 pinned CoreMark:                        737,070
---------------------------------------------------
complete normal-retirement gate:            944,407
```

Every normal retirement matched NEMU one-for-one. All thirteen compiled
programs returned exit code zero. Strict smoke, the explicit mismatch probe,
the focused memory-stall forwarding regression and all three precise
fault-boundary tests remain mandatory separate gates.

Exact CoreMark provenance is in [`docs/UPSTREAM_SOFTWARE.md`](docs/UPSTREAM_SOFTWARE.md).
The prior twelve binary hashes and results remain frozen in
[`docs/COMPILED_CORPUS.md`](docs/COMPILED_CORPUS.md).

## Compatibility rules

1. **Frozen-binary microarchitecture gate:** run the exact recorded binary hashes unchanged.
2. **Compiler compatibility gate:** rebuild the same source and record a new artifact separately.
3. **Upstream integrity gate:** pin the source revision and keep algorithm sources unmodified; isolate all platform adaptation.
4. **Failure reduction gate:** an upstream CPU failure must become a focused permanent regression before RTL is repaired.

A CPU change may not silently recompile the corpus to obtain a passing result.

## Known limitations

- Multiply/divide remains combinational and unsuitable for final FPGA timing/area.
- The linker emits a non-fatal RWX LOAD-segment warning; later cleanup should split text and data program headers.
- Privileged CSRs, trap redirection, interrupts, atomics, Sv39 and caches are not implemented.
- Precise faults still halt instead of entering an architectural trap handler.
- Full musl, Lua and SQLite require a user-mode and syscall environment that does not yet exist.

## Next software gates

1. expand the upstream harness to a selected first batch of Embench-IoT programs;
2. add littlefs over a deterministic RAM-backed block-device adapter;
3. add a freestanding musl function corpus for routines that need no syscall ABI;
4. use FreeRTOS to drive machine CSRs, timer interrupts and context switching;
5. use xv6-riscv, then Linux, to complete privilege, atomics, virtual memory and devices;
6. run full musl, BusyBox, Lua and SQLite as user-space compatibility gates;
7. only after software completeness, use the frozen corpus for execution-unit, cache, branch and bus optimization.
