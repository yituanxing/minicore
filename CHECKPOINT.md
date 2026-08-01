# AetherCore S1 checkpoint

## Status

S1 adds the complete RV64M integer multiply/divide extension to the frozen S0.5 RV64I baseline. The implementation is verified at decoder, ALU, whole-core directed-program and deterministic generated-program levels, with every normal retirement compared one-for-one against a pinned OpenXiangShan/NEMU reference.

S1 also exposed and fixed a pre-existing pipeline correctness bug: a frozen EX instruction could lose a WB-forwarded operand while an older memory operation was held by bus backpressure.

## Core baseline

- IF/ID/EX/MEM/WB five-stage in-order pipeline.
- RV64I plus all thirteen RV64M register instructions.
- Integer register file with x0 protection and same-cycle WB bypass.
- EX/MEM and MEM/WB forwarding.
- One-cycle load-use interlock.
- EX-stage branch and jump redirection.
- Blocking data bus and host-backed RAM adapter.
- Forwarded EX operands preserved across memory stalls.
- UART MMIO at `0x10000000` and exit MMIO at `0x10000008`.
- Architectural commit trace and temporary halt-on-exception behavior.
- Precise suppression of younger memory effects while WB retires an exception.
- Chisel tests, strict Verilator smoke, directed regressions, generated regressions and GitHub Actions evidence artifacts.

## RV64M architectural coverage

Decoder and execution support:

```text
MUL  MULH  MULHSU  MULHU
DIV  DIVU  REM     REMU
MULW DIVW  DIVUW   REMW  REMUW
```

The tests explicitly cover:

- low and high product halves;
- signed × signed, signed × unsigned and unsigned × unsigned high products;
- signed and unsigned quotient/remainder;
- divide-by-zero results;
- signed minimum divided by `-1` overflow behavior;
- all W-class operations and required 32-to-64-bit sign extension;
- back-to-back M-result dependencies;
- Load-to-M interlocks;
- M operations under deterministic memory backpressure.

The current multiply/divide implementation is combinational and correctness-first. It is suitable for architectural simulation and differential verification, but it is not the final FPGA timing/area implementation. A later microarchitectural checkpoint will replace division, and potentially multiplication, with a multi-cycle unit without changing architectural behavior.

## NEMU reference

The reference remains fixed:

- repository: `OpenXiangShan/NEMU`;
- revision: `ad6bfde6241f2fc1e864b1efb2bed99b3670eb73`;
- configuration: `riscv64-nutshell-ref_defconfig`;
- shared object: `build/riscv64-nemu-interpreter-so`;
- RAM base: `0x80000000`;
- reference RAM size: 64 MiB.

For every normal DUT retirement, the adapter checks pre-PC and all 32 GPRs, executes exactly one NEMU instruction, compares all post-GPRs and verifies enabled Store bytes in reference memory. DUT state is never copied back into NEMU after initialization.

## Test-first evidence

### Decoder red/green

The initial S0.5 decoder rejected `funct7=0000001` instructions. Intentional red runs established both boundaries:

```text
run 30696935694: legal MUL observed illegal=1
run 30697084217: MUL observed AluOp.Add, expected AluOp.Mul
```

After explicit operation identities and mappings were added, all thirteen legal encodings passed while reserved W-class `funct3=001/010/011` remained illegal.

### ALU red/green

Run `30697505995` proved the unimplemented M operations returned the default zero. The completed ALU then passed multiply-high signedness, normal division, zero division, signed overflow and all W-class semantic tests.

### Directed whole-core RV64M

Run `30697916694` passed three self-checking programs and NEMU DiffTest:

```text
rv64m_multiply: 25 commits, difftest=25
rv64m_divide:   41 commits, difftest=41
rv64m_word:     42 commits, difftest=42
```

Directed RV64M comparisons: **108**.

## Memory-stall forwarding bug found by generated DiffTest

The first generated RV64M run `30698243435` passed all prior gates and the first three seeds, then failed in `mseed_64d10004` after 243 matched retirements:

```text
pc=0x800003cc  add x16, x23, x13
NEMU x16=0xfffffffffffffb63
DUT  x16=0x0000000000000205
```

The relevant sequence was:

```text
DIV x23,...      retires in WB with x23=0
LD  x22,...      waits in MEM because ready=0
ADD x16,x23,x13  is frozen in EX
```

