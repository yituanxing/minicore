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
import re
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
SHA256_PATTERN = re.compile(r"[a-fA-F0-9]{64}\Z")


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
    lines = path.read_text(encoding="utf-8").splitlines()
    if "RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS" not in lines:
        raise ValueError("SD1 payload has no final Debian PASS marker")
    for raw in lines:
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        if key in values and values[key] != value:
            raise ValueError(f"conflicting {key!r} values in {path}")
        values[key] = value
    for field, expected in (
        ("spi_sd", "1"), ("product_board", "1"),
        ("linux_stage", "sd1"),
    ):
        if values.get(field) != expected:
            raise ValueError(f"SD1 payload requires {field}={expected}")
    for field in (
        "sd_image_sha256", "sd_image_manifest_sha256", "linux_result_sha256",
        "linux_image_sha256", "linux_config_sha256", "rootfs_result_sha256",
        "rootfs_tree_sha256", "firmware_bin_sha256", "fdt_sha256",
    ):
        if not SHA256_PATTERN.fullmatch(values.get(field, "")):
            raise ValueError(f"missing or invalid SD1 payload {field}")
    if values.get("fdt_addr", "").lower() != hex(DTB_ADDRESS):
        raise ValueError("SD1 payload DTB handoff is not the board DDR loader address")
    bootargs = values.get("bootargs", "").split()
    for token in (
        "root=/dev/mmcblk0p1", "rootfstype=ext4", "rootwait",
        "rw", "init=/sbin/init",
    ):
        if token not in bootargs:
            raise ValueError(f"SD1 payload missing native disk-root argument: {token}")
    return values


def supplied_path(values: dict[str, str], field: str, manifest: Path) -> Path:
    value = values.get(field, "")
    if not value:
        raise ValueError(f"SD1 payload lacks {field}")
    path = Path(value)
    if not path.is_absolute():
        path = manifest.parent / path
    path = path.resolve()
    if not path.is_file():
        raise ValueError(f"SD1 {field} file is missing: {path}")
    return path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def require_sha(path: Path, expected: str, what: str) -> None:
    if sha256_file(path).lower() != expected.lower():
        raise ValueError(f"{what} bytes differ from the pinned SD1 identity: {path}")


