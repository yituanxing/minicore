#!/usr/bin/env python3
"""Inspect the actual SD1 full host checkpoint before accepting a relay stage.

The UART is in AETSDCP1's Observer, not in the advance-only log.  Only the
bounded snapshot preamble is read for diagnosis; the full host SHA is checked
against the publish-once .snapshot-ready manifest before accepting it.
No guest code, RTL, disk image, or snapshot is executed or modified here.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

MAGIC = b"AETSDCP1"
VERSION = 1
FNV_OFFSET = 14695981039346656037
FNV_PRIME = 1099511628211
MAX_UART = 32 * 1024 * 1024
FAILURES = (
    b"kernel panic", b"unable to mount root fs", b"cannot open root device",
    b"attempted to kill init", b"end kernel panic", b"aethercore_sd1_fail",
)


def manifest(path: Path) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep or not key or key in fields:
            raise ValueError("duplicate or malformed SD1 stage manifest")
        fields[key] = value
    return fields


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_exact(stream, count: int) -> bytes:
    data = stream.read(count)
    if len(data) != count:
        raise ValueError("truncated SD1 host checkpoint")
    return data


def read_u64(stream) -> int:
    return struct.unpack("<Q", read_exact(stream, 8))[0]


def read_string(stream, limit: int) -> bytes:
    size = read_u64(stream)
    if size > limit:
        raise ValueError("unbounded SD1 string length")
    return read_exact(stream, size)


def decode(path: Path) -> dict[str, object]:
    with path.open("rb") as stream:
        if read_exact(stream, 8) != MAGIC or read_u64(stream) != VERSION:
            raise ValueError("wrong SD1 host checkpoint schema")
        identities = [read_string(stream, 128).decode("ascii") for _ in range(6)]
        if not all(re.fullmatch("[0-9a-f]{64}", digest) for digest in identities):
            raise ValueError("malformed immutable SD1 model/media identity")
        read_string(stream, 128)  # UART trigger
        read_string(stream, 4096)  # UART input policy
        read_u64(stream)  # UART input post-IRQ deadline
        time = read_u64(stream)
        read_u64(stream)  # DDR wait profile
        observer = [read_u64(stream) for _ in range(15)]
        uart = read_string(stream, MAX_UART)
        opensbi, login, input_started = [read_u64(stream) for _ in range(3)]
        if any(flag > 1 for flag in (opensbi, login, input_started)):
            raise ValueError("noncanonical SD1 observer boolean")
        fnv = FNV_OFFSET
        for byte in uart:
            fnv = ((fnv ^ byte) * FNV_PRIME) & ((1 << 64) - 1)
        if observer[0] != time or observer[13] != fnv:
            raise ValueError("SD1 cycle/UART observer fingerprint mismatch")
    low = uart.lower()
    failure = next((pattern.decode() for pattern in FAILURES if pattern in low), None)
    return {
        "cycles": observer[0], "commits": observer[1],
        "uart_bytes": len(uart), "opensbi": bool(opensbi),
        "login": bool(login), "uart_hash": f"{fnv:016x}",
        "failure": failure,
        "ext4_root_mounted": b"vfs: mounted root (ext4 filesystem)" in low,
        "native_init_started": b"run /sbin/init as init process" in low,
        "identity": identities,
    }


def validate(path: Path, ready: Path, minimum_commits: int) -> dict[str, object]:
    if path.stat().st_size <= 268435456:
        raise ValueError("full 256 MiB SD1 DDR/card host state is absent")
    pins = manifest(ready)
    expected = {
        "snapshot_ready": "1", "top": "AetherCoreProductDebianSdRV64SimTop",
        "schema": MAGIC.decode(), "saves_full_rtl_ddr_sd": "1",
    }
    if any(pins.get(k) != v for k, v in expected.items()):
        raise ValueError("not a published full SD1 snapshot")
    result = decode(path)
    if pins.get("boot_cycles") != str(result["cycles"]):
        raise ValueError("SD1 checkpoint cycle differs from published stage")
    if pins.get("snapshot_host_sha256") != sha256(path):
        raise ValueError("SD1 snapshot host SHA differs from published stage")
    for key, digest in zip(("model", "firmware", "dtb", "sd_image", "linux_image", "rootfs_result"), result["identity"]):
        if pins.get(f"{key}_sha256") != digest:
            raise ValueError(f"SD1 {key} identity differs from published stage")
    if result["failure"]:
        raise ValueError(f"guest UART contains fatal diagnostic: {result['failure']}")
    if result["commits"] <= minimum_commits:
        raise ValueError("SD1 retired instruction count did not advance")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", required=True, type=Path,
                        help="directory with persistent.pre.host and .snapshot-ready")
    parser.add_argument("--minimum-commits", type=int, default=-1)
    args = parser.parse_args()
    try:
        result = validate(args.snapshot / "persistent.pre.host",
                          args.snapshot / ".snapshot-ready", args.minimum_commits)
    except (ValueError, OSError, UnicodeError) as error:
        parser.exit(3, f"PRODUCT_SD1_RELAY_REJECTED: {error}\n")
    print("PRODUCT_SD1_RELAY_CHECKPOINT_OK " + json.dumps(
        {k: v for k, v in result.items() if k != "identity"},
        sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
