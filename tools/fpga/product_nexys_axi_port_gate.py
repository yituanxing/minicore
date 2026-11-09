#!/usr/bin/env python3
"""Check *generated* Chisel Product RTL against the Nexys AXI bridge.

This is an RTL interface contract, not a substitute for Vivado's AXI bus
inference, simulation, MIG generation, physical timing or DDR calibration.
"""

import argparse
import json
import re
import sys
from pathlib import Path


TOP = "AetherCoreProductFpgaUartDdrBoard"
BRIDGE = "aether_product_nexys_axi_bridge"


def axi_contract() -> dict[str, tuple[str, int]]:
    result = {}
    for channel in ("aw", "ar"):
        for field, width in (("id", 4), ("addr", 28), ("len", 8),
                             ("size", 3), ("burst", 2), ("lock", 1),
                             ("cache", 4), ("prot", 3), ("qos", 4)):
            result[f"io_localDdrAxi_{channel}_bits_{field}"] = ("output", width)
        result[f"io_localDdrAxi_{channel}_valid"] = ("output", 1)
        result[f"io_localDdrAxi_{channel}_ready"] = ("input", 1)
    for field, width in (("data", 64), ("strb", 8), ("last", 1)):
        result[f"io_localDdrAxi_w_bits_{field}"] = ("output", width)
    result["io_localDdrAxi_w_valid"] = ("output", 1)
    result["io_localDdrAxi_w_ready"] = ("input", 1)
    for field, width in (("id", 4), ("resp", 2)):
        result[f"io_localDdrAxi_b_bits_{field}"] = ("input", width)
    result["io_localDdrAxi_b_valid"] = ("input", 1)
    result["io_localDdrAxi_b_ready"] = ("output", 1)
    for field, width in (("id", 4), ("data", 64), ("resp", 2), ("last", 1)):
        result[f"io_localDdrAxi_r_bits_{field}"] = ("input", width)
    result["io_localDdrAxi_r_valid"] = ("input", 1)
    result["io_localDdrAxi_r_ready"] = ("output", 1)
    result.update({
        "clock": ("input", 1), "reset": ("input", 1),
        "io_serialRx": ("input", 1), "io_serialTx": ("output", 1),
        "io_spiSclk": ("output", 1), "io_spiMosi": ("output", 1),
        "io_spiMiso": ("input", 1), "io_spiCsN": ("output", 1),
        "io_rawClockLocked": ("input", 1),
        "io_rawDdrCalibrated": ("input", 1),
        "io_boardImageFailed": ("input", 1),
        "io_cpuReleased": ("output", 1), "io_bootFault": ("output", 1),
    })
    return result


def module_header(text: str, name: str) -> str:
    match = re.search(r"\bmodule\s+" + re.escape(name) + r"\s*\((.*?)\)\s*;",
                      text, re.S)
    if match is None:
        raise ValueError(f"module header absent: {name}")
    return match.group(1)


def ports(header: str, grouped_ansi: bool = False) -> dict[str, tuple[str, int]]:
    out = {}
    if grouped_ansi:
        # CIRCT's real SV emitter groups adjacent declarations with identical
        # direction and width: `input clock,\n reset,\n io_foo,`.
        # The continuation names must inherit the previous ANSI declaration.
        header = re.sub(r"//[^\n]*", "", header)
        previous = None
        decl = re.compile(r"^(input|output)\s+(?:(?:wire|logic|reg)\s+)?"
                          r"(?:\[\s*(\d+)\s*:\s*(\d+)\s*\]\s+)?([A-Za-z_]\w*)$")
        for field in header.split(","):
            field = field.strip()
            if not field:
                continue
            match = decl.fullmatch(field)
            if match:
                direction, upper, lower, name = match.groups()
                width = abs(int(upper) - int(lower)) + 1 if upper else 1
                previous = (direction, width)
            elif re.fullmatch(r"[A-Za-z_]\w*", field) and previous:
                name = field
            else:
                raise ValueError(f"unrecognized grouped Product ANSI declaration: {field[:90]}")
            if name in out:
                raise ValueError(f"duplicate port declaration: {name}")
            out[name] = previous
        return out
    pattern = re.compile(
        r"^\s*(input|output)\s+(?:(?:wire|logic|reg)\s+)?"
        r"(?:\[\s*(\d+)\s*:\s*(\d+)\s*\]\s+)?([A-Za-z_]\w*)\s*(?:,|$)",
        re.M,
    )
    for direction, upper, lower, name in pattern.findall(header):
        width = abs(int(upper) - int(lower)) + 1 if upper else 1
        if name in out:
            raise ValueError(f"duplicate port declaration: {name}")
        out[name] = (direction, width)
    return out


