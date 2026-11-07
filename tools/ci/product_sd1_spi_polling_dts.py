#!/usr/bin/env python3
"""Emit an isolated polling-only Product SD1 DTS candidate.

Upstream Linux 6.6.143 spi-oc-tiny selects its tight PIO polling loop when
its optional SPI IRQ is absent. Keep UART/PLIC topology, GPIO-CS, MMC child,
clock speeds and all other DTS bytes untouched. This is a CANDIDATE ONLY:
it must get a distinct DTB/firmware identity and may not restore an IRQ-DTB
guest checkpoint or be reported as Linux/Debian RTL performance evidence.
"""

from __future__ import annotations
import argparse
import hashlib
import re
from pathlib import Path

SPI_NODE = re.compile(r"^\s*spi@[0-9a-fA-F]+\s*\{\s*$")
SPI_IRQ_PARENT = re.compile(r"^\s*interrupt-parent\s*=\s*<&plic>;\s*$")
SPI_IRQ = re.compile(r"^\s*interrupts\s*=\s*<\d+>;\s*$")


def make_polling_candidate(original: str) -> str:
    lines = original.splitlines(keepends=True)
    spi_starts = [i for i, line in enumerate(lines) if SPI_NODE.fullmatch(line.strip("\r\n"))]
    if len(spi_starts) != 1:
        raise ValueError("expected exactly one Product SPI controller node")
    start = spi_starts[0]
    depth = 0
    end = None
    for i in range(start, len(lines)):
        # This emitter generates no brace-bearing comments or property strings.
        depth += lines[i].count("{") - lines[i].count("}")
        if depth == 0:
            end = i
            break
        if depth < 0:
            raise ValueError("malformed DTS nesting")
    if end is None:
        raise ValueError("unterminated Product SPI node")
    node = "".join(lines[start:end + 1])
    for required in ('compatible = "opencores,tiny-spi-rtlsvn2";',
                     'cs-gpios = <&sd_cs_gpio 0 1>;',
                     'mmc@0 {', 'compatible = "mmc-spi-slot";',
                     'spi-max-frequency = <'):
        if required not in node:
            raise ValueError(f"unrecognized Product SPI SD device tree: {required}")
    removed = {"parent": 0, "irq": 0}
    out = []
    depth = 0
    for i, line in enumerate(lines):
        if start <= i <= end:
            # Remove ONLY direct properties of the chosen SPI node.
            if depth == 1 and SPI_IRQ_PARENT.fullmatch(line.strip("\r\n")):
                removed["parent"] += 1
                continue
            if depth == 1 and SPI_IRQ.fullmatch(line.strip("\r\n")):
                removed["irq"] += 1
                continue
            depth += line.count("{") - line.count("}")
        out.append(line)
    if removed != {"parent": 1, "irq": 1}:
        raise ValueError("expected exactly two direct SPI IRQ properties")
    candidate = "".join(out)
    if candidate == original or "mmc-spi-slot" not in candidate:
        raise ValueError("no distinct, real SPI poll-mode candidate")
    return candidate


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--expected-input-sha256", required=True)
    args = p.parse_args()
    if args.input.resolve() == args.output.resolve() or args.output.exists():
        raise SystemExit("SD1_SPI_POLLING_REJECTED: refuse to overwrite source/output")
    original = args.input.read_bytes()
    sha = hashlib.sha256(original).hexdigest()
    if sha != args.expected_input_sha256:
        raise SystemExit("SD1_SPI_POLLING_REJECTED: unqualified source DTS hash")
    candidate = make_polling_candidate(original.decode("utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(candidate.encode("utf-8"))
    print("SD1_SPI_POLLING_DTS_CANDIDATE: status=PREPARED_NOT_RTL_QUALIFIED")
    print(f"source_sha256={sha}")
    print(f"candidate_sha256={hashlib.sha256(candidate.encode('utf-8')).hexdigest()}")
    print("spi_irq=absent spi_cs_gpio=unchanged sd_protocol=unchanged")


if __name__ == "__main__":
    main()
