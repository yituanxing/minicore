# RV32IMU user-mode syscall checkpoint

This checkpoint adds the smallest complete Machine/User privilege boundary to
AetherCore without claiming process memory isolation.

## Control flow

```text
M-mode bootstrap
  -> mtvec = trap_handler
  -> mepc = user_main
  -> mstatus.MPP = U
  -> MRET
U-mode user program
  -> ECALL, mcause = 8
M-mode trap handler
  -> execute the requested service
  -> mepc += 4
  -> MRET
U-mode continuation
```

The current privilege is architectural state independent of `mstatus.MPP`.
Trap entry records the interrupted privilege in MPP and enters M-mode. MRET is
legal only in M-mode and restores the privilege selected by MPP.

The RV32IMU profile reports `misa=0x40101100`, including the U privilege bit.
Machine CSR addresses enforce the encoded minimum privilege in CSR bits 9:8.
A U-mode access to `mstatus`, or a U-mode MRET, raises an illegal-instruction
exception.

## Syscall ABI

The deterministic image uses the standard RISC-V argument registers:

```text
a7 = syscall number
a0 = argument 0 / return value
a1 = argument 1
```

Implemented calls:

```text
SYS_WRITE     = 1   a0=buffer, a1=length, returns bytes written
SYS_GET_TICKS = 2   returns the low 32 bits of mtime
SYS_EXIT      = 3   a0=exit status, does not return
```

The user program requests:

```text
write("hello from U-mode via SYS_WRITE\n", 32)
get_ticks()
exit(0)
```

## UART boundary

The UART remains a physical platform device at `0x10000000`. It is not removed
when U-mode is introduced. The intended ownership changes:

```text
U-mode: SYS_WRITE ECALL
M-mode: validate/consume the user buffer and write UART MMIO
```

The frozen formal user region contains zero Store instructions. Its only SYSTEM
instructions are five ECALL sites and three unreachable EBREAK sentinels. The
M-mode `SYS_WRITE` implementation contains the single UART Store site. This
proves that all observed UART bytes in the checkpoint image are kernel-mediated.

This is a software/verification contract, not yet hardware memory protection.
Without PMP or an MMU, a malicious U-mode program could still issue a physical
Store to UART or kernel memory. A later PMP checkpoint must turn such accesses
into precise access-fault traps.

## Frozen image

```text
bytes:   340
words:   85
SHA-256: 36f70e0c6b86b1cc2c3c75deb2f55a413b6955b1f1bee526a4b62bcea7d7602e

trap_handler:   0x80000034
SYS_WRITE:      0x8000006c
SYS_GET_TICKS:  0x80000098
SYS_EXIT:       0x800000a8
return_to_user: 0x800000cc
user_main:      0x800000dc
first ECALL:    0x800000ec
message:        0x80000134
```

The first U-mode ECALL is architectural event 12: eight bootstrap retirements,
then four user instructions before the ECALL. The negative DiffTest probe
corrupts x31 at this exact event and requires rejection after 12 matched events.

## Frozen execution

```text
stall  cycles  retirements  Zicsr  U-ECALL traps  MRET
0       395        254         10         3          3
5       427        254         10         3          3
```

Both schedules print the message exactly once, return a nonzero `mtime` value,
and terminate through `SYS_EXIT(0)`. Ordinary instructions remain under the
shared deterministic single-step NEMU reference. The adapter independently
shadows M/U privilege state, Machine CSRs, U-mode ECALL entry and MRET, because
the frozen NEMU regcpy ABI exposes only GPRs and PC. For the asynchronous
free-running `mtime` input, DUT-observed bytes are copied to NEMU's passive MMIO
page immediately before NEMU executes the load; NEMU still computes the load
result and all subsequent ordinary execution.

## Deliberate omissions

This checkpoint does not add:

- PMP or virtual-memory isolation;
- S-mode or delegation;
- a general process model;
- user/kernel address-space separation;
- file descriptors, blocking I/O or a general RTOS API.

The next architectural checkpoint should be PMP-backed U-mode isolation, then a
user-mode preemptive two-task test using the already validated Machine timer and
scheduler paths.
