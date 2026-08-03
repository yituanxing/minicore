#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

OLD = "switch (address & 0xffU) {"
NEW = "switch (address & 0xfffU) {"
EXPECTED = 3


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: widen_rv32imu_pmp_csr_decode.py GENERATED_CPP")

    path = Path(sys.argv[1])
    text = path.read_text(encoding="utf-8")
    count = text.count(OLD)
    if count != EXPECTED:
        raise SystemExit(
            f"expected {EXPECTED} low-byte CSR decode switches, found {count}"
        )
    if NEW in text:
        raise SystemExit("PMP CSR decode was already widened")

    path.write_text(text.replace(OLD, NEW), encoding="utf-8")
    print(f"widened {count} PMP CSR decode switches in {path}")


if __name__ == "__main__":
    main()
