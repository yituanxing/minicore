#!/usr/bin/env python3
"""Host-side real-UART transport for the Product FPGA FW+DTB DDR loader.

This is an explicit physical-board action, not a simulation fallback or a test.
Only the pinned SD1 payload manifest is accepted. Install pyserial on the host
before intentionally invoking this utility with a real USB-UART serial port.
"""

from __future__ import annotations

import argparse
import hashlib
from dataclasses import dataclass
from pathlib import Path
import struct
import sys
import time
import zlib


FW_ADDRESS = 0x8000_0000
DTB_ADDRESS = 0x87F0_0000
MAX_FIRMWARE_BYTES = DTB_ADDRESS - FW_ADDRESS
MAX_DTB_BYTES = 0x0010_0000
WINDOW_BYTES = 1024
HEADER_MAGIC = b"AETU"
ACK_READY = b"R"
ACK_HEADER = b"H"
ACK_WINDOW = b"K"
DEFAULT_MANIFEST = (
    Path(__file__).resolve().parents[2]
    / "build/rv64-debian-boot/result.txt"
)


@dataclass(frozen=True)
class Image:
    path: Path
    kind: int
    byte_count: int
    crc32: int
    sha256: str


def manifest_values(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ValueError(f"missing SD1 payload result manifest: {path}")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        if key in values and values[key] != value:
            raise ValueError(f"conflicting {key!r} values in {path}")
        values[key] = value
    if values.get("spi_sd") != "1" or not values.get("sd_image_sha256"):
        raise ValueError("manifest is not bound to the persistent SD1 firmware/DTB")
    return values


def read_image(values: dict[str, str], manifest: Path, kind: int) -> Image:
    prefix = "firmware_bin" if kind == 1 else "fdt"
    expected_sha = values.get(f"{prefix}_sha256", "")
    value = values.get(prefix, "")
    if not value or len(expected_sha) != 64:
        raise ValueError(f"SD1 manifest lacks {prefix} and its SHA256 identity")
    source = Path(value)
    if not source.is_absolute():
        source = manifest.parent / source
    source = source.resolve()
    if not source.is_file():
        raise ValueError(f"missing {prefix} image: {source}")

    size_limit = MAX_FIRMWARE_BYTES if kind == 1 else MAX_DTB_BYTES
    byte_count = 0
    crc = 0
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            byte_count += len(chunk)
            if byte_count > size_limit:
                raise ValueError(f"{prefix} exceeds pinned DDR address aperture")
            crc = zlib.crc32(chunk, crc)
            digest.update(chunk)
    if byte_count == 0:
        raise ValueError(f"{prefix} is empty")
    actual_sha = digest.hexdigest()
    if actual_sha.lower() != expected_sha.lower():
        raise ValueError(f"{prefix} SHA256 differs from pinned SD1 manifest")
    return Image(source, kind, byte_count, crc & 0xFFFFFFFF, actual_sha)


def await_ack(serial_port, expected: bytes, max_seconds: float) -> None:
    deadline = time.monotonic() + max_seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(
                f"UART timeout waiting for {expected!r}; board may have faulted "
                "or DDR ownership may never have been granted"
            )
        serial_port.timeout = min(1.0, remaining)
        token = serial_port.read(1)
        if token == expected:
            return
        # DDR-granted READY repeats every 500ms until the FW header starts.
        # An earlier beacon may still be in the host RX FIFO when H arrives.
        if token in (b"", ACK_READY) and expected != ACK_READY:
            continue
        if token == b"" and expected == ACK_READY:
            continue
        if token:
            raise ValueError(f"unexpected UART token {token!r}; expected {expected!r}")


def send_image(serial_port, image: Image, timeout: float) -> None:
    header = struct.pack("<4sBII", HEADER_MAGIC, image.kind,
                         image.byte_count, image.crc32)
    serial_port.write(header)
    serial_port.flush()
    await_ack(serial_port, ACK_HEADER, timeout)

    remaining = image.byte_count
    with image.path.open("rb") as stream:
        while remaining:
            window = stream.read(min(remaining, WINDOW_BYTES))
            if not window:
                raise IOError(f"{image.path} was truncated after manifest verification")
            serial_port.write(window)
            serial_port.flush()
            remaining -= len(window)
            # The last DTB DDR write may release CPU and mux TX to the Linux
            # console before a UART uploader ACK could finish. No final ACK.
            if remaining != 0 or image.kind != 2:
                await_ack(serial_port, ACK_WINDOW, timeout)

    # A concurrent mutation after initial SHA audit must not go undetected.
    # The FPGA independently checks the uploaded stream's CRC before allowing
    # its last word through; a changed source cannot silently boot as correct.


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True,
                        help="USB-UART serial device, e.g. /dev/ttyUSB0")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST,
                        help="SD1 payload result.txt with firmware+DTB SHA256")
    parser.add_argument("--baud", type=int, default=1_000_000,
                        help="must match hardware uploadBaud (default 1000000)")
    parser.add_argument("--console-baud", type=int, default=115200,
                        help="Linux ttyS0 UART after CPU release (default 115200)")
    parser.add_argument("--ready-timeout", type=float, default=180.0)
    parser.add_argument("--credit-timeout", type=float, default=35.0)
    parser.add_argument("--follow-console", action="store_true",
                        help="switch to 115200 and print Linux UART bytes indefinitely")
    args = parser.parse_args()
    if args.baud <= 0 or args.console_baud <= 0:
        parser.error("baud rates must be positive")
    if args.ready_timeout <= 0 or args.credit_timeout <= 0:
        parser.error("timeouts must be positive")

    manifest = args.manifest.resolve()
    values = manifest_values(manifest)
    images = (read_image(values, manifest, 1), read_image(values, manifest, 2))
    for item in images:
        print(f"Pinned {'FW' if item.kind == 1 else 'DTB'}: "
              f"{item.byte_count} bytes, CRC32={item.crc32:08x}, SHA256={item.sha256}",
              flush=True)

    try:
        import serial  # pyserial: host-only dependency, not required by RTL
    except ImportError as error:
        raise RuntimeError("install pyserial on the *host* to use this tool") from error

    with serial.Serial(args.port, baudrate=args.baud, timeout=1.0,
                       write_timeout=args.credit_timeout,
                       bytesize=serial.EIGHTBITS,
                       parity=serial.PARITY_NONE,
                       stopbits=serial.STOPBITS_ONE) as port:
        port.reset_input_buffer()
        print("Waiting for physical DDR-grant READY 'R' beacon...", flush=True)
        await_ack(port, ACK_READY, args.ready_timeout)
        for item in images:
            print(f"Uploading {'FW' if item.kind == 1 else 'DTB'} "
                  f"at {args.baud} 8N1 baud...", flush=True)
            send_image(port, item, args.credit_timeout)
        print("Final CRC-checked DTB bytes submitted; no terminal ACK. "
              "The FPGA has NOT been proven to boot Linux yet.", flush=True)
        port.baudrate = args.console_baud
        if args.follow_console:
            print(f"Following Linux console at {args.console_baud} baud; "
                  "Ctrl-C to stop.", flush=True)
            try:
                while True:
                    output = port.read(4096)
                    if output:
                        sys.stdout.buffer.write(output)
                        sys.stdout.buffer.flush()
            except KeyboardInterrupt:
                pass
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, IOError, TimeoutError, RuntimeError) as exc:
        print(f"ABORTED (board state unknown; reset before retry): {exc}",
              file=sys.stderr)
        sys.exit(2)
