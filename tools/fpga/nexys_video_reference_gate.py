#!/usr/bin/env python3
"""Fail-closed provenance and interface gate for the Nexys Video DDR reference.

This verifies *external MIT-licensed collateral*, not a Product bitstream.
Keep the upstream LICENSE.md when copying any portion of that reference.
"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


UPSTREAM = "eugene-tarassov/vivado-risc-v"
COMMIT = "1e6b86aebe5ba6675a1193ec12b417c1f7cb8bf0"
FILES = {
    "board/nexys-video/riscv-2026.1.tcl": "da8f413e7fc5a69481e64043c9e7df038acf60bc",
    "board/nexys-video/top.xdc": "d252b3a3c7b0fb25e06cf77a1f710a82d11b0472",
    "board/nexys-video/sdc.xdc": "06cd0b7fb645d7d12ede4d798ebaedee7f2b31b5",
    "board/nexys-video/uart.xdc": "2aba6558eeb641c880bf24714a85b10b5b21da5e",
    "board/mem-reset-control.v": "24ede923ba5f31339f862dbd90330903153cbe3f",
}


def git_blob_sha(raw: bytes) -> str:
    return hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_contract(files: dict[str, str]) -> None:
    tcl = files["board/nexys-video/riscv-2026.1.tcl"]
    require("proc create_hier_cell_DDR" in tcl, "DDR hierarchy is absent")
    require("proc write_mig_file_riscv_mig_7series_0_0" in tcl,
            "MIG PRJ writer is absent")
    for needle in (
        "<C0_S_AXI_ADDR_WIDTH>29</C0_S_AXI_ADDR_WIDTH>",
        "<C0_S_AXI_DATA_WIDTH>128</C0_S_AXI_DATA_WIDTH>",
        "<C0_S_AXI_ID_WIDTH>4</C0_S_AXI_ID_WIDTH>",
        "<C0_S_AXI_SUPPORTS_NARROW_BURST>0</C0_S_AXI_SUPPORTS_NARROW_BURST>",
        "CONFIG.NUM_CLKS {2}",
        "get_bd_intf_pins axi_smc_1/S00_AXI",
        "get_bd_intf_pins mig_7series_0/S_AXI",
        "get_bd_pins axi_smc_1/aclk1",
        "get_bd_pins mig_7series_0/ui_clk",
        "get_bd_pins mig_7series_0/device_temp_i",
        "CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {50.000}",
        "CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {200.000}",
    ):
        require(needle in tcl, f"upstream DDR/clock contract changed: {needle}")

    xdc_checks = {
        "board/nexys-video/top.xdc": {"R4": "sys_clock", "G4": "reset"},
        "board/nexys-video/uart.xdc": {"V18": "usb_uart_rxd", "AA19": "usb_uart_txd"},
        "board/nexys-video/sdc.xdc": {
            "W19": "sdio_clk", "W20": "sdio_cmd", "V19": "sdio_dat[0]",
            "U18": "sdio_dat[3]", "V20": "sdio_reset", "T18": "sdio_cd",
        },
    }
    for path, pins in xdc_checks.items():
        xdc = files[path]
        for pin, signal in pins.items():
            # Same-line pin/port pairs prevent accepting only a nearby comment.
            found = any(re.search(r"\bPACKAGE_PIN\s+" + re.escape(pin) + r"\b", line)
                        and signal in line for line in xdc.splitlines())
            require(found, f"board pin changed or missing: {path} {signal}={pin}")

    reset = files["board/mem-reset-control.v"]
    for signal in ("mmcm_locked", "calib_complete", "ui_clk_sync_rst", "mem_ok"):
        require(re.search(r"\b" + signal + r"\b", reset) is not None,
                f"DDR readiness dependency missing: {signal}")


def verify_reference(root: Path) -> dict:
    require(root.is_dir(), f"reference checkout not found: {root}")
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True,
        text=True, check=False,
    )
    require(result.returncode == 0 and result.stdout.strip() == COMMIT,
            f"reference checkout must be pinned to {COMMIT}")
    content = {}
    for relative, wanted_sha in FILES.items():
        target = root / relative
        require(target.is_file(), f"missing reference source: {relative}")
        raw = target.read_bytes()
        require(git_blob_sha(raw) == wanted_sha,
                f"reference source bytes changed: {relative}")
        content[relative] = raw.decode("utf-8")
    validate_contract(content)
    return {
        "status": "NEXYS_REFERENCE_SOURCE_ONLY",
        "upstream": UPSTREAM,
        "upstream_commit": COMMIT,
        "device": "xc7a200tsbg484-1",
        "upstream_blob_shas": FILES,
        "upstream_mig_narrow_enabled": False,
        "product_ddr_abi": {"data_bits": 64, "id_bits": 4, "addr_bits": 28,
                            "size_values": [0, 1, 2, 3], "cpu_clock_hz": 50000000},
        "ready_for_vivado_import": True,
        "mig_generated": False,
        "smartconnect_verified": False,
        "placed": False,
        "routed": False,
        "fmax_qualified": False,
        "ddr_calibrated": False,
        "debian_login": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-root", type=Path, required=True,
                        help="pinned external vivado-risc-v checkout")
    parser.add_argument("--output", type=Path,
                        help="optional evidence JSON file; never overwritten")
    args = parser.parse_args()
    try:
        report = verify_reference(args.reference_root)
        output = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8") as handle:
                handle.write(output)
        print(output, end="")
        return 0
    except (ValueError, OSError) as error:
        print(f"NEXYS_REFERENCE_NOT_VERIFIED: {error}", file=sys.stderr)
        return 4


if __name__ == "__main__":
    sys.exit(main())
