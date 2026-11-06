#!/usr/bin/env python3
"""Read-only performance report for existing G5 Debian D2 checkpoint slices.

The report deliberately separates guest IPC (retire/cycle) from host RTL
simulation throughput (cycles/elapsed wall second). The latter uses completed
snapshot SHA file mtimes as an *estimate*, not a benchmark timer; machine and
runner differences must be measured with a controlled A/B before comparison.

Usage:
  python3 tools/ci/product_g5_d2_perf_segments.py d2-*-live
  python3 tools/ci/product_g5_d2_perf_segments.py --json d2-*-live
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import re
import sys
from pathlib import Path


KV = re.compile(r"([a-z_]+)=([^ ]+)")
BOUNDARY = re.compile(r"^c([0-9]+)\.progress\.log$")


def fields(line: str) -> dict[str, str]:
    return dict(KV.findall(line))


def parse_progress(path: Path) -> dict[str, object] | None:
    match = BOUNDARY.fullmatch(path.name)
    if not match:
        return None
    content = path.read_text(encoding="utf-8")
    line = next((line for line in content.splitlines()
                 if line.startswith("G5_INITRAMFS_PROGRESS ")), None)
    if line is None:
        return None
    data = fields(line)
    required = ("cycles", "commits", "cursor_bytes", "total_bytes", "completed_records_approx")
    if any(key not in data for key in required):
        return None
    cycles = int(data["cycles"])
    if cycles != int(match.group(1)):
        raise ValueError(f"{path}: path and progress cycle mismatch")
    sha = path.parent / f"c{cycles}.sha256"
    if not sha.is_file():
        return None  # Ignore a slice that is still being committed.
    return {
        "cycles": cycles,
        "commits": int(data["commits"]),
        "cursor_bytes": int(data["cursor_bytes"]),
        "total_bytes": int(data["total_bytes"]),
        "records": int(data["completed_records_approx"].split("/")[0]),
        "record_total": int(data["completed_records_approx"].split("/")[1]),
        "snapshot_mtime": sha.stat().st_mtime,
        "path": str(path),
    }


def collect(globs: list[str]) -> list[dict[str, object]]:
    files = set()
    for spec in globs:
        for directory in glob.glob(spec):
            files.update(Path(directory).glob("c*.progress.log"))
    points: dict[int, dict[str, object]] = {}
    for path in files:
        record = parse_progress(path)
        if record is not None:
            prev = points.get(record["cycles"])
            if prev is not None:
                ident = ("commits", "cursor_bytes", "records", "total_bytes")
                if any(prev[key] != record[key] for key in ident):
                    raise ValueError(f"conflicting exact cycle snapshots: {prev['path']} and {path}")
            points[record["cycles"]] = record
    return [points[key] for key in sorted(points)]


def segments(points: list[dict[str, object]]) -> list[dict[str, object]]:
    output: list[dict[str, object]] = []
    for before, after in zip(points, points[1:]):
        delta_cycles = int(after["cycles"]) - int(before["cycles"])
        if delta_cycles != 5_000_000:
            continue  # Discontinuous intervals are not valid speed benchmarks.
        retired = int(after["commits"]) - int(before["commits"])
        bytes_out = int(after["cursor_bytes"]) - int(before["cursor_bytes"])
        files_out = int(after["records"]) - int(before["records"])
        if retired <= 0 or bytes_out < 0 or files_out < 0:
            raise ValueError(f"non-monotonic guest progress at {after['cycles']}")
        elapsed = float(after["snapshot_mtime"]) - float(before["snapshot_mtime"])
        output.append({
            "end_cycles": int(after["cycles"]),
            "retired": retired,
            "ipc": round(retired / delta_cycles, 6),
            "cpi": round(delta_cycles / retired, 6),
            "cpio_bytes": bytes_out,
            "cpio_records": files_out,
            "cpio_percent": round(100.0 * int(after["cursor_bytes"]) / int(after["total_bytes"]), 3),
            "elapsed_snapshot_s": round(elapsed, 2) if elapsed > 0 else None,
            "host_cycles_per_s_est": round(delta_cycles / elapsed) if elapsed > 0 else None,
        })
    return output


def selftest() -> None:
    parsed = fields("G5_INITRAMFS_PROGRESS cycles=1300000000 commits=3 completed_records_approx=1/3264")
    assert parsed == {"cycles": "1300000000", "commits": "3", "completed_records_approx": "1/3264"}
    data = [
        {"cycles": 1_300_000_000, "commits": 100, "cursor_bytes": 200, "records": 10,
         "total_bytes": 1000, "snapshot_mtime": 100.0},
        {"cycles": 1_305_000_000, "commits": 1_000_100, "cursor_bytes": 700, "records": 13,
         "total_bytes": 1000, "snapshot_mtime": 150.0},
    ]
    row, = segments(data)
    assert row["ipc"] == 0.2 and row["cpi"] == 5.0
    assert row["host_cycles_per_s_est"] == 100_000
    assert row["cpio_bytes"] == 500 and row["cpio_records"] == 3
    print("PRODUCT_G5_D2_PERF_SEGMENTS_SELFTEST_PASS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", help="checkpoint directories or shell globs")
    parser.add_argument("--json", action="store_true", help="emit JSON lines rather than CSV")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return
    if not args.paths:
        parser.error("give checkpoint directories")
    output = segments(collect(args.paths))
    if args.json:
        for row in output:
            print(json.dumps(row, sort_keys=True))
    elif output:
        writer = csv.DictWriter(sys.stdout, fieldnames=list(output[0]))
        writer.writeheader()
        writer.writerows(output)


if __name__ == "__main__":
    main()
