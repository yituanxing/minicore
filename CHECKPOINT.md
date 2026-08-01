# AetherCore S1.1 checkpoint

## Status

S1.1 adds the first real compiler-produced software gate to the verified S1 RV64IM core. Freestanding C programs are compiled by a real RISC-V GCC toolchain into ELF images, converted to flat binaries, executed on the Verilated AetherCore RTL, and compared one retirement at a time against the pinned OpenXiangShan/NEMU reference.

No CPU RTL changed in S1.1. The purpose of this checkpoint is to prove that the S1 architectural implementation survives compiler-generated control flow, stack frames, recursion, global data, BSS initialization, pointer-heavy memory code and multiply/divide workloads.

## Core baseline

- IF/ID/EX/MEM/WB five-stage in-order pipeline.
- Complete RV64I plus all thirteen RV64M register instructions.
- Integer register file with x0 protection and same-cycle WB bypass.
- EX/MEM and MEM/WB forwarding.
- One-cycle load-use interlock.
- EX-stage branch and jump redirection.
- Blocking data bus and host-backed 64 MiB RAM adapter.
- Forwarded EX operands preserved across memory stalls.
- UART MMIO at `0x10000000` and exit MMIO at `0x10000008`.
- Architectural commit trace and temporary halt-on-exception behavior.
- Precise suppression of younger memory effects while WB retires an exception.
- Chisel tests, Verilator smoke, directed/generated regressions and commit-level NEMU DiffTest.

The S1 multiply/divide implementation remains combinational and correctness-first. It is not the final FPGA timing/area implementation.

## Compiler toolchain contract

GitHub Actions run `30700575123` used:

```text
riscv64-unknown-elf-gcc 13.2.0
binutils-riscv64-unknown-elf 2.42
```

The C build contract is:

```text
-march=rv64im
-mabi=lp64
-mcmodel=medany
-mno-relax
-msmall-data-limit=0
-O2
-ffreestanding
-fno-builtin
-fno-stack-protector
-fno-pic
-nostdlib
-nostartfiles
-static
```

Every workload is linked with the repository-owned `crt0.S` and `linker.ld`:

- reset PC and image base: `0x80000000`;
- RAM size: 64 MiB;
- stack top: `0x80100000`;
- startup initializes `sp`;
- startup clears `.bss`;
- startup calls C `main`;
- the `main` return value is written to exit MMIO `0x10000008`.

The builder retains for each workload:

- linked ELF;
- flat binary;
- linker map;
- source-interleaved disassembly;
- ELF headers and section/program headers;
- binary SHA-256;
- execution log and DiffTest result.

## First real C workload matrix

### `call_stack`

Covers:

- recursive Fibonacci calls;
- Euclidean GCD using remainder;
- nested no-inline calls;
- local arrays and stack frames;
- structure-by-value recursion;
- `.data` and `.bss` access;
- function return and `ra`/`sp` traffic.

Result:

```text
binary: 808 bytes, 202 words
cycles: 3204
commits: 2541
difftest: 2541
stall-period: 0
sha256: e3e03b0bce3caa3481b5ae8c3928924e9b00847e6eb86259cdcf02187c39e9e3
```

### `memory`

Covers:

- `.rodata`, `.data` and `.bss`;
- byte fill/copy loops;
- byte and word loads/stores;
- pointer aliasing into a structure;
- FNV-1a checksum multiplication;
- global arrays;
- deterministic memory backpressure.

Result:

```text
binary: 744 bytes, 186 words
cycles: 1159
commits: 791
difftest: 791
stall-period: 4
sha256: ea65b30823f78c069f236d6d3864163264e668bcef5f370a9c2b6a920d596a1b
```

### `arithmetic`

Covers:

- signed and unsigned 64-bit multiplication/division/remainder;
- quotient/remainder identities;
- signed remainder rules;
- compiler-generated 32-bit W-class multiply/divide/remainder;
- continuous ALU/M-result dependency chains;
- deterministic memory backpressure.

Result:

