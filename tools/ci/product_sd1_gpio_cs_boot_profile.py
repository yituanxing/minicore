#!/usr/bin/env python3
"""Verify patched Product SD1 RTL stages and preserve comparable boot timing.

Only reads AETSDCP1 host snapshots; no guest execution or SD mutation. This
diagnostic manifest is distinct from the official frozen SD1 relay manifest.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re

from product_sd1_relay_state import (
    MAGIC, VERSION, decode, manifest, read_exact, read_string, read_u64, sha256,
)

IDENTITIES = ("model", "firmware", "dtb", "sd_image", "linux_image", "rootfs_result")
QUALIFICATION = "SD1_GPIO_CS_DIAGNOSTIC_CHECKPOINT_NOT_DEBIAN_PASS"


def load_stage(directory: Path):
    ready_file = directory / ".diag-ready"
    rtl = directory / "persistent.pre.rtl"
    host = directory / "persistent.pre.host"
    for path in (ready_file, rtl, host):
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"missing or linked checkpoint member: {path}")
    if host.stat().st_size <= 268435456:
        raise ValueError("missing full 256MiB DDR+SD host state")
    pins = manifest(ready_file)
    if (pins.get("qualification") != QUALIFICATION or
            pins.get("top") != "AetherCoreProductDebianSdRV64SimTop" or
            pins.get("schema") != "AETSDCP1"):
        raise ValueError("wrong patched kernel diagnostic checkpoint qualification")
    for kind, path in (("rtl", rtl), ("host", host)):
        if pins.get(f"snapshot_{kind}_sha256") != sha256(path):
            raise ValueError(f"{kind} checkpoint SHA mismatch")
    timing_file = directory / "phase-timing.txt"
    if not timing_file.is_file() or timing_file.is_symlink():
        raise ValueError("missing checkpoint performance evidence")
    if pins.get("phase_timing_sha256") != sha256(timing_file):
        raise ValueError("phase timing SHA mismatch")
    stats = decode(host)
    if pins.get("boot_cycles") != str(stats["cycles"]):
        raise ValueError("manifest cycle differs from AETSDCP1 state")
    for name, digest in zip(IDENTITIES, stats["identity"]):
        if pins.get(f"{name}_sha256") != digest:
            raise ValueError(f"checkpoint {name} identity mismatch")
    if stats["failure"]:
        raise ValueError(f"real guest UART fatal diagnostic: {stats['failure']}")
    return pins, stats


def uart_bytes(path: Path) -> bytes:
    # Layout is the already validated AETSDCP1 header; read only bounded UART.
    with path.open("rb") as stream:
        if read_exact(stream, 8) != MAGIC or read_u64(stream) != VERSION:
            raise ValueError("wrong host checkpoint header")
        for _ in range(6):
            read_string(stream, 128)
        read_string(stream, 128)  # UART trigger
        read_string(stream, 4096)  # UART policy
        read_u64(stream)  # post IRQ deadline
        read_u64(stream)  # cycle
        read_u64(stream)  # DDR wait profile
        for _ in range(15):
            read_u64(stream)  # observer
        return read_string(stream, 32 * 1024 * 1024)


def timings(path: Path) -> dict[str, str]:
    return manifest(path / "phase-timing.txt")


def printk_gap(text: bytes):
    points = []
    for match in re.finditer(rb"\[\s*(\d+)\.(\d{1,9})\]", text):
        seconds = int(match.group(1)) + int(match.group(2).ljust(9, b"0")) / 1e9
        points.append(seconds)
    if len(points) < 2:
        return None
    nonnegative = [b - a for a, b in zip(points, points[1:]) if b >= a]
    return round(max(nonnegative), 9) if nonnegative else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--current", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    old_pins, old = load_stage(args.previous)
    new_pins, new = load_stage(args.current)
    for key in (*IDENTITIES, "source_model_key", "patch_sha256", "qualification"):
        if old_pins.get(key) != new_pins.get(key):
            raise ValueError(f"incompatible diagnostic continuation: {key}")
    delta_cycles = new["cycles"] - old["cycles"]
    delta_commits = new["commits"] - old["commits"]
    if delta_cycles <= 0 or delta_cycles > 20_000_000 or delta_commits <= 0:
        raise ValueError("no monotonic RTL cycle/retirement advance")
    old_uart = uart_bytes(args.previous / "persistent.pre.host")
    new_uart = uart_bytes(args.current / "persistent.pre.host")
    if not new_uart.startswith(old_uart):
        raise ValueError("UART bytes changed instead of append-only continuation")
    timing = timings(args.current)
    elapsed = int(timing["save_or_advance_elapsed_seconds"])
    if timing.get("mode") != "advance" or int(timing["advance_delta_cycles"]) != delta_cycles:
        raise ValueError("simulator advancement timing does not match RTL cycle advance")
    if timing.get("checkpoint_total_cycles") != str(new["cycles"]):
        raise ValueError("timing target mismatches saved RTL checkpoint")
    segment = {
        "qualification": QUALIFICATION,
        "from_cycles": old["cycles"], "to_cycles": new["cycles"],
        "delta_cycles": delta_cycles,
        "from_retired": old["commits"], "to_retired": new["commits"],
        "delta_retired": delta_commits,
        "cycles_per_retired_instruction": round(delta_cycles / delta_commits, 6),
        "sim_wall_seconds": elapsed,
        "sim_cycles_per_wall_second": round(delta_cycles / elapsed, 3) if elapsed else None,
        "uart_from_bytes": len(old_uart), "uart_to_bytes": len(new_uart),
        "uart_delta_bytes": len(new_uart) - len(old_uart),
        "uart_sha256": hashlib.sha256(new_uart).hexdigest(),
        "printk_max_guest_seconds_gap": printk_gap(new_uart),
        "opensbi_seen": new["opensbi"],
        "ext4_root_mounted": new["ext4_root_mounted"],
        "native_init_started": new["native_init_started"],
        "login_seen": new["login"],
        "guest_failure": new["failure"],
        "sd_command_count": None, "sd_reads": None, "sd_writes": None,
        "sd_init_complete": None, "sd_cow_count": None,
        "identity_sha256": {name: new_pins[f"{name}_sha256"] for name in IDENTITIES},
        "model_source_key": new_pins["source_model_key"],
        "patch_sha256": new_pins["patch_sha256"],
        "snapshot_rtl_sha256": new_pins["snapshot_rtl_sha256"],
        "snapshot_host_sha256": new_pins["snapshot_host_sha256"],
        "is_full_debian_success": False,
        "is_cmd0_proven": False,
    }
    args.output.mkdir(parents=True, exist_ok=False)
    outputs = {
        "segments.jsonl": (json.dumps(segment, sort_keys=True, separators=(",", ":")) + "\n").encode(),
        "uart-cumulative.log": new_uart,
        "uart-delta.bin": new_uart[len(old_uart):],
    }
    for name, payload in outputs.items():
        (args.output / name).write_bytes(payload)
    with (args.output / "SHA256SUMS").open("w") as stream:
        for name in sorted(outputs):
            stream.write(f"{sha256(args.output / name)}  {name}\n")
    print("PRODUCT_SD1_GPIO_CS_DIAG_PROFILE: " + json.dumps(segment, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit(f"PRODUCT_SD1_GPIO_CS_DIAG_PROFILE_REJECTED: {error}") from error
