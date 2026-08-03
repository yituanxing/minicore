#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

LOW_BYTE = "switch (address & 0xffU) {"
FULL_ADDRESS = "switch (address) {"
WIDENED = "switch (address & 0xfffU) {"
EXPECTED = 3


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: widen_rv32imu_pmp_csr_decode.py GENERATED_CPP")

    path = Path(sys.argv[1])
    text = path.read_text(encoding="utf-8")

    low_byte_count = text.count(LOW_BYTE)
    full_address_count = text.count(FULL_ADDRESS)
    widened_count = text.count(WIDENED)

    if low_byte_count == EXPECTED and full_address_count == 0 and widened_count == 0:
        path.write_text(text.replace(LOW_BYTE, WIDENED), encoding="utf-8")
        print(f"widened {low_byte_count} PMP CSR decode switches in {path}")
        return

    if full_address_count == EXPECTED and low_byte_count == 0 and widened_count == 0:
        print(f"validated {full_address_count} full-address PMP CSR decode switches in {path}")
        return

    if widened_count == EXPECTED and low_byte_count == 0 and full_address_count == 0:
        print(f"validated {widened_count} already-widened PMP CSR decode switches in {path}")
        return

    raise SystemExit(
        "unexpected PMP CSR decode shape: "
        f"low-byte={low_byte_count}, full-address={full_address_count}, "
        f"widened={widened_count}; expected exactly {EXPECTED} switches in one form"
    )


if __name__ == "__main__":
    main()
