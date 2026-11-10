#!/usr/bin/env python3
"""Check exact-source Nexys Video post-route evidence without claiming board boot.

The marker is only written by the separate Vivado 2026.1 project implementation
script after routing, static setup/hold timing, and DRC checks.  The host also
checks the upstream and generated Product provenance before writing result.json.
"""

import argparse
import hashlib
import json
import math
import subprocess
import sys
from pathlib import Path


REPORTS = (
    "synth_utilization.rpt", "synth_timing_summary.rpt", "synth.dcp",
    "route_utilization.rpt", "route_status.rpt", "route_clocks.rpt",
    "route_check_timing.rpt", "route_timing_summary.rpt", "route_drc.rpt",
    "routed.dcp",
)
EXPECTED_PART = "xc7a200tsbg484-1"
EXPECTED_REFERENCE = "1e6b86aebe5ba6675a1193ec12b417c1f7cb8bf0"


def require(ok: bool, description: str) -> None:
    if not ok:
        raise ValueError(description)


def read_marker(path: Path) -> dict[str, str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    require(lines and lines[0] == "NEXYS_ROUTED_TIMING_ONLY",
            "Vivado routed timing marker missing or mismatched")
    values: dict[str, str] = {}
    for line in lines[1:]:
        require(line.count("=") == 1, f"invalid evidence field: {line}")
        key, value = line.split("=", 1)
        require(key not in values and key and value, "repeated or empty evidence field")
        values[key] = value
    require(set(values) == {
        "part", "vivado_version", "setup_wns_ns", "hold_whs_ns",
        "drc_violations", "bitstream_generated", "ddr_board_calibrated",
        "fpga_debian_login",
    }, "missing or unexpected routed evidence keys")
    require(values["part"] == EXPECTED_PART, "unexpected routed FPGA part")
    require(values["vivado_version"].startswith("2026.1"),
            "pinned 2026.1 Vivado evidence required")
    require(values["drc_violations"] == "0", "routed DRC is not clean")
    for key in ("bitstream_generated", "ddr_board_calibrated", "fpga_debian_login"):
        require(values[key] == "false", f"unexpected physical boot claim: {key}")
    for key in ("setup_wns_ns", "hold_whs_ns"):
        value = float(values[key])
        require(math.isfinite(value) and value >= 0, f"bad routed slack: {key}")
    return values


def verify(output_dir: Path, repo: Path, product_export: Path) -> dict:
    physical = output_dir / "physical_reports"
    require((output_dir / "LOGICAL_BD_ONLY.txt").is_file(),
            "logical BD validation must precede routing")
    require((output_dir / "aether_nexys_product.xpr").is_file(),
            "real Vivado project absent")
    require(not (physical / "result.json").exists(),
            "refusing to overwrite previous routed evidence")
    values = read_marker(physical / "route-evidence.txt")
    for name in REPORTS:
        path = physical / name
        require(path.is_file() and path.stat().st_size > 0,
                f"Vivado report/checkpoint absent or empty: {name}")

    source = json.loads((output_dir / "reference-source.json").read_text())
    ports = json.loads((output_dir / "product-axi-ports.json").read_text())
    manifest = json.loads((product_export / "manifest.json").read_text())
    sha_list = (product_export / "rtl.sha256").read_bytes()
    sha = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"],
                                  text=True).strip()
    require(source.get("status") == "NEXYS_REFERENCE_SOURCE_ONLY" and
            source.get("upstream_commit") == EXPECTED_REFERENCE and
            source.get("device") == EXPECTED_PART,
            "upstream Nexys reference identity lost")
    require(ports.get("status") == "PRODUCT_NEXYS_AXIMM_PORTS_ONLY" and
            ports.get("mapped_product_ports") == 50 and
            ports.get("axi_data_bits") == 64 and ports.get("axi_id_bits") == 4,
            "generated Product AXI port gate absent")
    require(manifest.get("status") == "RTL_EXPORT_PASS" and
            manifest.get("git_sha") == sha and
            manifest.get("rtl_file_hashes_sha256") == hashlib.sha256(sha_list).hexdigest(),
            "generated RTL SHA/commit mismatch")

    return {
        "status": "NEXYS_ROUTED_TIMING_ONLY",
        "git_sha": sha,
        "upstream_reference_commit": EXPECTED_REFERENCE,
        "rtl_file_hashes_sha256": manifest["rtl_file_hashes_sha256"],
        "part": EXPECTED_PART,
        "vivado_version": values["vivado_version"],
        "setup_wns_ns": float(values["setup_wns_ns"]),
        "hold_whs_ns": float(values["hold_whs_ns"]),
        "drc_violations": 0,
        "routed": True,
        "constrained_paths_met_timing": True,
        "all_io_timing_qualified": False,
        "bitstream_generated": False,
        "ddr_calibrated_on_board": False,
        "fpga_debian_login": False,
        "qualification": "Vivado routed static-timing evidence only; no real board DDR calibration or boot",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--product-export-dir", type=Path, required=True)
    args = parser.parse_args()
    result = args.output_dir / "physical_reports" / "result.json"
    try:
        report = verify(args.output_dir, args.repository, args.product_export_dir)
        # 'x' avoids fabricating or overwriting a past PASS if the command is
        # replayed or any later run is incomplete.
        with result.open("x", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2, sort_keys=True)
            handle.write("\n")
    except (ValueError, OSError, KeyError, json.JSONDecodeError,
            subprocess.CalledProcessError) as error:
        print(f"NEXYS_PHYSICAL_REPORT_REJECTED: {error}", file=sys.stderr)
        return 4
    print(f"NEXYS_ROUTED_TIMING_ONLY: part={EXPECTED_PART} result={result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
