#!/usr/bin/env python3
"""Resolve one *real* G5 D2 late-boot trace against its exact vmlinux/System.map.

The source snapshot and System.map are built in the same workflow as the RTL
run. Never substitute a separately rebuilt kernel with a different SHA here.
Addresses outside the kernel's executable symbols remain explicitly unmapped
(OpenSBI and other privilege-mode code must not be assigned Linux functions).
"""

import argparse
import bisect
import hashlib
from pathlib import Path
import re


RESULT_PREFIX = "PRODUCT_G5_RESUME_DIAGNOSTIC_RESULT "
HEX_PC = re.compile(r"0x[0-9a-fA-F]+$")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def kernel_text_symbols(path: Path) -> tuple[list[int], list[str]]:
    text = []
    with path.open(encoding="ascii") as stream:
        for line in stream:
            fields = line.split()
            if len(fields) < 3 or fields[1] not in ("t", "T", "w", "W"):
                continue
            try:
                address = int(fields[0], 16)
            except ValueError:
                continue
            text.append((address, fields[2]))
    text.sort()
    return [addr for addr, _ in text], [name for _, name in text]


def parse_observations(line: str) -> list[tuple[str, int, int]]:
    parts = dict(token.split("=", 1) for token in line.split() if "=" in token)
    observations = []
    for key in ("last-exception-pc", "current-commit-pc"):
        value = parts.get(key, "")
        if HEX_PC.fullmatch(value):
            observations.append((key, int(value, 16), 1))
    recent = parts.get("recent-commit-pcs", "none")
    for number, value in enumerate(recent.split(",")):
        if HEX_PC.fullmatch(value):
            observations.append((f"recent-commit[{number}]", int(value, 16), 1))
    boundary = parts.get("timer-boundary-pc-top", "none")
    for value in boundary.split(","):
        if ":" not in value:
            continue
        pc, count = value.split(":", 1)
        if HEX_PC.fullmatch(pc) and count.isdecimal():
            observations.append(("interrupted-pc", int(pc, 16), int(count)))
    return observations


def symbolize(address: int, addresses: list[int], names: list[str]) -> str:
    if not addresses or address < addresses[0] or address > addresses[-1] + (1 << 20):
        return "UNMAPPED_NOT_PROVEN_LINUX"
    index = bisect.bisect_right(addresses, address) - 1
    if index < 0:
        return "UNMAPPED_NOT_PROVEN_LINUX"
    offset = address - addresses[index]
    # A hole this large is not reliably within the preceding code symbol.
    if offset >= (1 << 20):
        return "UNMAPPED_GAP_TOO_LARGE"
    return f"{names[index]}+0x{offset:x} (symbol_start=0x{addresses[index]:x})"


def render(log: Path, symbols: Path, vmlinux: Path) -> str:
    last = ""
    with log.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            if line.startswith(RESULT_PREFIX):
                last = line.rstrip("\n")
    if not last:
        raise ValueError("no finalized G5 diagnostic result; do not guess a PC")
    addresses, names = kernel_text_symbols(symbols)
    if not addresses:
        raise ValueError("no executable text symbols in exact System.map")
    observations = parse_observations(last)
    lines = [
        "PRODUCT_G5_FORENSIC_EXACT_KERNEL_SYMBOLS",
        f"vmlinux_sha256={sha256(vmlinux)}",
        f"system_map_sha256={sha256(symbols)}",
        f"trace={last}",
        "NOTE: outside-kernel/large-gap PCs deliberately remain unmapped;",
        "      timer-boundary interrupted PCs are more informative than IRQ-handler commit PCs.",
    ]
    for kind, pc, count in observations:
        lines.append(
            f"{kind} pc=0x{pc:016x} samples={count} kernel_symbol={symbolize(pc, addresses, names)}"
        )
    if not observations:
        lines.append("NO_PC_SAMPLES: guest diagnostic emitted no decodable execution PC")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--system-map", type=Path, required=True)
    parser.add_argument("--vmlinux", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(render(args.trace, args.system_map, args.vmlinux), end="")
    except (OSError, ValueError) as exc:
        parser.exit(2, f"PRODUCT_G5_FORENSIC_SYMBOLS_UNAVAILABLE: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
