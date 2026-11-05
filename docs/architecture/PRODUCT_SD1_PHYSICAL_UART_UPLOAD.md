# Product SD1: physical UART upload of OpenSBI and DTB

Status: **source-level board integration, not a tested FPGA boot**. Do not
conflate this with Linux/Debian execution or a qualified DDR/SD PHY.

## Why a separate upload transport

`AetherProductDdrFirmwareStreamLoader` already checks the exact two DRAM
segments and all AXI write responses, but its `segment` and `wordIn` ports
need a real source after DDR calibration. `AetherProductUartFirmwareUpload`
provides a synthesizable **full-duplex 8N1 UART byte-source controller**,
without any invisible simulation memory access or fake SD/JTAG PHY.

The same physical USB-UART RX/TX pads serve two mutually exclusive owners:

1. **Cold loader**: board clock 50 MHz; upload line rate **1,000,000 baud**;
   fractional 16 MHz baud-tick enable; Linux held in reset and DDR controller
   reserved for `AetherProductDdrFirmwareStreamLoader`.
2. **CPU released**: cold loader loses ownership; board TX mux switches to
   the normal Product ns16550 Linux serial TX at **115,200 baud** with its
   independent 3.6864 MHz UART reference tick. The SD rootfs is accessed by
   Linux over the board's SPI-SD path, not over the upload link.

The board must provide a **single physical 50 MHz system clock**, qualified
`clockLocked && ddrCalibrated`, the vendor-specific DDR IP/pin constraints,
USB-UART-level electrical wiring, SD-card IO voltage/pullups and actual serial
pin ownership. The serial source does not implement a physical DDR PHY or
silently synthesize unavailable board components.

## Wire protocol

All multi-byte header fields are little-endian. One session always contains
exactly two frames; the board fault state requires a full reset, not a retry.

| Phase | Host to board | Board to host |
| --- | --- | --- |
| DDR calibration/loader grant | Wait; transmit **nothing** | `R` (0x52) every ~500 ms while waiting for first header |
| Firmware descriptor | `AETU` + `01` + `fw_byte_count:u32` + `CRC32:u32` | `H` (0x48) after loader accepts descriptor |
| Firmware content | At most **1024 bytes per window** | `K` (0x4B) after last AXI B for the window; **also** after firmware's last window |
| DTB descriptor | `AETU` + `02` + `dtb_byte_count:u32` + `CRC32:u32` | `H` after loader accepts descriptor |
| DTB content | At most 1024 bytes per window | `K` for each window **except** the very last DTB window |
| End | Flush final serial byte, switch host to 115200 | No final uploader ACK; Linux UART begins only after actual final DDR B succeeds |

CRC32 is IEEE reflected polynomial `0xedb88320`, zero as the initial Python
`zlib.crc32` argument, and is computed across the exact original file bytes.
The RTL uses internal initialize-to-all-ones/final-complement representation.
The fixed DDR destinations are `0x80000000` for OpenSBI `fw_payload.bin` and
`0x87f00000` for the separate DTB. Firmware must be at most `0x07f00000`
bytes; DTB must be at most 1 MiB; neither segment may be empty.

**The final word of each frame is withheld until its CRC32 is verified.** A
corrupt DTB therefore cannot trigger a final DDR B or CPU release. Each
1024-byte credit window fits an equally bounded **1024-byte on-chip FIFO**,
even if DDR stalls for the entire host window. This FIFO requires 8 Kib of
storage (plus pointers/control), which a board synthesis flow may map to BRAM;
the wrapper does not assume a particular FPGA RAM primitive. UART cannot
physically stop an uncooperative host:
an RX buffer overrun or malformed transfer is a sticky board fault; there
is no unsafe best-effort resynchronization. An upload timeout/stalled DDR B
also fails closed after a configurable 30 seconds. Successful acceptance of
the final DTB bytes is *not* the same as successful DDR B completion or Linux
boot; the DDR loader and cold-boot controller independently guard that event.

The last DTB window intentionally omits `K`: asserting `cpuReleased` on the
last AXI B transfers the **same** TX pad to Linux, which could cut off a
half-transmitted uploader ACK. The host must therefore treat final send as
`submitted_unverified`, then look for subsequent Linux console messages or
board-specific status evidence. A host timeout or serial disconnect is not a
safe retry point; reset the board and begin with a new READY beacon.

## Later opt-in physical use

After producing and auditing **exactly matched** SD1 firmware/DTB/SD image
with the existing offline artifact builders, first check the exact staged
native Debian supply chain **without opening a UART or requiring pyserial**:

```bash
python3 tools/ci/product_sd1_uart_firmware_upload.py --preflight-only
```

This verifies the final SD1 firmware PASS marker, Product board selection,
disk-root bootargs, firmware and independent DTB content SHA256, the DTB
header/DDR address, the complete raw MBR/ext4 SD image SHA256, and the tied
Linux Image/.config/rootfs/SD manifests. It validates the **offline image**,
not the contents of a physical removable SD card.

Later, once a board exists, program that separate complete MBR/ext4 SD image
onto a real SD device. On a host connected to the board's serial pins,
install `pyserial`, then explicitly invoke:

```bash
python3 tools/ci/product_sd1_uart_firmware_upload.py --port /dev/ttyUSB0 --follow-console
```

The utility reads `build/rv64-debian-boot/result.txt`, repeats all of the
offline supply-chain checks, validates the firmware/DTB bounds, waits for
the DDR READY beacon, sends checked windowed frames, then switches the host
port to 115200 after the final DTB bytes. In addition to the board's CRC32
verification, it SHA256-checks the **file bytes handed to the host UART** to
detect a concurrent host-side image mutation. For the last DTB window, the
CPU may already have been released before this final host SHA check finishes;
any mismatch means **reset before retry**. It **does not** claim success
merely because serial bytes were written, nor because the offline SD image
matches the SD card that may eventually be inserted into a board.

This entire source round deliberately performs **no compilation, simulator
run, board run, long Linux validation, or CI launch**.