On the first stalled cycle, the ADD correctly saw `x23=0` through MEM/WB forwarding. At the clock edge, WB wrote the register file and `memWb.valid` was cleared. The ADD remained frozen with its old decode-time `x23=0x6a2`; on release, the forwarding source had disappeared and the stale operand produced `0x205`.

A focused eight-instruction Chisel regression reproduced the exact failure:

```text
expected x16=0xfffffffffffffb63
observed x16=0x0000000000000205
run 30698650481
```

The minimal fix preserves both already-forwarded EX operands while memory backpressure freezes the pipeline:

```scala
when(memoryStall) {
  memWb.valid := false.B
  idEx.rs1Data := forwardedRs1
  idEx.rs2Data := forwardedRs2
}
```

This changes only the stall path. Normal pipeline advancement and forwarding remain unchanged.

## Deterministic generated RV64M matrix

The generator uses an in-repository XorShift64 implementation. Every image contains all thirteen M encodings plus ALU, Load/Store, Load-to-M, zero-divisor, FENCE and x0 cases. Four images use periodic memory backpressure.

GitHub Actions run `30698820690` passed the complete repaired S1 gate:

```text
name             seed                bytes  words  stall  cycles  commits  difftest
mseed_64d10001   0x0000000064d10001  1168   292    0      308     290      290
mseed_64d10002   0x0000000064d10002  1180   295    3      329     293      293
mseed_64d10003   0x0000000064d10003  1204   301    4      346     299      299
mseed_64d10004   0x0000000064d10004  1196   299    5      330     297      297
mseed_64d10005   0x0000000064d10005  1176   294    7      320     292      292
```

Generated RV64M comparisons: **1471**.

Binary SHA-256 values:

```text
0b7d39928f4caf8d22f47604006e59460fb3d293b6d010e07453f13ebe4a8ca0  mseed_64d10001.bin
1c3a28111e9c5c2f9f815836abe532fe77466c379b6be693e169bde544bad430  mseed_64d10002.bin
70a1dbdd8e838c1d8bcfb308dcb63724a3389df6197737928788757398f05ae3  mseed_64d10003.bin
45b9c8802ee787bd56335f40aff55d4d7ba663dada040286bd3c771e943d864a  mseed_64d10004.bin
3ec1a8e8b8c57cc8a8479cc6b6187d93466ba561aed0d054aab24dd35f82e07b  mseed_64d10005.bin
```

## Complete S1 verification total

```text
S0.5 directed/generated RV64I: 1540 comparisons
S1 directed RV64M:              108 comparisons
S1 generated RV64M:            1471 comparisons
------------------------------------------------
S1 normal retirement total:    3119 comparisons
```

All 3119 normal retirements matched NEMU one-for-one. The strict smoke, explicit mismatch probe and three precise fault-boundary programs also remained green.

## Current verified boundary

- Python image/reference and deterministic-generation tests: PASS.
- complete RV64M decoder contract: PASS.
- complete RV64M ALU semantic contract: PASS.
- Chisel compilation and unit tests: PASS.
- CIRCT SystemVerilog generation: PASS.
- Verilator compile/link: PASS.
- strict smoke: PASS.
- eleven directed RV64I programs: PASS.
- five generated RV64I programs: PASS.
- three directed RV64M programs: PASS.
- five generated RV64M programs: PASS.
- 3119 normal retirements compared one-for-one with NEMU: PASS.
- explicit checker mismatch probe: PASS.
- three exact fault-boundary programs: PASS.
- deterministic memory-backpressure periods 3, 4, 5 and 7: PASS.
- focused WB-forwarding-across-memory-stall regression: PASS.

## Known engineering limitations

- Multiply/divide is currently combinational; division is not expected to meet a practical FPGA frequency target.
- There is no architectural busy/ready protocol for a multi-cycle execution unit yet.
- Privileged CSRs, trap redirection, interrupts, Sv39 and caches are not implemented.
- Precise faults still halt rather than enter an architectural trap handler.
- Verilator reports expected unused intermediate-width and generated reset-helper warnings.

## Next gate: S1.1 compiled workloads and M-unit microarchitecture

Freeze S1 before further changes. The next engineering gate should:

1. add a minimal bare-metal linker/startup path and compiler-produced RV64IM programs;
2. run arithmetic kernels through self-check and NEMU DiffTest;
3. measure generated RTL size and combinational critical-path risk;
4. define a pipeline stall/handshake contract for a multi-cycle M unit;
5. replace combinational division test-first while preserving all 3119 architectural comparisons.

Only after the compiled-workload and multi-cycle execution boundary is stable should development enter privileged architecture.