#!/usr/bin/env python3
"""Check strict vs fast Product GSIM functional state (not cycle-trace A/B).

Both executions are the *same* generated Product RTL and host memory model.
The fast variant intentionally omits two expensive trace digests, so this
comparison can certify only retained architectural counters and final state.
"""

import argparse
from pathlib import Path
import sys

from product_gsim_compare import require_strict_trace, summary


TERMINAL_FIELDS = (
    "cycles", "commits", "compressed", "opensbi-compressed", "linux-compressed",
    "uart-bytes", "uart-fingerprint", "banner", "milestone", "exceptions",
    "interrupts", "stip", "seip", "mtime", "mtimecmp", "last-exception-pc",
    "last-exception-cause", "last-exception-value", "ram-fingerprint", "recent-pcs",
)
DETAIL_FIELDS = (
    "guest_cycles", "retires", "exceptions", "interrupts", "stip", "seip",
    "last_pc", "ram_digest", "uart_bytes", "uart_digest", "mtime", "mtimecmp",
    "exit_valid", "exit_code", "opensbi_banner", "linux_banner",
    "milestone_cycle", "host_bridge_calls", "host_imem_calls",
    "host_data_calls", "host_ptw_calls",
)


def bridge_getter_budget(detail: dict[str, str], cycles: int) -> tuple[int, int, int, int]:
    """Measured callbacks; conservative accessor-count bounds, NOT host speedup."""
    calls = int(detail["host_bridge_calls"])
    fetch = int(detail["host_imem_calls"])
    data = int(detail["host_data_calls"])
    ptw = int(detail["host_ptw_calls"])
    if cycles <= 0 or calls <= 0 or any(n < 0 or n > calls for n in (fetch, data, ptw)):
        raise ValueError("invalid Product host callback/getter budget counters")
    strict_getters = 14 * calls
    # Three unconditional valid bits; IMEM=2, MEM=4..7, PTW=1 extra.
    fast_min = 3 * calls + 2 * fetch + 4 * data + ptw
    fast_max = fast_min + 3 * data
    return calls, strict_getters, fast_min, fast_max


def compare_functional(strict_terminal: dict[str, str], fast_terminal: dict[str, str],
                       strict_detail: dict[str, str], fast_detail: dict[str, str],
                       cycles: int) -> None:
    require_strict_trace(strict_detail)
    if fast_detail.get("trace_mode") != "fast-g4-unqualified":
        raise ValueError("fast run must report trace_mode=fast-g4-unqualified")
    for key in ("commit_digest", "wire_digest"):
        if fast_detail.get(key) != "OMITTED":
            raise ValueError(f"fast run must explicitly omit {key}")

    for label, strict, fast, fields in (
        ("terminal", strict_terminal, fast_terminal, TERMINAL_FIELDS),
        ("detail", strict_detail, fast_detail, DETAIL_FIELDS),
    ):
        for key in fields:
            if key not in strict or key not in fast:
                raise ValueError(f"missing {label} functional field {key}")
            if strict[key] != fast[key]:
                raise ValueError(f"{label} {key} differs strict={strict[key]} fast={fast[key]}")
    if strict_terminal["cycles"] != str(cycles) or strict_detail["guest_cycles"] != str(cycles - 4):
        raise ValueError("exact 4-reset-step cycle window missing")
    if int(strict_terminal["commits"]) <= 0 or int(strict_terminal["uart-bytes"]) <= 0:
        raise ValueError("real CPU retirement and UART required")
    if strict_detail["opensbi_banner"] != "1" or strict_terminal["banner"] != "1":
        raise ValueError("real OpenSBI marker required")
    if int(strict_detail["host_bridge_calls"]) <= 0 or int(strict_detail["host_imem_calls"]) <= 0:
        raise ValueError("real Product GSIM host bridge/fetch required")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict", required=True, type=Path)
    parser.add_argument("--fast", required=True, type=Path)
    parser.add_argument("--cycles", type=int, required=True)
    args = parser.parse_args()

    for label, path in (("strict", args.strict), ("fast", args.fast)):
        contents = path.read_text(errors="replace")
        for marker in ("OpenSBI v1.6", "RV64 OpenSBI S-mode payload PASS",
                       "AETHERCORE_PRODUCT_GSIM_FIXED_CYCLE_UNQUALIFIED_REQUIRES_VERILATOR_AB"):
            if marker not in contents:
                raise ValueError(f"{label} real G3 guest/mode marker missing: {marker}")

    strict_detail = summary(args.strict, "AETHERCORE_PRODUCT_GSIM_PREFIX")
    fast_detail = summary(args.fast, "AETHERCORE_PRODUCT_GSIM_PREFIX")
    compare_functional(
        summary(args.strict, "L32_OPENSBI_TIMEOUT"),
        summary(args.fast, "L32_OPENSBI_TIMEOUT"),
        strict_detail, fast_detail, args.cycles,
    )
    calls, strict_getters, fast_min, fast_max = bridge_getter_budget(strict_detail, args.cycles)
    print(f"PRODUCT_GSIM_FAST_AB_FUNCTIONAL_STATE_MATCH cycles={args.cycles} "
          "same_compiled_product_model=1 strict_trace_vs_fast_omitted=1 "
          "not_g4_same_cycle_verilator_ab=1 not_pid1=1")
    print(f"PRODUCT_GSIM_FAST_AB_HOST_BRIDGE_ACCESSORS callback_calls={calls} "
          f"calls_per_guest_cycle={calls / args.cycles:.6f} "
          f"strict_getters={strict_getters} fast_getters_min={fast_min} "
          f"fast_getters_max={fast_max} count_only_not_measured_speedup=1 "
          "main_loop_getters_excluded=1")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as exc:
        print(f"PRODUCT_GSIM_FAST_AB_REJECTED: {exc}", file=sys.stderr)
        sys.exit(2)
