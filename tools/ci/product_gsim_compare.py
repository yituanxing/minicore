#!/usr/bin/env python3
"""Fail-closed same-cycle Product GSIM/Verilator CPU+RAM+UART comparison.

The engines expose different trace prefixes. Compare normalized fields, never
the incidental formatting. GSIM fields missing from this contract are errors,
not zero-valued defaults. Only a real OpenSBI + S-mode UART G3 is accepted.
"""

import argparse
from pathlib import Path
import re
import sys


def summary(log: Path, prefix: str) -> dict[str, str]:
    lines = [line for line in log.read_text(errors="replace").splitlines()
             if line.startswith(prefix + " ")]
    if len(lines) != 1:
        raise ValueError(f"{log}: expected exactly one {prefix} terminal record, saw {len(lines)}")
    return dict(re.findall(r"(?:^|\s)([A-Za-z][\w-]*)=([^\s]+)", lines[0]))


def require_strict_trace(detail: dict[str, str]) -> None:
    """An omitted fast-prefix digest must never qualify the strict G3 A/B."""
    if detail.get("trace_mode") != "strict-same-cycle":
        raise ValueError("GSIM strict G3 requires trace_mode=strict-same-cycle")
    for key in ("commit_digest", "wire_digest"):
        if not re.fullmatch(r"0x[0-9a-fA-F]+", detail.get(key, "")):
            raise ValueError(f"GSIM strict G3 missing real {key}")


def canonical(raw: dict[str, str], is_gsim: bool) -> dict[str, str]:
    aliases = {
        "cycles": ("guest_cycles", "cycles") if is_gsim else ("cycles",),
        "commits": ("commits", "retires") if is_gsim else ("commits",),
        "exceptions": ("exceptions",),
        "interrupts": ("interrupts",),
        "stip": ("stip",),
        "seip": ("seip",),
        "mtime": ("mtime",),
        "mtimecmp": ("mtimecmp",),
        "ram-fingerprint": ("ram-fingerprint", "ram_digest", "ram_fingerprint") if is_gsim else ("ram-fingerprint",),
        "uart-bytes": ("uart-bytes", "uart_bytes") if is_gsim else ("uart-bytes",),
        "uart-fingerprint": ("uart-fingerprint", "uart_fingerprint") if is_gsim else ("uart-fingerprint",),
        "banner": ("banner", "opensbi_banner") if is_gsim else ("banner",),
    }
    result: dict[str, str] = {}
    for logical, options in aliases.items():
        found = [raw[k] for k in options if k in raw]
        if len(found) != 1:
            raise ValueError(f"{'GSIM' if is_gsim else 'Verilator'}: missing/ambiguous {logical}, variants={options}")
        token = found[0].lower()
        try:
            result[logical] = str(int(token, 16 if token.startswith("0x") else 10))
        except ValueError as error:
            raise ValueError(f"{logical}: not an integer {found[0]!r}") from error
    pcs = raw.get("recent-pcs", "").split(",")
    if not pcs or not all(pc.startswith("0x") for pc in pcs):
        raise ValueError(f"{'GSIM' if is_gsim else 'Verilator'}: missing recent retire PCs")
    result["recent-pcs"] = ",".join(str(int(pc, 16)) for pc in pcs)
    for logical in ("compressed", "opensbi-compressed", "linux-compressed",
                    "last-exception-pc", "last-exception-cause", "last-exception-value"):
        if logical not in raw:
            raise ValueError(f"{'GSIM' if is_gsim else 'Verilator'}: missing CPU state {logical}")
        token = raw[logical].lower()
        result[logical] = str(int(token, 16 if token.startswith("0x") else 10))
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verilator", type=Path, required=True)
    ap.add_argument("--gsim", type=Path, required=True)
    ap.add_argument("--cycles", type=int, required=True)
    args = ap.parse_args()
    v_log = args.verilator.read_text(errors="replace")
    g_log = args.gsim.read_text(errors="replace")
    for label, body in (("Verilator", v_log), ("GSIM", g_log)):
        for marker in ("OpenSBI v1.6", "RV64 OpenSBI S-mode payload PASS"):
            if marker not in body:
                raise ValueError(f"{label}: real G3 UART marker absent: {marker}")
    v_raw = summary(args.verilator, "L32_OPENSBI_TIMEOUT")
    g_raw = summary(args.gsim, "L32_OPENSBI_TIMEOUT")
    g_detail = summary(args.gsim, "AETHERCORE_PRODUCT_GSIM_PREFIX")
    require_strict_trace(g_detail)
    if int(g_detail.get("host_bridge_calls", "0")) <= 0 or int(g_detail.get("host_imem_calls", "0")) <= 0:
        raise ValueError("GSIM never exercised actual external host bridge/fetch")
    if int(g_detail.get("guest_cycles", "0")) != args.cycles - 4:
        raise ValueError("GSIM guest cycle accounting does not match 4-reset-step RTL")
    v = canonical(v_raw, False)
    g = canonical(g_raw, True)
    if v["cycles"] != str(args.cycles) or g["cycles"] != str(args.cycles):
        raise ValueError(f"Not same-cycle: required={args.cycles} Verilator={v['cycles']} GSIM={g['cycles']}")
    if int(v["commits"]) <= 0 or int(v["uart-bytes"]) <= 0 or v["banner"] != "1":
        raise ValueError("No real OpenSBI CPU retirement/UART evidence")
    disagreements = {key: (v[key], g[key]) for key in v if v[key] != g[key]}
    if disagreements:
        print("PRODUCT_GSIM_G3_STATE_DIFF: " + repr(disagreements), file=sys.stderr)
        return 1
    print("PRODUCT_GSIM_G3_SAME_CYCLE_PASS " + " ".join(f"{k}={v[k]}" for k in sorted(v)))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError) as error:
        print("PRODUCT_GSIM_G3_REJECTED:", error, file=sys.stderr)
        sys.exit(2)
