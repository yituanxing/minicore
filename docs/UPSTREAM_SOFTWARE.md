# Upstream software-driven verification

AetherCore uses real software as a design input. Directed tests remain the final
minimal regressions, but new architectural requirements and pipeline defects
should first be exposed by reproducible upstream programs.

## S1.3 gate: pinned CoreMark correctness corpus

The first upstream workload is EEMBC CoreMark pinned at:

```text
eembc/coremark
1f483d5b8316753a742cbf5590caf5bd0a4e4777
```

The repository does not modify CoreMark's five algorithm sources. AetherCore
provides only a bare-metal `core_portme.*` implementation and a wrapper that
turns CoreMark validation errors into the existing exit-MMIO status.

This run is a **correctness and compatibility corpus**, not a published
CoreMark score:

- fixed performance seeds;
- `TOTAL_DATA_SIZE=2000`;
- two iterations suitable for RTL simulation;
- deterministic synthetic timing so the standard CRC validation path runs;
- GCC `-march=rv64im -mabi=lp64 -O2`;
- memory backpressure period 5;
- Verilator execution with one-for-one NEMU retirement comparison;
- frozen ELF, binary, map, disassembly, revision and SHA-256 evidence.

Verified GitHub Actions run `30704503644`:

```text
name:       coremark_O2
bytes:      10,740
words:      2,685
cycles:     960,387
retirements:737,070
DiffTest:   737,070
exit:       0
stall:      5
sha256:     cda5d505711d55e4355c6d62e4eaf4b27ef1168f0a3229011b56968a6a24dfb2
```

Artifact:

```text
upstream-workloads-30704503644
ZIP SHA-256: dcd1137998a0a34b6e3d0eef6c5cc01ced1b4ddc9a07b02590d9128889a5f9d5
```

The first red run failed before target execution because the port duplicated
CoreMark's upstream `get_seed_32` implementation. The fix removed only the
platform duplicate; upstream algorithm sources and CPU RTL remained unchanged.

## Software ladder

The intended order is:

1. **CoreMark** — linked lists, matrix operations, state-machine parsing and CRC.
2. **Embench-IoT** — a broad set of small integer embedded applications.
3. **littlefs** — callback-heavy storage logic over a deterministic RAM block device.
4. **Freestanding musl corpus** — selected string, memory, conversion and sorting
   routines that do not require a syscall ABI.
5. **FreeRTOS** — machine CSRs, timer interrupts, context switching and queues.
6. **xv6-riscv** — M/S/U privilege, Sv39, atomics, processes and devices.
7. **Linux + musl + BusyBox** — the primary system-level completion driver.
8. **Lua and SQLite** — user-space compatibility and long-running validation,
   initially with deterministic in-memory workloads and later real files.

The musl/Lua/SQLite path deliberately reuses the software that previously drove
the Minic compiler. On the CPU project it is primarily a compatibility and
system-integration gate: CPU defects are usually exposed through instruction,
privilege, memory-ordering, exception or device interactions rather than source
language coverage.

## Failure handling

For every new upstream failure:

```text
first failing retirement
  -> surrounding disassembly and commit history
  -> minimized instruction/program reproducer
  -> focused permanent regression
  -> minimal RTL repair
  -> failing upstream binary rerun
  -> complete frozen corpus rerun
```

Upstream source versions and original binary hashes are immutable within a
checkpoint. Recompilation is a separate compiler-compatibility operation and
must not replace the frozen-binary microarchitecture gate.
