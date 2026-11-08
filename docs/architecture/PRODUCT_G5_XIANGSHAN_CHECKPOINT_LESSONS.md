# Product G5: XiangShan checkpoint lessons and warm-start gates

Status: implementation checklist; **not** a claim of QEMU-to-RTL handoff or Debian PASS.

## Primary references

- XiangShan NEMU README, *Workflows* and *Why cannot produce a checkpoint in M-mode?*:
  https://github.com/OpenXiangShan/NEMU
- XiangShan's published SimPoint and GCPT restorer instructions:
  https://docs.xiangshan.cc/zh-cn/latest/tools/simpoint/
- XiangShan DiffTest and LightSSS snapshot mechanism:
  https://github.com/OpenXiangShan/difftest
- XiangShan 2026 infrastructure report (DiffTest, GSIM, FPGA, checkpoints):
  https://docs.xiangshan.cc/zh-cn/latest/blog/2026/08/19/biweekly-109-en/

## What we actually reuse

1. Produce a full-system *architectural* checkpoint with a fast golden model.
   Do not treat a raw RAM dump, an ISA trace, or a QEMU-only success as a
   bootable RTL checkpoint. XiangShan separates its fast checkpoint producer
   from the RTL consumer and uses DiffTest to check the result.
2. Resume in a known S/U privilege context. XiangShan explicitly warns that
   restoring an M-mode checkpoint through a return/trap restorer can overwrite
   the EPC architecture state. Our historical MRET shim is a limited synthetic
   first-instruction proof, **not** a lossless real Linux state restoration.
3. Seal the original physical memory and *every* architectural control state:
   PC, privilege, x/f registers, mstatus/sstatus, satp, PMP, trap delegation,
   interrupt enable/pending and timer/interrupt-device state. QEMU and DUT ISA
   and paging configurations must match; the observed H-extension mismatch was
   removed only when the QEMU source was captured with `h=off`.
4. Perform first-retire comparison before a lengthy run. The current source
   oracle is Linux PC `0xffffffff808c3490`, physical fetch `0x80ac3490`,
   RVC word `0x7179`, SP `0xffffffc80000bec0` -> `0xffffffc80000be90`,
   next PC `0xffffffff808c3492`. The synthetic G5 RVC retire was observed,
   but authentic QEMU RAM and full CSR/device import are **not** yet proven.
5. Diff architectural state and committed instructions against a golden
   reference from the *same restored state*. Record the earliest mismatch;
   retries should resume from short sealed checkpoints with diagnostic traces,
   in the spirit of LightSSS. Do not mask an earlier mismatch by waiting for
   a terminal UART message.

## Gates before claiming a successful warm handoff

| Gate | Required proof | Current qualification |
| --- | --- | --- |
| G0: source | QEMU ISA/features/paging, SHA-pinned RAM and state manifest | Sv39 + H-off source captured |
| G1: synthetic RTL | Same real Linux opcode, first PC and register result on genuine RTL | Passed on synthetic RAM/state only |
| G2: direct seed | Full G5 PC/priv/CSR/register injection with frozen clocks, production path unchanged | In implementation; **not yet compiled/qualified** |
| G3: authentic state | Exact QEMU RAM imported and first retire checked on G5 RTL | Not yet demonstrated |
| G4: differential | Multiple genuine RTL commits match the golden model, with timer/PLIC/UART/SD ownership specified | Not yet demonstrated |
| G5: full Debian | Genuine Product RTL UART reaches actual service/login | Not yet demonstrated |

## Implementation hazards

- Retire PC and physical fetch must use identical Sv39 settings; a valid
  page-table walk alone says nothing about running all subsequent instructions.
- Rename maps, free lists and physical register file state must be mutually
  consistent after seeding, especially with an out-of-order backend.
- No timer/PLIC/UART input may move while reset is released and state is still
  being installed; capture a quiescent handoff boundary or define replay rules.
- A restorer that executes guest instructions changes the exact PC/CSR state;
  explicitly account for every instruction or use simulation-only direct seed.
- A savable Verilator model snapshot is tied to the precise generated RTL
  build/ABI. Migrating only the Host runner across the 8B SD1 safety bound
  requires proving zero guest cycles and byte-identical RTL plus 20M replay
  equivalence before continuing. Never disable the existing 8B guard blindly.
- Progress counters, ext4/native-init markers, CI green, and QEMU green are
  all below the full Debian PASS threshold.

Decision: Keep the official signed SD1 cold-to-warm RTL relay independent of
experimental QEMU-to-G5 state import. Move the experimental path forward by
one **real**, differential first-retire gate at a time while retaining strict
provenance and the previous successful artifacts.