```text
binary: 888 bytes, 222 words
cycles: 379
commits: 289
difftest: 289
stall-period: 3
sha256: 47fb06fe003700761d30cea0e6eaa3d5461791373e1bd0c19bfe083e45932423
```

The retained disassemblies confirm normal compiler code generation, including stack loads/stores, `auipc`/`jalr` calls, branches, `mul`, `div`, `rem`, `mulw`, `divw`, `divuw`, `remw` and `remuw`.

## Compiler-produced verification result

```text
call_stack:  2541 comparisons
memory:       791 comparisons
arithmetic:   289 comparisons
--------------------------------
S1.1 GCC:    3621 comparisons
```

All programs returned self-check exit code zero. All 3621 normal retirements matched NEMU one-for-one.

## Complete verified total

The original S1 gate remained unchanged and green in run `30700575126`:

```text
S0.5 RV64I directed/generated: 1540 comparisons
S1 directed RV64M:              108 comparisons
S1 generated RV64M:            1471 comparisons
------------------------------------------------
S1 existing total:             3119 comparisons
```

Adding the S1.1 compiler-produced programs:

```text
S1 existing: 3119
S1.1 GCC:    3621
----------------
combined:    6740 normal retirement comparisons
```

The mismatch probe, strict smoke and all three precise fault-boundary programs also remained green.

## NEMU reference

The reference remains pinned:

- repository: `OpenXiangShan/NEMU`;
- revision: `ad6bfde6241f2fc1e864b1efb2bed99b3670eb73`;
- configuration: `riscv64-nutshell-ref_defconfig`;
- shared object: `build/riscv64-nemu-interpreter-so`;
- RAM base: `0x80000000`;
- reference RAM size: 64 MiB.

For every normal DUT retirement, the adapter checks the pre-instruction PC and all 32 GPRs, executes exactly one reference instruction, compares all post-instruction GPRs and verifies enabled Store bytes in reference memory. DUT state is never copied back into NEMU after initialization.

## Previous S1 bug retained as regression

S1 generated testing found that an EX instruction frozen behind a stalled MEM operation could lose an operand that had only been available through WB forwarding. The stall path now saves the already-forwarded EX operands:

```scala
when(memoryStall) {
  memWb.valid := false.B
  idEx.rs1Data := forwardedRs1
  idEx.rs2Data := forwardedRs2
}
```

The focused eight-instruction regression remains green in S1.1.

## Current verified boundary

- GCC freestanding RV64IM compilation and linking: PASS.
- startup stack initialization and BSS clearing: PASS.
- ELF-to-flat-image path: PASS.
- retained maps/disassemblies/hashes: PASS.
- three compiler-produced C programs: PASS.
- 3621 compiler-produced retirements compared with NEMU: PASS.
- original 3119-retirement S1 gate: PASS.
- combined 6740 normal retirement comparisons: PASS.
- explicit DiffTest mismatch probe: PASS.
- three precise fault-boundary programs: PASS.
- deterministic memory backpressure: PASS.

## Known engineering limitations

- Multiply/divide is still combinational and unsuitable as the final FPGA implementation.
- The linker currently reports a non-fatal RWX LOAD-segment warning; the simulated no-MMU machine does not enforce ELF permissions, but later cleanup should split text and data program headers.
- The first C matrix uses GCC `-O2` only.
- Minic has not yet compiled these same sources for AetherCore.
- Privileged CSRs, trap redirection, interrupts, Sv39 and caches are not implemented.
- Precise faults still halt rather than enter an architectural trap handler.

## Next gate: real-program expansion before microarchitecture change

Continue using real software to drive the design:

1. add insertion sort, CRC32/hash and a larger mixed integer workload;
2. compile the same sources at `-O0`, `-O2` and `-Os`;
3. add a Minic path for the same freestanding C sources and compare program results;
4. freeze the resulting ELF/binary corpus as a microarchitectural compatibility suite;
5. define a multi-cycle M-unit `req/busy/resp` contract;
6. replace combinational division test-first while running the unchanged compiler-produced binaries.

Only after the real-program corpus and multi-cycle execution boundary are stable should development enter privileged architecture.
