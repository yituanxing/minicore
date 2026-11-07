#!/usr/bin/env python3
"""Read only the versioned SD-card state embedded in a full Product SD1 host checkpoint.

The legacy RTL/DDR/host checkpoint is never rewritten. The 256 MiB DDR
payload is SEEKED, not loaded, and the SDSP01 payload SHA256 is checked.
A recognized command is not, by itself, a CMD0/R1 success certificate.
"""

from __future__ import annotations
import argparse
import hashlib
import json
import struct
from pathlib import Path
from product_sd1_relay_state import MAGIC, VERSION, read_exact, read_string, read_u64

DDR_BYTES = 256 * 1024 * 1024
MAX_CARD_BYTES = 512 * 1024 * 1024
SD_MAGIC = b"ACSDSP01"
CARD_PREFIX_BYTES = 123


def _u32(stream):
    return struct.unpack("<I", read_exact(stream, 4))[0]


def decode_card_prefix(raw: bytes) -> dict:
    """Decode the fixed SDSP01 v1 body prefix; never infer CMD0 from command count."""
    if len(raw) != CARD_PREFIX_BYTES:
        raise ValueError("SD card snapshot body prefix truncated")
    offset = 0

    def take(fmt):
        nonlocal offset
        result = struct.unpack_from("<" + fmt, raw, offset)
        offset += struct.calcsize("<" + fmt)
        return result[0] if len(result) == 1 else result

    sector_count, partition_start, partition_sectors = take("QII")
    media_sha = raw[offset:offset + 32].hex()
    offset += 32
    flags = [take("B") for _ in range(10)]
    if any(flag not in (0, 1) for flag in flags):
        raise ValueError("noncanonical SD state boolean")
    write_state, rx_byte, rx_bits, tx_byte, tx_bit = [take("B") for _ in range(5)]
    command_bytes, write_bytes = take("II")
    next_read_lba, next_write_lba = take("QQ")
    commands, reads, writes, startup_clocks = take("QQQQ")
    last_command = take("I")
    if offset != CARD_PREFIX_BYTES:
        raise ValueError("SD card prefix reader drift")
    if (not sector_count or partition_start >= sector_count or
            partition_sectors > sector_count - partition_start or
            write_state > 2 or rx_bits > 7 or tx_bit > 7 or
            command_bytes > 5 or write_bytes > 514 or
            startup_clocks > 74 or last_command > 63 or
            (not flags[6] and startup_clocks >= 74)):
        raise ValueError("SD card snapshot protocol or media fields invalid")
    return {
        "sd_media_sha256": media_sha,
        "sd_sector_count": sector_count,
        "sd_partition_start_lba": partition_start,
        "sd_partition_sectors": partition_sectors,
        "sd_command_count": commands,
        "sd_reads": reads,
        "sd_writes": writes,
        "sd_last_command": last_command if commands else None,
        "sd_init_complete": not bool(flags[0]),
        "sd_high_capacity": bool(flags[1]),
        "sd_74_clocks_seen": bool(flags[6]),
        "sd_startup_valid_clocks": startup_clocks,
        "sd_last_cs_high": bool(flags[8]),
        "sd_rx_bits": rx_bits,
        "sd_command_partial_bytes": command_bytes,
        "sd_next_read_lba": next_read_lba,
        "sd_next_write_lba": next_write_lba,
    }


def card_state_from_host(path: Path) -> dict:
    """Parse AETSDCP1 + inner ACSDSP01 v1, verify SD body digest, stream no DDR bytes."""
    if path.stat().st_size <= DDR_BYTES:
        raise ValueError("missing full 256MiB host state")
    with path.open("rb") as stream:
        if read_exact(stream, 8) != MAGIC or read_u64(stream) != VERSION:
            raise ValueError("wrong AETSDCP1 host checkpoint")
        identities = [read_string(stream, 128).decode("ascii") for _ in range(6)]
        for value in identities:
            if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
                raise ValueError("unqualified host checkpoint identity")
        read_string(stream, 128)
        read_string(stream, 4096)
        for _ in range(3 + 15):
            read_u64(stream)  # input policy, saved cycle/wait, fixed Observer counters
        read_string(stream, 32 * 1024 * 1024)  # UART, already checked by load_stage
        for _ in range(32 + 4):
            read_u64(stream)  # remainder Observer + DDR wait
        if read_u64(stream) != DDR_BYTES:
            raise ValueError("SD1 host DDR extent is not 256 MiB")
        stream.seek(DDR_BYTES, 1)
        for _ in range(3):
            read_u64(stream)  # LR/SC reservation
        card_length = read_u64(stream)
        if card_length < 52 + CARD_PREFIX_BYTES + 6 + 514 + 4 + 8 or card_length > MAX_CARD_BYTES:
            raise ValueError("unbounded SD card snapshot")
        if read_exact(stream, 8) != SD_MAGIC or _u32(stream) != 1:
            raise ValueError("SD1 card state is not the frozen ACSDSP01 v1 schema")
        body_length = read_u64(stream)
        digest = read_exact(stream, 32)
        if body_length != card_length - 52:
            raise ValueError("SD card envelope length mismatch")
        body_at = stream.tell()
        prefix = read_exact(stream, CARD_PREFIX_BYTES)
        state = decode_card_prefix(prefix)
        if state["sd_media_sha256"] != identities[3]:
            raise ValueError("SD card media SHA differs from AETSDCP1 host identity")
        stream.seek(6 + 514, 1)  # partial command and pending write-packet bytes
        pending_count = _u32(stream)
        if pending_count > body_length:
            raise ValueError("SD response FIFO length exceeds body")
        stream.seek(pending_count, 1)
        overlay_count = read_u64(stream)
        consumed = stream.tell() - body_at
        if overlay_count > state["sd_sector_count"] or body_length - consumed != overlay_count * 520:
            raise ValueError("SD COW overlay sector count/length mismatch")
        state["sd_cow_count"] = overlay_count
        stream.seek(body_at)
        remaining = body_length
        hash_state = hashlib.sha256()
        while remaining:
            chunk = read_exact(stream, min(1024 * 1024, remaining))
            hash_state.update(chunk)
            remaining -= len(chunk)
        if hash_state.digest() != digest or stream.read(1):
            raise ValueError("SD card body SHA256 mismatch or trailing host bytes")
        state["sd_card_body_sha256"] = hash_state.hexdigest()
        state["sd_card_schema_version"] = 1
        return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", type=Path, required=True)
    parser.add_argument("--expected-media-sha", required=True)
    args = parser.parse_args()
    state = card_state_from_host(args.host)
    if state["sd_media_sha256"] != args.expected_media_sha:
        raise SystemExit("PRODUCT_SD1_CARD_SNAPSHOT_REJECTED: expected media SHA differs")
    print("PRODUCT_SD1_CARD_SNAPSHOT: " + json.dumps(state, sort_keys=True))


if __name__ == "__main__":
    main()
