#!/usr/bin/env python3
"""Compare two independently mapped full Product XC7 cache geometries.

Only Yosys unplaced Xilinx-cell counts: never routed timing, Fmax, board fit,
or Debian functionality. Both cases must be actual, same-source successes.
"""

import argparse
import json
from pathlib import Path
import sys


class ComparisonError(ValueError):
    pass


def _read_profile(directory: Path, entries: int) -> dict:
    export = json.loads((directory / "manifest.json").read_text())
    result = json.loads((directory / "mapped/result.json").read_text())
    hierarchy = json.loads((directory / "mapped/hierarchy.json").read_text())
    for key in ("git_sha", "top"):
        if export.get(key) != result.get(key):
            raise ComparisonError(f"{entries}: inconsistent {key}")
    for identity in (export, result):
        if identity.get("cache_i_entries") != entries or identity.get("cache_d_entries") != entries:
            raise ComparisonError(f"{entries}: wrong cache size")
    if (export.get("status") != "RTL_EXPORT_PASS" or
            result.get("status") != "XC7_UNPLACED_MAPPING_PASS" or
            hierarchy.get("status") != "XC7_MAPPED_HIERARCHY_PARSED"):
        raise ComparisonError(f"{entries}: missing real mapping/hierarchy PASS")
    if result.get("rtl_file_hashes_sha256") != export.get("rtl_file_hashes_sha256"):
        raise ComparisonError(f"{entries}: mapped RTL hash differs from exported source")
    if result.get("placed") is not False or result.get("routed") is not False or result.get("physical_timing_qualified") is not False:
        raise ComparisonError(f"{entries}: qualification fields are inconsistent")
    if (result.get("emitter") != "aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1CacheAbRv64gc" or
            export.get("emitter") != result.get("emitter") or
            result.get("top") != hierarchy.get("top")):
        raise ComparisonError(f"{entries}: incorrect full SoC emitter/top")
    metrics = {"luts": hierarchy["total_luts"], "ffs": hierarchy["total_ffs"],
               "dsp48e1": hierarchy["total_dsp48e1"]}
    for cell in ("RAMB18E1", "RAMB36E1", "RAM32M", "RAM64M", "CARRY4", "MUXF7", "MUXF8"):
        metrics[cell.lower()] = hierarchy.get("primitive_counts", {}).get(cell, 0)
    if any(not isinstance(value, int) or value < 0 for value in metrics.values()):
        raise ComparisonError(f"{entries}: invalid mapped cell metrics")
    return {"entries": entries, "rtl_hashes_sha256": result["rtl_file_hashes_sha256"],
            "yosys_version": result.get("yosys_version"), "metrics": metrics,
            "git_sha": export["git_sha"], "top": export["top"]}


def compare(base_dir: Path) -> dict:
    p0 = _read_profile(base_dir / "cache-64", 64)
    p1 = _read_profile(base_dir / "cache-256", 256)
    if (p0["git_sha"] != p1["git_sha"] or p0["top"] != p1["top"] or
            not p0["yosys_version"] or p0["yosys_version"] != p1["yosys_version"]):
        raise ComparisonError("profiles differ by source SHA, top or Yosys version")
    return {
        "status": "XC7_CACHE_CAPACITY_RESOURCE_AB_ONLY", "git_sha": p0["git_sha"],
        "top": p0["top"], "yosys_version": p0["yosys_version"],
        "p0_512b": p0, "c1_2kib": p1,
        "delta_2kib_minus_512b": {key: p1["metrics"][key] - value for key, value in p0["metrics"].items()},
        "placed": False, "routed": False, "fmax_qualified": False,
        "qualification": "Same-SHA unplaced XC7 cells only; cannot establish part fit, DDR PHY, 200MHz, IPC or Debian boot",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_dir", type=Path)
    parser.add_argument("output_json", type=Path)
    args = parser.parse_args()
    if args.output_json.exists():
        print("ERROR: refusing to overwrite comparison evidence", file=sys.stderr)
        return 2
    try:
        result = compare(args.input_dir)
    except (ComparisonError, OSError, KeyError, ValueError) as exc:
        print(f"XC7_CACHE_AB_NOT_QUALIFIED: {exc}", file=sys.stderr)
        return 1
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(result, indent=2) + "\n")
    print("XC7_CACHE_AB_RESOURCE_COMPARISON:", json.dumps(result["delta_2kib_minus_512b"], sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
