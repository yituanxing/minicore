#!/usr/bin/env python3
"""Fail-closed exact-cycle real G5 Debian RAM Top GSIM/Verilator comparison.

Reuse the already reviewed G3 transport-state parser, but additionally demand
all RV64GC retirement digests. A 30M prefix is NOT a Debian PID1/F7 numerical
qualification, especially when the prefix has not retired an F/D operation.
"""

import argparse
from pathlib import Path
import re
import sys

from product_gsim_compare import canonical, require_strict_trace, summary


G5_FIELDS = ("g5-retire-digest", "g5-int-digest", "g5-fp-digest",
             "g5-fp-writes", "g5-fflags-events")


def parse_g5(raw: dict[str, str], engine: str) -> dict[str, str]:
    fields = canonical(raw, engine == "GSIM")
    for key in G5_FIELDS:
        token = raw.get(key, "")
        if key.endswith("digest"):
            if not re.fullmatch(r"0x[0-9a-fA-F]+", token):
                raise ValueError(f"{engine}: missing exact floating/integer {key}")
            fields[key] = str(int(token, 16))
        else:
            if not re.fullmatch(r"[0-9]+", token):
                raise ValueError(f"{engine}: missing/invalid {key}")
            fields[key] = str(int(token))
    return fields


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verilator", type=Path, required=True)
    parser.add_argument("--gsim", type=Path, required=True)
    parser.add_argument("--cycles", type=int, required=True)
    args = parser.parse_args()
    if not (1_000_000 <= args.cycles <= 60_000_000):
        raise ValueError("G5 only allows a bounded 1M..60M comparison")

    v_log = args.verilator.read_text(errors="replace")
    g_log = args.gsim.read_text(errors="replace")
    for label, body in (("Verilator", v_log), ("GSIM", g_log)):
        if "OpenSBI v1.6" not in body:
            raise ValueError(f"{label}: real Debian OpenSBI UART banner absent")
    if "actual_top=AetherCoreProductDebianRV64SimTop" not in g_log:
        raise ValueError("GSIM did not instantiate the real G5 Debian Top")

    ver = parse_g5(summary(args.verilator, "L32_OPENSBI_TIMEOUT"), "Verilator")
    gsim = parse_g5(summary(args.gsim, "L32_OPENSBI_TIMEOUT"), "GSIM")
    detail = summary(args.gsim, "AETHERCORE_PRODUCT_GSIM_PREFIX")
    require_strict_trace(detail)
    if int(detail.get("host_bridge_calls", "0")) <= 0 or int(detail.get("host_imem_calls", "0")) <= 0:
        raise ValueError("GSIM never exercised G5 memory host bridge/fetch")
    if int(detail.get("guest_cycles", "0")) != args.cycles - 4:
        raise ValueError("G5 GSIM reset/guest cycle accounting mismatch")
    if ver["cycles"] != str(args.cycles) or gsim["cycles"] != str(args.cycles):
        raise ValueError("G5 simulator results are not from the same RTL cycle")
    if int(ver["commits"]) <= 0 or int(ver["uart-bytes"]) <= 0 or ver["banner"] != "1":
        raise ValueError("G5 firmware did not execute real OpenSBI CPU/UART")
    mismatch = {k: (ver[k], gsim[k]) for k in ver if ver[k] != gsim[k]}
    if mismatch:
        print("PRODUCT_GSIM_G5_STATE_DIFF: " + repr(mismatch), file=sys.stderr)
        return 1
    print("PRODUCT_GSIM_G5_SAME_CYCLE_PASS " + " ".join(
        f"{key}={value}" for key, value in sorted(ver.items())))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError) as error:
        print("PRODUCT_GSIM_G5_REJECTED:", error, file=sys.stderr)
        sys.exit(2)
