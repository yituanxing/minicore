# RV32IM precise Machine trap return checkpoint

This checkpoint closes the first complete Machine-mode synchronous exception loop:

```text
normal code
  -> precise synchronous trap entry
  -> Machine handler
  -> handler inspects and updates CSRs
  -> MRET
  -> normal code resumes from mepc
```

The existing five-stage pipeline remains unchanged. `MRET` is carried through the ordinary pipeline and takes effect only at the WB architectural boundary, where it updates `mstatus`, redirects fetch to `mepc`, and flushes all younger instructions.

Interrupts, delegation, runtime U/S privilege transitions and virtual memory remain outside this checkpoint.

## Implemented MRET instruction

The exact privileged SYSTEM encoding is recognized:

```text
MRET = 0x30200073
```

It is available in the current Zicsr-enabled RV32IM/RV64IM profiles. The plain `rv32i-minimal` profile continues to report the encoding as illegal.

`MRET` is a normal non-exception architectural event:

- it does not write a GPR;
- it does not perform a memory operation;
- it does not report a synchronous exception;
- it redirects only when it reaches WB;
- it cannot be executed by a squashed younger instruction.

## Machine status transition

At the MRET edge:

```text
mstatus.MIE  <- mstatus.MPIE
mstatus.MPIE <- 1
mstatus.MPP  <- least supported privilege mode
PC           <- WARL-aligned mepc
```

The current real software profile implements only Machine mode, so the least supported privilege is M and the observed transition is:

```text
trap handler mstatus = 0x00001880
post-MRET mstatus    = 0x00001888
```

The CSR implementation nevertheless derives the least supported privilege from the profile. MPP WARL writes accept only privilege modes implemented by that profile and never retain the reserved encoding `2`.

Priority at the architectural boundary is:

```text
trap entry > MRET > ordinary Zicsr write
```

## Precise return boundary

An MRET can reach WB while a younger sequential instruction already occupies MEM. The core therefore blocks younger data-memory requests combinationally in both trap-entry and MRET redirect cycles.

On MRET:

```text
PC <- mepc
IF/ID.valid  <- 0
ID/EX.valid  <- 0
EX/MEM.valid <- 0
MEM/WB.valid <- 0
```

Every real workload places a Store immediately after MRET. Its sentinel must remain zero, proving the younger Store or MMIO request cannot escape before the redirect flush.

## Independent reference boundary

The exact historical RV32 NEMU reference remains unchanged:

```text
revision: 8601834e4889e6bf3b6113eb5f824ba7689126f5
SHA-256:  1dc17e1d2c8d27959fc3fa30163a350a57c688e102c64372e396f350699db577
ABI:      uint32_t gpr[32]; uint32_t pc
```

Each execution is one continuous checked event stream:

- ordinary RV32IM instructions execute in frozen NEMU;
- Zicsr instructions execute in the independent CSR shadow;
- synchronous trap entry executes in the independent trap shadow;
- MRET executes in a separate return shadow;
- the MRET shadow validates the absence of register/memory side effects, restores machine status and sets the next reference PC to `mepc`;
- handler and resumed instructions continue through the same mixed reference;
- Store-byte checking remains active.

No MRET instruction is skipped or delegated to an unsupported historical NEMU path.

## Real software workloads

Four freestanding GCC 13.2.0 RV32IM/Zicsr binaries cover distinct control flows.

### `ecall-next`

- raises an M-mode ECALL;
- validates trap state;
- advances `mepc` by four;
- returns to the instruction following ECALL;
- validates restored `mstatus` and the return PC.

### `ebreak-rewrite`

- raises a breakpoint;
- rewrites `mepc` to a non-sequential label;
- proves MRET follows the handler-selected address rather than `pc + 4`;
- proves the original sequential path is not executed.

### `load-fault`

- raises a Load access fault at `0x90000000`;
- validates fault address metadata;
- advances `mepc` beyond the faulting Load;
- resumes normal execution.

### `double-ecall`

- performs two complete ECALL/handler/MRET loops in one process;
- checks execution between the two traps;
- checks the handler return count through `mscratch`;
- proves machine status stacking remains stable across repeated returns.

All workloads also verify:

- handler `mstatus = 0x1880`;
- resumed `mstatus = 0x1888`;
- handler-selected `mepc` is retained;
- younger MRET-path Store sentinel remains zero;
- final MMIO exit code is zero.

## Frozen exploratory result

The first complete functional run on head `64910570a7d22dec6afb1e8bebbcf0551edd4c30` produced:

```text
case              bytes  words  SHA-256
ecall-next          352     88  6ddc9d73287798e8dd173221eaaa8866d668acedf004da0ce4b25538b2727702
ebreak-rewrite      368     92  6847a2fc4601317b4fd6348124d1b8c3462207b239d1bcf8bd361672b7b72e3b
load-fault          360     90  03bfdc5ce7f38a6029213c0b986d641fcc5266fd326a3a1c04524b47b0f8a567
double-ecall        392     98  307119e8576defdfc0804d3ff2176e0728b8b1887dad0f643b488b0bc3044042
```

Execution:

```text
case              cycles  events  Zicsr  traps  MRET
ecall-next            81      57      13      1     1
ebreak-rewrite        82      58      13      1     1
load-fault            84      59      13      1     1
double-ecall         121      85      20      2     2
-----------------------------------------------------
total                368     259      59      5     5
```

All 259 events matched the continuous mixed reference.

The first MRET in `ecall-next` is event index 39. CI deliberately flips x31 on that event and requires immediate rejection after exactly 39 matched events. This proves the return step itself is actively checked rather than merely inferred from later execution.

Exploratory artifact:

```text
artifact:             rv32im-machine-return-30735961004
artifact ID:          8829588972
artifact ZIP SHA-256: 5acf75097c7d18174596fb261fc09dfbc268ebd3fe6acd3ccb515e7465f026db
```

## Verification layers

The checkpoint is protected by:

1. Decoder tests for exact MRET recognition and exclusion from the plain RV32I profile.
2. Machine CSR tests for trap-return status restoration, return PC and priority over ordinary CSR writes.
3. A full-pipeline test that traps, modifies `mepc`, retires MRET as a normal event, resumes, checks `mstatus=0x1888`, and proves the younger Store was suppressed.
4. Four real freestanding workloads under memory stalls.
5. Exact frozen NEMU/CSR/trap/MRET mixed-reference checking.
6. A negative probe on the first MRET event.
7. Every pre-existing RV32/RV64 architecture and real-software regression.

## Remaining boundary

This checkpoint does not yet implement:

- a runtime current-privilege register;
- MRET legality checks outside M mode;
- U-mode or S-mode execution;
- asynchronous interrupts;
- `mie`, `mip`, `mtime` or `mtimecmp`;
- exception delegation;
- virtual memory.

The next architecture checkpoint can now add a machine timer interrupt using the same precise WB redirect and MRET return machinery. A later U-mode checkpoint will make `mstatus.MPP` restoration change the runtime privilege state rather than only the CSR-visible state.
