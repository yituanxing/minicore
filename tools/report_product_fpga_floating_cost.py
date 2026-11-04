#!/usr/bin/env python3
"""Reproducible Yosys cost delta: F/D-on minus integer-only SmallOoO.

These are structure-only, board-neutral synth_ecp5/ltp proxy measurements.
They never qualify HardFloat completeness, timing closure, or Debian Linux.
"""

import json
import sys
from pathlib import Path


METRICS = ("cells", "lut4", "ff", "bram", "dpr", "dsp", "depth")


def report(capacity: str, root: Path) -> dict:
    if capacity not in ("small", "expanded"):
        raise ValueError("capacity must be small or expanded")
    work = root / capacity
    integer = json.loads((work / "integer/metrics.json").read_text())
    floating = json.loads((work / "floating/metrics.json").read_text())
    if integer.get("capacity") != capacity or floating.get("capacity") != capacity:
        raise ValueError("inconsistent capacity metadata")
    if integer.get("isa") != "integer" or floating.get("isa") != "floating":
        raise ValueError("inconsistent ISA metadata")
    delta = {key: floating[key] - integer[key] for key in METRICS}
    comparison = {
        "top": "ProductSmallMemoryCore",
        "capacity": capacity,
        "paddr_bits": 32,
        "issue_width": 1,
        "comparison": "RV64IMA(LP64) versus partial RV64GC(LP64D) hardware slice",
        "proxy": "Yosys synth_ecp5 + ltp -noff (board-neutral)",
        "integer": integer,
        "floating": floating,
        "floating_overhead": delta,
        "qualification": (
            "Area/depth proxy only; ProductSmallPagedCore still rejects F/D; "
            "IEEE arithmetic, Linux distro and board Fmax not qualified"
        ),
    }
    (work / "summary.json").write_text(json.dumps(comparison, indent=2) + "\n")
    print(json.dumps(comparison, indent=2))
    return comparison


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: report_product_fpga_floating_cost.py small|expanded OUTPUT_DIRECTORY")
    report(sys.argv[1], Path(sys.argv[2]))