def read_result(path: Path, marker: str) -> dict[str, str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if marker not in lines:
        raise ValueError(f"SD1 supplier is not qualified by {marker}: {path}")
    result: dict[str, str] = {}
    for raw in lines:
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        if key in result and result[key] != value:
            raise ValueError(f"conflicting {key} values in {path}")
        result[key] = value
    return result


def verify_supply_chain(values: dict[str, str], manifest: Path) -> Path:
    """Check the entire software handoff BEFORE opening a physical UART.

    The firmware and DTB are independently loaded into board DDR; the SD
    rootfs is separate removable media. Verify these were all built against
    one native Linux/rootfs generation, and reject edits made after staging.
    This can also run offline, without a board or pyserial installation.
    """
    sd_image = supplied_path(values, "SD_IMAGE", manifest)
    sd_manifest = supplied_path(values, "sd_image_manifest", manifest)
    require_sha(sd_manifest, values["sd_image_manifest_sha256"], "SD media manifest")
    sd = read_result(sd_manifest,
                     "RV64_DEBIAN_SD1_SD_IMAGE_RESULT: status=READY_UNVERIFIED")
    for field, expected in (
        ("stage", "sd1"), ("partition_table", "MBR"),
        ("partition_count", "1"), ("partition1_type", "0x83"),
        ("partition1_fs", "ext4"), ("partition1_first_lba", "2048"),
        ("sector_bytes", "512"),
        ("sd_image_sha256", values["sd_image_sha256"]),
        ("rootfs_result_sha256", values["rootfs_result_sha256"]),
        ("rootfs_tree_sha256", values["rootfs_tree_sha256"]),
    ):
        if sd.get(field) != expected:
            raise ValueError(f"SD media manifest disagrees on {field}={expected}")
    if supplied_path(sd, "SD_IMAGE", sd_manifest) != sd_image:
        raise ValueError("firmware and SD manifests refer to different disk paths")
    if sd_image.stat().st_size != int(sd.get("disk_bytes", "0")):
        raise ValueError("SD card image length changed after MBR/ext4 preparation")
    require_sha(sd_image, values["sd_image_sha256"], "native Debian SD image")

    linux_image = supplied_path(values, "linux_image", manifest)
    # The Linux build has a fixed out-of-tree obj layout; reject a copied or
    # renamed kernel that cannot be connected to the matching .config.
    if linux_image.parts[-4:] != ("arch", "riscv", "boot", "Image"):
        raise ValueError("SD1 Linux Image has an unexpected build layout")
    linux_obj = linux_image.parents[3]
    if linux_obj.name != "obj":
        raise ValueError("SD1 Linux Image does not reside under its obj tree")
    linux_result_path = linux_obj.parent / "result.txt"
    linux_result = read_result(linux_result_path,
                               "RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS")
    require_sha(linux_result_path, values["linux_result_sha256"], "Linux manifest")
    linux_config = linux_obj / ".config"
    if not linux_config.is_file():
        raise ValueError("SD1 Linux .config is absent")
    require_sha(linux_image, values["linux_image_sha256"], "Linux Image")
    require_sha(linux_config, values["linux_config_sha256"], "Linux .config")
    for field, expected in (
        ("stage", "sd1"),
        ("image_sha256", values["linux_image_sha256"]),
        ("config_sha256", values["linux_config_sha256"]),
        ("rootfs_result_sha256", values["rootfs_result_sha256"]),
        ("rootfs_tree_sha256", values["rootfs_tree_sha256"]),
    ):
        if linux_result.get(field) != expected:
            raise ValueError(f"SD1 Linux manifest disagrees on {field}={expected}")
    if supplied_path(linux_result, "image", linux_result_path) != linux_image:
        raise ValueError("SD1 Linux result references a different Image")
    return sd_image


def read_image(values: dict[str, str], manifest: Path, kind: int) -> Image:
    prefix = "firmware_bin" if kind == 1 else "fdt"
    expected_sha = values.get(f"{prefix}_sha256", "")
    value = values.get(prefix, "")
    if not value or not SHA256_PATTERN.fullmatch(expected_sha):
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
    if kind == 2:
        with source.open("rb") as stream:
            header = stream.read(8)
        if len(header) < 8 or struct.unpack(">I", header[:4])[0] != 0xD00DFEED:
            raise ValueError("standalone Product DTB lacks flattened-device-tree magic")
        total_size = struct.unpack(">I", header[4:])[0]
        if total_size < 40 or total_size > byte_count:
            raise ValueError("standalone Product DTB reports invalid total size")
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
    if serial_port.write(header) != len(header):
        raise IOError(f"UART short write of {image.path} image descriptor")
    serial_port.flush()
    await_ack(serial_port, ACK_HEADER, timeout)

    remaining = image.byte_count
    transmitted_sha = hashlib.sha256()
    with image.path.open("rb") as stream:
        while remaining:
            window = stream.read(min(remaining, WINDOW_BYTES))
            if not window:
                raise IOError(f"{image.path} was truncated after manifest verification")
            if serial_port.write(window) != len(window):
                raise IOError(f"UART short write of {image.path} payload window")
            serial_port.flush()
            transmitted_sha.update(window)
            remaining -= len(window)
            # The last DTB DDR write may release CPU and mux TX to the Linux
            # console before a UART uploader ACK could finish. No final ACK.
            if remaining != 0 or image.kind != 2:
                await_ack(serial_port, ACK_WINDOW, timeout)

    # Board-side CRC guards accidental link/data corruption, but CRC32 is not
    # a cryptographic content identity. Recheck the bytes actually sent; a
    # concurrent local file mutation must be reported even if its CRC collides.
    # DTB's final window can already release the CPU, so any failure here
    # requires a board reset before retry (as stated by the CLI error).
    if transmitted_sha.hexdigest() != image.sha256:
        raise ValueError(f"image changed during UART transmission: {image.path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port",
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
    parser.add_argument("--preflight-only", action="store_true",
                        help="verify pinned SD/Linux/FW/DTB identities offline; never access a UART")
    args = parser.parse_args()
    if args.baud <= 0 or args.console_baud <= 0:
        parser.error("baud rates must be positive")
    if args.ready_timeout <= 0 or args.credit_timeout <= 0:
        parser.error("timeouts must be positive")
    if not args.preflight_only and not args.port:
        parser.error("--port is required unless --preflight-only is selected")

    manifest = args.manifest.resolve()
    values = manifest_values(manifest)
    sd_image = verify_supply_chain(values, manifest)
    images = (read_image(values, manifest, 1), read_image(values, manifest, 2))
    print(f"Pinned native Debian SD image: {sd_image}, "
          f"SHA256={values['sd_image_sha256']}", flush=True)
    for item in images:
        print(f"Pinned {'FW' if item.kind == 1 else 'DTB'}: "
              f"{item.byte_count} bytes, CRC32={item.crc32:08x}, SHA256={item.sha256}",
              flush=True)
    if args.preflight_only:
        print("PRODUCT_SD1_UART_UPLOAD_OFFLINE_PREFLIGHT_PASS: "
              "all native Debian SD, Linux, firmware and DTB identities agree; "
              "no UART, simulator or FPGA was accessed", flush=True)
        return 0

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
        if "--preflight-only" in sys.argv:
            print(f"OFFLINE PREFLIGHT FAILED (no UART or board accessed): {exc}",
                  file=sys.stderr)
        else:
            print(f"ABORTED (board state unknown; reset before retry): {exc}",
                  file=sys.stderr)
        sys.exit(2)
