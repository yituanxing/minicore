#!/usr/bin/env python3
"""Compare two unplaced XC7 PMP geometries without inventing device fit."""

import argparse
import json
from pathlib import Path
import re


LUTS = tuple(f"LUT{i}" for i in range(1, 7))
FFS = ("FDRE", "FDSE", "FDCE", "FDPE")
RAMS = ("RAM32M", "RAM64M", "RAM32X1S", "RAM64X1S", "RAM128X1S")


def read_counts(path: Path, top: str) -> dict[str, int]:
    log = path.read_text()
    # Yosys emits preliminary statistics before the final mapped `stat`.
    # Use the last matching header; a two-module design must count the
    # `design hierarchy` rather than only the parent or geometry submodule.
    matches = list(re.finditer(r"(?m)^=== design hierarchy ===\s*$", log))
    if not matches:
        matches = list(re.finditer(rf"(?m)^=== {re.escape(top)} ===\s*$", log))
    if not matches:
        raise ValueError(f"{path}: missing mapped hierarchy for {top}")
    tail = log[matches[-1].end():]
    end = tail.find("Estimated number of LCs:")
    if end == -1:
        raise ValueError(f"{path}: incomplete mapped statistics")
    section = tail[:end]
    cells: dict[str, int] = {}
    for line in section.splitlines():
        match = re.fullmatch(r"\s*(\d+)\s+([A-Z][A-Z0-9_]+)\s*", line)
        if match:
            amount, cell = match.groups()
            cells[cell] = int(amount)
    if sum(cells.get(cell, 0) for cell in LUTS) == 0:
        raise ValueError(f"{path}: no mapped LUTs (truncated or wrong module?)")
    return {
        "lut1_6": sum(cells.get(cell, 0) for cell in LUTS),
        "ff": sum(cells.get(cell, 0) for cell in FFS),
        "distributed_ram": sum(cells.get(cell, 0) for cell in RAMS),
        "dsp48e1": cells.get("DSP48E1", 0),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--serial", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    baseline = read_counts(args.baseline, "PmpRangeDecoder")
    serial = read_counts(args.serial, "PmpSerialRangePredecoder")
    report = {
        "qualification": "unplaced XC7 standalone Yosys mapping; not full SoC Fmax or Debian PASS",
        "geometry": "RV64 / PA56 / PMP16",
        "baseline_parallel": baseline,
        "candidate_serial": serial,
        "delta_candidate_minus_baseline": {
            key: serial[key] - baseline[key] for key in baseline
        },
        "refresh_cycles": 16,
        "functional_status": "independent ChiselSim qualification required before integration",
    }
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
