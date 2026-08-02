# RV32IM Embench-IoT Batch 2

This checkpoint expands the real-software RV32IM corpus with four additional programs from the pinned upstream Embench-IoT revision:

```text
09c2ed8c3b7008c95d08b038de4a3f6dc103ed70
```

It is a correctness and compatibility checkpoint, not an Embench performance score.

## Selected programs

| Benchmark | New pressure introduced |
| --- | --- |
| `aha-mont64` | 64-bit Montgomery arithmetic lowered onto RV32 integer operations and compiler runtime helpers |
| `huffbench` | Huffman compression/decompression, heaps, byte streams and dense pointer-heavy memory traffic |
| `slre` | Recursive regular-expression parsing, character classes, captures and ASCII character classification |
| `wikisort` | Complex stable sorting, function pointers, large automatic arrays, overlapping moves and soft-float conversions |

The four algorithm directories, inputs, support `main`, and `verify_benchmark()` implementations come from the pinned upstream revision. They are not copied into this repository or rewritten.

## Correctness-only scaling

The build creates a temporary copy of each selected benchmark and mechanically changes exactly one definition:

```text
LOCAL_SCALE_FACTOR=<upstream value>
```

to:

```text
LOCAL_SCALE_FACTOR=1
```

It also uses:

```text
WARMUP_HEAT=0
GLOBAL_SCALE_FACTOR=1
```

This removes repetitions whose purpose is to normalize wall-clock benchmark duration. Inputs, algorithm bodies, output state and `verify_benchmark()` remain unchanged. The generated unified patch is retained in the CI artifact.

## Freestanding runtime boundary

The bare-metal GCC package does not provide hosted C-library headers or a hosted runtime. Batch 2 extends the repository-owned freestanding surface only where the selected upstream programs require it:

- `strchr`;
- ASCII `ctype` operations used by `slre`;
- declaration-only `stdio` compatibility for upstream sources whose correctness paths do not emit output;
- a `sqrt` entry point used by `wikisort`.

The `sqrt` implementation is intentionally limited to the domain actually used by this upstream workload: a small non-negative integral `double`, immediately converted back to an integer block size. It computes the integer square root and exercises GCC's RV32 soft-float conversion helpers. It is not presented as a complete IEEE-754 `libm` implementation and must not be reused as one without extension and dedicated tests.

Every runtime object is compiled with function/data sections and linked with garbage collection. The frozen Batch 1 workflow remains byte-identical, proving that unused Batch 2 support does not enter earlier binaries.

## Compiler and execution contract

```text
compiler:             riscv64-unknown-elf-gcc 13.2.0
-march:               rv32im
-mabi:                ilp32
optimization:         -O2
freestanding/static:  yes
warmup heat:          0
global scale factor:  1
local scale factor:   1
stall period:         5
```

Each program runs in a fresh `AetherCoreRV32IMSimTop` process. The simulator applies deterministic memory backpressure and compares every valid retirement against the exact RV32 NEMU reference. Selected committed RAM and passive-MMIO Store bytes are also compared, including the final exit Store.

## Frozen binaries

| Benchmark | Bytes | Words | SHA-256 |
| --- | ---: | ---: | --- |
| `aha-mont64` | 2,392 | 598 | `4e3013930725f8b6338b644ba7f08bac70bc4502a7129a22212e58162c8e617f` |
| `huffbench` | 3,512 | 878 | `a5bed4cce883ffc0ebc4f2496e3fa10d36386122ffee1f269ce72a30a4bdd8f0` |
| `slre` | 4,770 | 1,193 | `fe902482d8596165198fbce4bbe6f63f61507df607015700b1ecffe4ed61b929` |
| `wikisort` | 12,592 | 3,148 | `02f68287d47eaa7e1a00ba7ced3ceda0ef74096e5014d6d842992f5e51aa668a` |

CI asserts every size, word count and digest exactly. The `wikisort` disassembly must also retain the repository-owned `sqrt` entry point so the special runtime dependency cannot silently disappear.

## Exact execution results

| Benchmark | Cycles | Retirements | DiffTest matches | Exit |
| --- | ---: | ---: | ---: | ---: |
| `aha-mont64` | 12,667 | 10,885 | 10,885 | 0 |
| `huffbench` | 357,646 | 257,617 | 257,617 | 0 |
| `slre` | 31,361 | 23,049 | 23,049 | 0 |
| `wikisort` | 1,213,171 | 853,344 | 853,344 | 0 |
| **Total** | **1,614,845** | **1,144,895** | **1,144,895** | |

All four upstream `verify_benchmark()` routines succeed. There are no skipped instructions, skipped final Stores or tolerated architectural mismatches.

## Independent reference

```text
OpenXiangShan/NEMU revision:
8601834e4889e6bf3b6113eb5f824ba7689126f5

exact single-step reference SHA-256:
1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
```

The reference is rebuilt twice from clean state and must be byte-identical before execution begins.

## Preserved gates

This checkpoint does not alter RTL. The following remain mandatory:

```text
frozen RV64 normal-retirement gate:   944,407
RV32I GCC DiffTest:                       585
RV32IM CoreMark DiffTest:             646,301
RV32IM Embench batch 1:               184,185
RV32IM Embench batch 2:             1,144,895
```

Strict smoke, precise-fault regressions, deliberate mismatch probes, and all directed/generated RV64I and RV64M tests remain independent required checks.

## Boundary and next checkpoint

Batch 2 broadens stateless algorithm coverage but still does not exercise persistent state, recovery, privilege, interrupts, atomics or virtual memory.

The next software-driven checkpoint should move from benchmark algorithms to a deterministic stateful library. The preferred target is littlefs on a RAM-backed flash model, beginning with format, mount, create, write, seek, read, rename, traversal, truncate, unmount, remount and content verification. Failure injection and recovery can follow after the basic state machine is frozen.
