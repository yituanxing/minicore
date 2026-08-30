#!/usr/bin/env python3
"""Rank AetherCore FPGA architecture candidates before production RTL exists.

The parser consumes an attributed Linux log containing AETHERCORE_V2_TOPDOWN,
AETHERCORE_V2_ATTR_V11 and AETHERCORE_V2_ARCH_OPP snapshots.  It reports
optimistic cycle-speedup caps for counters that represent reclaimable cycles and
pressure fractions for capacity-limited structures.

Optional --cost entries add a conservative FPGA density sanity check:
  candidate:lut_percent:fmax_loss_percent

Example:
  fpga_arch_opportunity_gate.py boot.log \
    --cost dual_domain_issue:2.0:1.0 \
    --cost second_compute_issue:8.0:5.0
"""

from __future__ import annotations

import argparse
import math
import re
from pathlib import Path

KV = re.compile(r"([A-Za-z0-9_]+)=([0-9]+)")


def parse_snapshot(lines: list[str]) -> dict[str, int]:
    merged: dict[str, int] = {}
    for line in lines:
        if not line.startswith("AETHERCORE_V2_"):
            continue
        for key, value in KV.findall(line):
            merged[key] = int(value)
    return merged


def ideal_speedup(cycles: int, opportunity: int) -> float:
    if cycles <= 0:
        return 1.0
    opportunity = max(0, min(opportunity, cycles - 1))
    return cycles / (cycles - opportunity)


def pct(value: int, cycles: int) -> float:
    return 0.0 if cycles <= 0 else 100.0 * value / cycles


def parse_cost(text: str) -> tuple[str, float, float]:
    try:
        candidate, lut, fmax = text.split(":", 2)
        return candidate, float(lut), float(fmax)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            "cost must be candidate:lut_percent:fmax_loss_percent"
        ) from exc


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log", type=Path)
    ap.add_argument(
        "--cost",
        action="append",
        default=[],
        type=parse_cost,
        help="candidate:lut_percent:fmax_loss_percent",
    )
    args = ap.parse_args()

    lines = args.log.read_text(encoding="utf-8", errors="replace").splitlines()
    # Use the last marker/exit snapshot family. Periodic snapshots occur before
    # it, so walking from the end gives the qualified terminal observation.
    tail_start = 0
    for idx, line in enumerate(lines):
        if line.startswith("AETHERCORE_V2_TOPDOWN reason=marker") or line.startswith(
            "AETHERCORE_V2_TOPDOWN reason=exit"
        ):
            tail_start = idx
    data = parse_snapshot(lines[tail_start:])

    cycles = data.get("cycles", 0)
    if cycles <= 0:
        raise SystemExit("no attributed cycles found in log")

    exact = {
        "second_compute_issue": data.get("dual_compute_candidate", 0),
        "dual_domain_issue": data.get("dual_domain_pairable", 0),
        "fetch_queue": data.get("frontend_bound", 0),
    }
    pressure = {
        "rob8": data.get("rob_full_dispatch_pressure", 0),
        "loadq4": data.get("loadq_full_ready_load", 0),
    }
    critical_caps = {
        "load_path": data.get("critical_load_head", 0),
        "store_queue": data.get("critical_store_atomic_head", 0),
    }

    costs = {name: (lut, fmax) for name, lut, fmax in args.cost}

    print(f"cycles={cycles}")
    print("\n[optimistic cycle-speedup caps]")
    for name, opportunity in exact.items():
        speedup = ideal_speedup(cycles, opportunity)
        print(
            f"{name}: cycles={opportunity} fraction={pct(opportunity, cycles):.3f}% "
            f"ideal_speedup<={speedup:.5f}x"
        )
        if name in costs:
            lut_pct, fmax_loss_pct = costs[name]
            area_ratio = 1.0 + lut_pct / 100.0
            fmax_ratio = max(0.0, 1.0 - fmax_loss_pct / 100.0)
            throughput_upper = speedup * fmax_ratio
            density_upper = throughput_upper / area_ratio
            verdict = "PRE-REJECT" if density_upper <= 1.0 else "ELIGIBLE"
            print(
                f"  cost: LUT +{lut_pct:.2f}% Fmax -{fmax_loss_pct:.2f}% "
                f"ideal_throughput<={throughput_upper:.5f}x "
                f"ideal_perf_per_LUT<={density_upper:.5f}x {verdict}"
            )

    print("\n[capacity pressure -- requires trace/shadow model before RTL]")
    for name, value in pressure.items():
        print(f"{name}: cycles={value} pressure={pct(value, cycles):.3f}%")

    print("\n[retirement-critical ownership caps]")
    for name, value in critical_caps.items():
        print(
            f"{name}: cycles={value} fraction={pct(value, cycles):.3f}% "
            f"ideal_speedup_if_all_removed<={ideal_speedup(cycles, value):.5f}x"
        )

    print("\n[existing supporting evidence]")
    for key in (
        "backend_bound",
        "branch_recovery",
        "branch_squashed_uops",
        "issue_idle_launchable",
        "issue_idle_no_launchable",
        "memory_terminal_hold",
    ):
        if key in data:
            print(f"{key}={data[key]}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
