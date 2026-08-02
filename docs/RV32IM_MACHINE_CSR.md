# RV32IM machine CSR and Zicsr checkpoint

This checkpoint is the first machine-level privileged-architecture extension of AetherCore. It adds the CSR data path and the complete `Zicsr` instruction family while deliberately retaining the existing halt-on-exception behavior.

Trap entry, `mret`, interrupts, privilege transitions, delegation, S/U modes and virtual memory are outside this checkpoint.

## Configuration contract

The ISA configuration now represents multi-letter extensions independently from the single-letter base extension set.

The active software profiles are:

```text
rv32im-software: rv32im_zicsr / ilp32
rv64im-current:  rv64im_zicsr / lp64
rv32i-minimal:   rv32i / ilp32
```

The plain RV32I profile continues to reject CSR instructions.

## Implemented machine CSRs

```text
CSR       address  access     implemented behavior
mstatus   0x300    WARL       MIE, MPIE and MPP
misa      0x301    read-only  XLEN plus generated I/M extension bits
mtvec     0x305    WARL       direct-mode aligned base
mscratch  0x340    read/write full XLEN value
mepc      0x341    WARL       aligned to the configured IALIGN
mcause    0x342    read/write full XLEN value
mtval     0x343    read/write full XLEN value
```

For the current M-only profiles, `mstatus.MPP` is canonicalized to Machine mode. `mtvec` is aligned to four bytes and only direct mode is represented. Because the current profiles do not implement the compressed extension, `mepc[1:0]` are cleared.

## Zicsr instructions

All six instructions are decoded and executed:

```text
CSRRW   CSRRS   CSRRC
CSRRWI  CSRRSI  CSRRCI
```

The implementation preserves the architectural corner cases:

- the old CSR value is returned through the ordinary GPR writeback path;
- `rd=x0` suppresses the GPR write without suppressing a required CSR write;
- `CSRRS/CSRRC` with `rs1=x0` perform a read without a CSR write;
- `CSRRSI/CSRRCI` with `zimm=0` perform a read without a CSR write;
- `CSRRW/CSRRWI` always have write intent, including a zero source;
- writes to read-only CSRs and accesses to unimplemented CSRs produce the existing precise exception marker and have no GPR, CSR or memory side effect.

## Retirement and forwarding contract

A CSR instruction computes its old value and requested new value in EX, but the CSR file changes only when the instruction reaches the valid, non-exception WB retirement boundary.

Pending CSR writes are forwarded from EX/MEM and MEM/WB so consecutive operations on the same CSR observe program order without serializing the whole pipeline.

WARL canonicalization is shared by storage and forwarding. The forwarded value is the value that will become architecturally visible, not the raw software request.

This distinction matters for sequences such as:

```text
csrw mepc, 0x80000203
csrr x6, mepc
```

The required result is `0x80000200`. An exploratory DiffTest run exposed that the first implementation stored the aligned value but forwarded the raw value. The shared `MachineCsrWarl.canonicalize` path fixes that real RTL defect.

## Directed verification

The Chisel tests cover:

- configuration and canonical `march` strings;
- all six decoder forms;
- exclusion from a plain RV32I profile;
- RV32 and RV64 `misa` construction;
- WARL behavior of `mstatus`, `mtvec` and `mepc`;
- full-width CSR storage;
- all register and immediate CSR forms through the complete pipeline;
- consecutive same-CSR dependencies;
- a load stall immediately before CSR operations;
- `rd=x0`, `rs1=x0` and `zimm=0` behavior;
- read-only `misa` write rejection;
- unimplemented CSR rejection;
- absence of architectural side effects on illegal accesses.

## Real RV32IM workload

The freestanding assembly workload is built by GCC 13.2.0 with:

```text
-march=rv32im_zicsr
-mabi=ilp32
-O2
```

It exercises all six Zicsr instructions and verifies `mscratch`, `mtvec`, `mepc`, `mcause` and `mtval`. Each failure path returns a distinct non-zero exit code.

Frozen binary:

```text
bytes:    388
words:     97
SHA-256:  84b5279e4e077fe5e6a8cf06bff9a177a2a774822b7f533c5df663e79468346f
```

## Independent reference boundary

The frozen RV32 NEMU reference remains:

```text
revision: 8601834e4889e6bf3b6113eb5f824ba7689126f5
SHA-256:  1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
ABI:      uint32_t gpr[32]; uint32_t pc
```

That historical RV32 target does not implement the machine CSR set and does not implement all six Zicsr forms. The validation boundary is therefore explicit:

- every ordinary RV32IM instruction is executed by the exact frozen NEMU reference;
- every Zicsr retirement is executed by an independent repository-owned specification model;
- the Zicsr model maintains separate machine CSR state, applies the same architectural WARL rules, updates the destination GPR and advances PC;
- the resulting GPR/PC state is copied back into NEMU, preserving one continuous retirement stream;
- subsequent ordinary instructions are again executed by NEMU;
- committed RAM and MMIO Store bytes remain checked by the existing DiffTest path.

The CSR model is not the DUT implementation reused as a reference: it is an independent C++ transition model at the retirement boundary.

## Frozen execution result

```text
cycles:                  80
retirements:             65
reference matches:       65
Zicsr shadow steps:      19
memory stall period:      5
self-check exit:          0
```

Exact success record:

```text
PASS: self-check exit=0 after 80 cycles, 65 committed instructions, stall-period=5, difftest=65, zicsr-shadow=19
```

The negative probe injects an x31 corruption at retirement index 9, which is the first CSR instruction in the workload. It must fail after exactly 9 matched retirements.

## Preserved gates

This checkpoint must leave the established gates unchanged:

```text
RV64 frozen normal-retirement gate:    944,407
RV32I GCC DiffTest:                         585
RV32IM CoreMark DiffTest:               646,301
RV32IM Embench batch 1:                 184,185
RV32IM Embench batch 2:               1,144,895
RV32IM littlefs basic:                4,819,485
```

## Next checkpoint

The next step is precise synchronous trap entry. The current one-bit exception marker will become cause/value metadata, and a faulting retirement will write `mepc`, `mcause`, `mtval` and `mstatus`, flush all younger pipeline state and redirect fetch to `mtvec` instead of halting the core.
