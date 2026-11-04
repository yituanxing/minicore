#!/usr/bin/env python3
"""Full-SoC pin/probe and F-off/F-on FPGA synthesis proxy comparison.

Each of the four netlists uses the same CPU geometry and PA32 system. Probe
and pins differ only at the external observation boundary; F-off and F-on
select the same SoC with RV64IMAC/LP64 versus RV64GC/LP64D hardware.
"""

import json
import sys
from pathlib import Path


METRICS = ("port_bits", "cells", "lut4", "ff", "bram", "dpr", "dsp", "depth")
ISAS = ("integer", "floating")
BOUNDARIES = ("probe", "pins")


def report(capacity: str, root: Path) -> dict:
    if capacity not in ("small", "expanded"):
        raise ValueError("capacity must be small or expanded")

    results = {}
    for isa in ISAS:
        results[isa] = {}
        for boundary in BOUNDARIES:
            data = json.loads(
                (root / capacity / isa / boundary / "metrics.json").read_text()
            )
            expected_top = (
                "AetherCoreProductFpgaPins" if boundary == "pins"
                else "AetherCoreProductFpgaSoC"
            )
            if (data.get("capacity"), data.get("isa"),
                    data.get("boundary"), data.get("top")) != (
                        capacity, isa, boundary, expected_top):
                raise ValueError(f"inconsistent netlist metadata: {isa}/{boundary}")
            if not all(isinstance(data.get(k), int) and data[k] >= 0
                       for k in METRICS):
                raise ValueError(f"missing/invalid synthesis metrics: {isa}/{boundary}")
            results[isa][boundary] = data

    def difference(left: dict, right: dict) -> dict:
        return {key: left[key] - right[key] for key in METRICS}

    comparison = {
        "capacity": capacity,
        "paddr_bits": 32,
        "issue_width": 1,
        "proxy": "Yosys synth_ecp5 + ltp -noff (unrouted, board-neutral)",
        "measurements": results,
        "probe_overhead": {
            isa: difference(results[isa]["probe"], results[isa]["pins"])
            for isa in ISAS
        },
        "floating_overhead": {
            boundary: difference(results["floating"][boundary],
                                 results["integer"][boundary])
            for boundary in BOUNDARIES
        },
        "qualification": (
            "Pin-only RTL has no external retire/trace probes; both tops run "
            "the same CPU/AXI/UART. This is not routed Fmax, DDR hardware, "
            "IEEE instruction differential, Linux PID1 or Debian qualification."
        ),
    }
    target = root / capacity / "summary.json"
    target.write_text(json.dumps(comparison, indent=2) + "\n")
    print(json.dumps(comparison, indent=2))
    return comparison


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: report_product_fpga_pin_cost.py small|expanded OUTPUT_DIRECTORY")
    report(sys.argv[1], Path(sys.argv[2]))