def verify_bridge(bridge: str, product_rtl: str) -> dict:
    product_ports = ports(module_header(product_rtl, TOP), grouped_ansi=True)
    bridge_ports = ports(module_header(bridge, BRIDGE))
    contract = axi_contract()
    for name, expected in contract.items():
        if product_ports.get(name) != expected:
            raise ValueError(f"Product generated port mismatch: {name} expected={expected} actual={product_ports.get(name)}")
    named_connections = re.findall(r"\.([A-Za-z_]\w*)\s*\(\s*([^()]*)\s*\)", bridge)
    mapped = {name: value.strip() for name, value in named_connections}
    if len(named_connections) != len(mapped):
        raise ValueError("duplicate named Product connection in bridge")
    for name in contract:
        if name not in mapped:
            raise ValueError(f"Product port not connected in bridge: {name}")
    expected_nonaxi = {
        "clock": "cpu_clk_50", "reset": "~cpu_aresetn",
        "io_serialRx": "usb_uart_rxd", "io_serialTx": "usb_uart_txd",
        "io_spiSclk": "sdio_clk", "io_spiMosi": "sdio_cmd",
        "io_spiMiso": "sdio_dat0", "io_spiCsN": "sdio_dat3",
        "io_rawClockLocked": "clock_locked",
        "io_rawDdrCalibrated": "ddr_mem_ok",
        "io_boardImageFailed": "board_image_failed",
        "io_cpuReleased": "cpu_released", "io_bootFault": "boot_fault",
    }
    for name, target in expected_nonaxi.items():
        if mapped.get(name) != target:
            raise ValueError(f"bridge physical signal mismatch: {name} must map to {target}")
    for channel in ("aw", "w", "b", "ar", "r"):
        for name in contract:
            prefix = f"io_localDdrAxi_{channel}_"
            if not name.startswith(prefix):
                continue
            field = name[len(prefix):].removeprefix("bits_").upper()
            target = (f"product_{channel}addr" if field == "ADDR"
                      else f"M_AXI_{channel.upper()}{field}")
            if mapped[name] != target:
                raise ValueError(f"bridge AXI wiring mismatch: {name} must map to {target}")
    # Any additional generated Product input must be wired. Unused diagnostics
    # may be omitted, but a new required input must not float silently.
    for name, (direction, _) in product_ports.items():
        if direction == "input" and name not in mapped:
            raise ValueError(f"new Product input has no physical source: {name}")
    for channel, fields in {
        "AW": {"ID": 4, "ADDR": 29, "LEN": 8, "SIZE": 3, "BURST": 2,
               "LOCK": 1, "CACHE": 4, "PROT": 3, "QOS": 4,
               "VALID": 1, "READY": 1},
        "W": {"DATA": 64, "STRB": 8, "LAST": 1, "VALID": 1, "READY": 1},
        "B": {"ID": 4, "RESP": 2, "VALID": 1, "READY": 1},
        "AR": {"ID": 4, "ADDR": 29, "LEN": 8, "SIZE": 3, "BURST": 2,
               "LOCK": 1, "CACHE": 4, "PROT": 3, "QOS": 4,
               "VALID": 1, "READY": 1},
        "R": {"ID": 4, "DATA": 64, "RESP": 2, "LAST": 1,
              "VALID": 1, "READY": 1},
    }.items():
        for field, width in fields.items():
            name = f"M_AXI_{channel}{field}"
            # Slave-to-master readiness/response direction for the interface.
            incoming = field == "READY" and channel in ("AW", "W", "AR")
            incoming |= channel in ("B", "R") and field != "READY"
            direction = "input" if incoming else "output"
            if bridge_ports.get(name) != (direction, width):
                raise ValueError(f"bridge AXI mismatch: {name}, expected {direction}[{width}]")
            if f"M_AXI {channel}{field}" not in bridge:
                raise ValueError(f"Vivado AXI field annotation missing: {name}")
    if not re.search(r"assign\s+M_AXI_AWADDR\s*=\s*\{\s*1'b0\s*,\s*product_awaddr\s*\}", bridge):
        raise ValueError("AWADDR must zero-extend the local 28-bit Product address")
    if not re.search(r"assign\s+M_AXI_ARADDR\s*=\s*\{\s*1'b0\s*,\s*product_araddr\s*\}", bridge):
        raise ValueError("ARADDR must zero-extend the local 28-bit Product address")
    for port, direction in (("cpu_clk_50", "input"), ("cpu_aresetn", "input"),
                            ("clock_locked", "input"), ("ddr_mem_ok", "input"),
                            ("board_image_failed", "input")):
        if bridge_ports.get(port) != (direction, 1):
            raise ValueError(f"bridge physical control port mismatch: {port}")
    return {
        "status": "PRODUCT_NEXYS_AXIMM_PORTS_ONLY",
        "product_top": TOP,
        "product_axi_addr_bits": 28,
        "smartconnect_axi_addr_bits": 29,
        "axi_data_bits": 64,
        "axi_id_bits": 4,
        "mapped_product_ports": len(contract),
        "vivado_bus_inference_verified": False,
        "mig_generated": False,
        "routed": False,
        "ddr_calibrated": False,
        "debian_login": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rtl-dir", type=Path, required=True)
    parser.add_argument("--bridge", type=Path, required=True)
    parser.add_argument("--output", type=Path, help="new evidence file; no overwrite")
    args = parser.parse_args()
    try:
        files = sorted(args.rtl_dir.glob("*.sv"))
        if not files:
            raise ValueError("no generated SystemVerilog; run the Product RTL export")
        top_candidates = [p.read_text(encoding="utf-8") for p in files
                          if re.search(r"\bmodule\s+" + TOP + r"\s*\(",
                                       p.read_text(encoding="utf-8"))]
        if len(top_candidates) != 1:
            raise ValueError(f"expected one generated Product module, got {len(top_candidates)}")
        report = verify_bridge(args.bridge.read_text(encoding="utf-8"), top_candidates[0])
        encoded = json.dumps(report, sort_keys=True, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8") as handle:
                handle.write(encoded)
        print(encoded, end="")
        return 0
    except (ValueError, OSError) as error:
        print(f"PRODUCT_NEXYS_PORTS_NOT_VERIFIED: {error}", file=sys.stderr)
        return 4


if __name__ == "__main__":
    sys.exit(main())
